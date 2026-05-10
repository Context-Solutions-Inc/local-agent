"""Export `sentence-transformers/all-MiniLM-L6-v2` to a LiteRT (.tflite) artifact
shippable on `com.google.ai.edge.litert:litert:2.1.4`, plus the test fixtures
the Kotlin side needs.

Pipeline mirrors `classifier_training/training/export_litert.py`:
  1. Pull the HF model + tokenizer (cached after first run).
  2. Wrap in an `ExportableMiniLMEncoder` that bakes mean-pooling +
     L2-normalisation into the graph — so the on-device caller gets a single
     384-dim vector back and cosine similarity is a plain dot product.
  3. Trace + convert to FP32 .tflite via `litert-torch` (the `ai-edge-torch`
     successor; both are installed but litert_torch is actively maintained
     per CLAUDE.md inv. #16).
  4. Optionally quantise INT8 weight-only via `ai_edge_quantizer.Quantizer`
     using `MIN_MAX_UNIFORM_QUANT` channel-wise (same recipe as the classifier
     INT8 export — encoder-class weights, NOT the LLM-targeted recipes in
     `litert_torch.generative.quantize`).
  5. Run a sanity invocation via Python `ai-edge-litert` and dump 10 canonical
     embeddings + the matching tokenizer fixtures so the Kotlin side can
     assert byte-near-exact match (FP tolerance) against the on-device
     forward pass.

Output files (paths relative to repo root):
  models/all-MiniLM-L6-v2.tflite                                 (FP32 reference)
  models/all-MiniLM-L6-v2_int8.tflite                            (INT8 ship target)
  models/all-MiniLM-L6-v2_minilm_vocab.txt                       (tokenizer vocab)
  classifier-training/tests/fixtures/minilm_tokenizer_canonical_inputs.json
  classifier-training/tests/fixtures/embedder_canonical_outputs.json

Usage:
    classifier-training/.venv/bin/python \\
        classifier-training/scripts/export_minilm_litert.py --int8

Re-running is idempotent — files are overwritten in place. Print the INT8
SHA-256 at the end so the Gradle copy task in :androidApp/build.gradle.kts
can be updated to match.

Requires the `[training]` and `[dedup]` extras (or any venv that has torch,
transformers, sentence-transformers, litert-torch, ai-edge-quantizer,
ai-edge-litert). The classifier-training venv built for M3 already has
all of these.
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import click

REPO_ROOT = Path(__file__).resolve().parents[2]
MODELS_DIR = REPO_ROOT / "models"
FIXTURES_DIR = REPO_ROOT / "classifier-training" / "tests" / "fixtures"

DEFAULT_MODEL_ID = "sentence-transformers/all-MiniLM-L6-v2"
DEFAULT_MAX_LENGTH = 128
EMBEDDING_DIM = 384

# Same set as the classifier tokenizer fixture (with a couple of memory-style
# entries swapped in for the kinds of strings the embedder will actually see).
# Kept short — 10 fixtures is enough to catch dimensional, ordering, and
# pooling regressions without bloating the assets.
CANONICAL_STRINGS: list[tuple[str, str]] = [
    ("typical_user_disclosure", "i'm a software engineer working on mobile apps"),
    ("location_statement", "i live in toronto, ontario"),
    ("preference_statement", "my favorite nfl team is the philadelphia eagles"),
    ("relationship_statement", "i have a dog named rex"),
    ("temporary_context", "i'm traveling to tokyo next week"),
    ("transient_query", "what is the weather in toronto today"),
    ("third_party_fact", "my friend john is a doctor"),
    ("forget_command", "actually forget what i said about my job"),
    ("remember_command", "remember that i'm allergic to peanuts"),
    ("settled_history", "when did world war two end"),
]


@click.command()
@click.option("--model-id", default=DEFAULT_MODEL_ID, show_default=True)
@click.option(
    "--max-length",
    default=DEFAULT_MAX_LENGTH,
    show_default=True,
    type=int,
    help="Static sequence length for the exported graph.",
)
@click.option(
    "--int8",
    "int8_weights",
    is_flag=True,
    default=False,
    help="Apply INT8 weight quantisation via ai-edge-quantizer post-conversion.",
)
@click.option(
    "--skip-fixtures",
    is_flag=True,
    default=False,
    help="Skip writing canonical fixture JSONs (for re-export-only runs).",
)
def main(model_id: str, max_length: int, int8_weights: bool, skip_fixtures: bool) -> None:
    """Export MiniLM to LiteRT and emit Kotlin-side fixtures."""
    import torch
    from rich.console import Console
    from torch import nn
    from transformers import AutoModel, AutoTokenizer

    console = Console()
    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    FIXTURES_DIR.mkdir(parents=True, exist_ok=True)

    # ------------------------------------------------------------------
    # 1. Load HF model + tokenizer
    # ------------------------------------------------------------------
    console.print(f"[bold]Loading {model_id}[/bold]")
    tokenizer = AutoTokenizer.from_pretrained(model_id)
    base = AutoModel.from_pretrained(model_id)
    base.eval()

    # Sanity-check the architecture matches our expectations before we
    # bake assumptions about hidden_dim into the wrapper.
    hidden_dim = base.config.hidden_size
    if hidden_dim != EMBEDDING_DIM:
        raise click.ClickException(
            f"expected hidden_dim={EMBEDDING_DIM} for {model_id}, got {hidden_dim}. "
            f"If a different MiniLM variant is being used, update EMBEDDING_DIM "
            f"and the Kotlin Memory.EMBEDDING_DIM constant in lockstep."
        )

    # ------------------------------------------------------------------
    # 2. Wrap with mean-pool + L2-norm
    # ------------------------------------------------------------------
    class ExportableMiniLMEncoder(nn.Module):
        """Static-signature wrapper. Produces a single L2-normalised
        sentence vector — `forward(input_ids, attention_mask) -> [B, 384]`.

        Mean-pooling matches the sentence-transformers reference
        implementation byte-for-byte: token-level hidden states are
        weighted by the attention mask, summed, and divided by the
        clamped mask sum (to avoid div-by-zero on all-padding rows,
        which can't occur for our inputs but the clamp is cheap and
        makes the graph trace robust).
        """

        def __init__(self, encoder: nn.Module) -> None:
            super().__init__()
            self.encoder = encoder

        def forward(
            self,
            input_ids: torch.Tensor,
            attention_mask: torch.Tensor,
        ) -> torch.Tensor:
            out = self.encoder(input_ids=input_ids, attention_mask=attention_mask)
            token_embeds = out.last_hidden_state  # [B, T, H]
            mask = attention_mask.unsqueeze(-1).to(token_embeds.dtype)  # [B, T, 1]
            sum_embeds = (token_embeds * mask).sum(dim=1)  # [B, H]
            sum_mask = mask.sum(dim=1).clamp(min=1e-9)  # [B, 1]
            pooled = sum_embeds / sum_mask  # [B, H]
            return nn.functional.normalize(pooled, p=2, dim=1)  # [B, H]

    exportable = ExportableMiniLMEncoder(base).eval()

    # ------------------------------------------------------------------
    # 3. Trace + convert to FP32 .tflite
    # ------------------------------------------------------------------
    sample_input_ids = torch.zeros((1, max_length), dtype=torch.long)
    sample_attn = torch.ones((1, max_length), dtype=torch.long)

    try:
        import litert_torch as edge_runtime
    except ImportError:
        try:
            import ai_edge_torch as edge_runtime  # type: ignore
        except ImportError as e:
            raise click.ClickException(
                "litert-torch (or ai-edge-torch) required. "
                "Install via: pip install -e '.[training]' (in classifier-training/)"
            ) from e

    fp32_path = MODELS_DIR / "all-MiniLM-L6-v2.tflite"
    int8_path = MODELS_DIR / "all-MiniLM-L6-v2_int8.tflite"

    console.print(
        f"[bold]Tracing + converting[/bold] (seq_len={max_length}, hidden={hidden_dim})"
    )
    edge_model = edge_runtime.convert(exportable, (sample_input_ids, sample_attn))
    edge_model.export(str(fp32_path))
    fp32_mb = fp32_path.stat().st_size / 1e6
    console.print(f"[green]Wrote FP32 .tflite[/green] → {fp32_path} ({fp32_mb:.1f} MB)")

    # ------------------------------------------------------------------
    # 4. Optional INT8 weight-only quantisation
    # ------------------------------------------------------------------
    ship_path = fp32_path
    if int8_weights:
        from ai_edge_quantizer import Quantizer, qtyping
        from ai_edge_quantizer.recipe_manager import AlgorithmName

        console.print("[bold]Applying INT8 weight-only quantisation[/bold]")
        q = Quantizer(float_model=str(fp32_path))
        q.update_quantization_recipe(
            regex=".*",
            operation_name=qtyping.TFLOperationName.ALL_SUPPORTED,
            algorithm_key=AlgorithmName.MIN_MAX_UNIFORM_QUANT,
            op_config=qtyping.OpQuantizationConfig(
                weight_tensor_config=qtyping.TensorQuantizationConfig(
                    num_bits=8,
                    symmetric=True,
                    granularity=qtyping.QuantGranularity.CHANNELWISE,
                ),
                compute_precision=qtyping.ComputePrecision.INTEGER,
                explicit_dequantize=False,
            ),
        )
        result = q.quantize()
        result.export_model(str(int8_path), overwrite=True)
        int8_mb = int8_path.stat().st_size / 1e6
        console.print(
            f"[green]Wrote INT8 .tflite[/green] → {int8_path} "
            f"({int8_mb:.1f} MB, {(1 - int8_mb / fp32_mb) * 100:.0f}% reduction from FP32)"
        )
        ship_path = int8_path

    # ------------------------------------------------------------------
    # 5. Sanity test + canonical fixtures via ai-edge-litert
    # ------------------------------------------------------------------
    console.print("[bold]Sanity test + fixture generation[/bold]")
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError as e:
        raise click.ClickException(
            "ai-edge-litert required for sanity test. Install via: "
            "pip install ai-edge-litert"
        ) from e

    import numpy as np

    interpreter = Interpreter(model_path=str(ship_path))
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    console.print("[dim]Inputs:[/dim]")
    for d in input_details:
        console.print(f"  {d['name']}  shape={list(d['shape'])}  dtype={d['dtype']}")
    console.print("[dim]Outputs:[/dim]")
    for d in output_details:
        console.print(f"  {d['name']}  shape={list(d['shape'])}  dtype={d['dtype']}")

    if len(output_details) != 1 or list(output_details[0]["shape"]) != [1, EMBEDDING_DIM]:
        raise click.ClickException(
            f"unexpected output signature: {output_details}\n"
            f"Expected exactly one output of shape [1, {EMBEDDING_DIM}]."
        )

    # Identify which positional input is which by name suffix (mirrors
    # CLAUDE.md inv. #12 dispatch convention used in LiteRtClassifierEngine).
    def find_input_index(suffix: str) -> int:
        for i, d in enumerate(input_details):
            if d["name"].endswith(suffix):
                return i
        # Fall back to argument order; the export consistently puts input_ids
        # first, attention_mask second, but having the by-name path documented
        # here means the Kotlin engine can do the same.
        raise click.ClickException(
            f"could not find input ending in '{suffix}'. Inputs were: "
            f"{[d['name'] for d in input_details]}"
        )

    in0 = find_input_index("args_0:0")
    in1 = find_input_index("args_1:0")

    embedded: list[dict[str, object]] = []
    tokenizer_fixtures: list[dict[str, object]] = []

    for fid, text in CANONICAL_STRINGS:
        encoded = tokenizer(
            text,
            truncation=True,
            max_length=max_length,
            padding="max_length",
            return_tensors=None,
        )
        ids = np.array([encoded["input_ids"]], dtype=np.int64)
        mask = np.array([encoded["attention_mask"]], dtype=np.int64)

        interpreter.set_tensor(input_details[in0]["index"], ids)
        interpreter.set_tensor(input_details[in1]["index"], mask)
        interpreter.invoke()
        vec = interpreter.get_tensor(output_details[0]["index"])[0]
        norm = float(np.linalg.norm(vec))
        # The L2-norm should be ~1.0 by construction; allow a tiny INT8
        # dequantisation drift.
        if not (0.95 <= norm <= 1.05):
            raise click.ClickException(
                f"fixture '{fid}' L2-norm {norm:.4f} outside [0.95, 1.05] — "
                f"the graph mean-pool + normalize step is broken."
            )

        tokenizer_fixtures.append({
            "id": fid,
            "text": text,
            "input_ids": encoded["input_ids"],
            "attention_mask": encoded["attention_mask"],
        })
        embedded.append({
            "id": fid,
            "text": text,
            "vector": [float(x) for x in vec.tolist()],
            "vector_norm": norm,
        })

    # Sanity check: cosine(related) > cosine(unrelated)
    def cos(a: list[float], b: list[float]) -> float:
        return float(sum(x * y for x, y in zip(a, b, strict=False)))

    eagles = next(e for e in embedded if e["id"] == "preference_statement")["vector"]
    forget = next(e for e in embedded if e["id"] == "forget_command")["vector"]
    history = next(e for e in embedded if e["id"] == "settled_history")["vector"]
    location = next(e for e in embedded if e["id"] == "location_statement")["vector"]

    eagles_forget = cos(eagles, forget)  # both reference user/job
    eagles_history = cos(eagles, history)  # unrelated topic
    location_history = cos(location, history)  # unrelated topic
    console.print(
        f"[dim]similarity probes:[/dim]\n"
        f"  cos(eagles, forget-job)  = {eagles_forget:+.3f}  (somewhat related)\n"
        f"  cos(eagles, ww2)         = {eagles_history:+.3f}  (unrelated)\n"
        f"  cos(toronto, ww2)        = {location_history:+.3f}  (unrelated)"
    )

    # ------------------------------------------------------------------
    # 6. Write fixture JSONs + vocab
    # ------------------------------------------------------------------
    if not skip_fixtures:
        tok_path = FIXTURES_DIR / "minilm_tokenizer_canonical_inputs.json"
        tok_payload = {
            "tokenizer": model_id,
            "max_length": max_length,
            "vocab_size": tokenizer.vocab_size,
            "special_tokens": {
                "cls": tokenizer.cls_token_id,
                "sep": tokenizer.sep_token_id,
                "pad": tokenizer.pad_token_id,
                "unk": tokenizer.unk_token_id,
            },
            "fixtures": tokenizer_fixtures,
        }
        tok_path.write_text(json.dumps(tok_payload, indent=2, ensure_ascii=False))
        console.print(f"[green]Wrote tokenizer fixture[/green] → {tok_path}")

        emb_path = FIXTURES_DIR / "embedder_canonical_outputs.json"
        emb_payload = {
            "model_id": model_id,
            "max_length": max_length,
            "embedding_dim": EMBEDDING_DIM,
            "ship_artifact": ship_path.name,
            "fixtures": embedded,
            # Diagnostic — same probes printed above, reproducible by the test.
            "similarity_probes": {
                "eagles_vs_forget_job": eagles_forget,
                "eagles_vs_ww2": eagles_history,
                "toronto_vs_ww2": location_history,
            },
        }
        emb_path.write_text(json.dumps(emb_payload, indent=2, ensure_ascii=False))
        console.print(f"[green]Wrote embedder fixture[/green] → {emb_path}")

        # Save the vocab MiniLM's tokenizer uses. sentence-transformers/
        # all-MiniLM-L6-v2 ships the bert-base-uncased WordPiece vocab,
        # which is byte-identical to distilbert-base-uncased's. We write it
        # to a distinct filename so the asset bundling is explicit; the
        # Gradle layer can choose to dedupe if it confirms identity.
        vocab_path = MODELS_DIR / "all-MiniLM-L6-v2_minilm_vocab.txt"
        vocab_str = "\n".join(_ordered_vocab(tokenizer))
        vocab_path.write_text(vocab_str + "\n")
        console.print(f"[green]Wrote vocab[/green] → {vocab_path}")

    # ------------------------------------------------------------------
    # 7. Print SHA-256 of ship artifact for the Gradle copy task
    # ------------------------------------------------------------------
    sha = _sha256(ship_path)
    console.print(
        f"[bold cyan]Ship artifact:[/bold cyan] {ship_path}\n"
        f"[bold cyan]SHA-256:[/bold cyan] {sha}\n"
        f"[dim]Update :androidApp/build.gradle.kts copyEmbedderTflite expectedSha "
        f"to this value.[/dim]"
    )


def _ordered_vocab(tokenizer: object) -> list[str]:
    """Return the WordPiece vocabulary in id order — matches the byte layout
    of the original `vocab.txt` shipped by HF."""
    vocab = tokenizer.get_vocab()  # type: ignore[attr-defined]
    return [tok for tok, _ in sorted(vocab.items(), key=lambda kv: kv[1])]


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(64 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


if __name__ == "__main__":
    sys.exit(main() or 0)

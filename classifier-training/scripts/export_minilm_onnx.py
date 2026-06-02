"""Export `sentence-transformers/all-MiniLM-L6-v2` to an ONNX artifact runnable
on ONNX Runtime (Java) — the desktop embedder (`OnnxEmbedderEngine`, Phase 5,
docs/DESKTOP_PORT_PLAN.md). Desktop counterpart of `export_minilm_litert.py`:
`ai-edge-litert` is Android-only (CLAUDE.md invariant #18), so the same encoder
is re-exported to ONNX for the JVM.

Pipeline mirrors the litert script:
  1. Pull the HF model + tokenizer (cached after first run).
  2. Wrap in an `ExportableMiniLMEncoder` that bakes mean-pooling +
     L2-normalisation into the graph — the on-device caller gets a single
     384-dim vector and cosine similarity is a plain dot product. (Identical
     wrapper to the litert script, byte-for-byte semantics.)
  3. `torch.onnx.export` to FP32 — static [1, 128] int64 inputs (invariant #15),
     named `input_ids` / `attention_mask`, single output `sentence_embedding`.
     `OnnxEmbedderEngine` reads the sole output generically, so the name is for
     readability only.
  4. Optional `--int8`: ONNX Runtime weight-only dynamic quantization (the ONNX
     parallel to the classifier/embedder ai-edge-quantizer INT8 path).
  5. Sanity invocation via Python ONNX Runtime + a canonical OUTPUT fixture
     (`embedder_onnx_canonical_outputs.json`) the Kotlin numeric-parity test
     asserts the engine reproduces (Kotlin-ORT vs Python-ORT on the same graph).

The 10 canonical strings are IDENTICAL to `export_minilm_litert.py`, so the
optional `--compare-litert` cross-check (and the operator's eye) can compare the
ONNX vectors against the shipped `.tflite` fixture
(`embedder_canonical_outputs.json`) string-for-string for re-export fidelity.

Output files (paths relative to repo root):
  models/all-MiniLM-L6-v2.onnx                                   (FP32)
  models/all-MiniLM-L6-v2_int8.onnx                              (INT8, with --int8)
  classifier-training/tests/fixtures/embedder_onnx_canonical_outputs.json

Usage:
    classifier-training/.venv/bin/python \\
        classifier-training/scripts/export_minilm_onnx.py --int8

Requires the `[training]` extra (torch, transformers, onnx, onnxruntime).
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
# torch 2.x's exporter builds the graph at opset >= 18; requesting 17 triggers a
# fragile (and observed-failing) down-conversion via the ONNX C API. Target 18
# directly — ONNX Runtime 1.20 (the desktop runtime) supports it.
OPSET = 18

INPUT_NAMES = ["input_ids", "attention_mask"]
OUTPUT_NAME = "sentence_embedding"

# IDENTICAL set + ids to export_minilm_litert.py so the ONNX fixture lines up
# string-for-string with embedder_canonical_outputs.json for fidelity compares.
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
    help="Static sequence length baked into the exported graph (invariant #15).",
)
@click.option(
    "--int8",
    "int8_weights",
    is_flag=True,
    default=False,
    help="Apply ONNX Runtime weight-only dynamic INT8 quantization post-export.",
)
@click.option(
    "--compare-litert",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=None,
    help="Optional: cross-check ONNX vectors against this .tflite via ai-edge-litert.",
)
@click.option(
    "--skip-fixtures",
    is_flag=True,
    default=False,
    help="Skip writing the canonical fixture JSON (re-export-only runs).",
)
def main(
    model_id: str,
    max_length: int,
    int8_weights: bool,
    compare_litert: Path | None,
    skip_fixtures: bool,
) -> None:
    """Export MiniLM to ONNX and emit the Kotlin-side parity fixture."""
    import numpy as np
    import torch
    from rich.console import Console
    from torch import nn
    from transformers import AutoModel, AutoTokenizer

    console = Console()
    MODELS_DIR.mkdir(parents=True, exist_ok=True)

    console.print(f"[bold]Loading {model_id}[/bold]")
    tokenizer = AutoTokenizer.from_pretrained(model_id)
    base = AutoModel.from_pretrained(model_id)
    base.eval()

    hidden_dim = base.config.hidden_size
    if hidden_dim != EMBEDDING_DIM:
        raise click.ClickException(
            f"expected hidden_dim={EMBEDDING_DIM} for {model_id}, got {hidden_dim}. "
            f"Update EMBEDDING_DIM and the Kotlin Memory.EMBEDDING_DIM in lockstep."
        )

    class ExportableMiniLMEncoder(nn.Module):
        """Static-signature wrapper — `forward(input_ids, attention_mask) -> [B, 384]`,
        mean-pooled (attention-weighted) + L2-normalised. Matches the
        sentence-transformers reference and the litert export byte-for-byte."""

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
            pooled = sum_embeds / sum_mask
            return nn.functional.normalize(pooled, p=2, dim=1)

    exportable = ExportableMiniLMEncoder(base).eval()

    sample_input_ids = torch.zeros((1, max_length), dtype=torch.long)
    sample_attn = torch.ones((1, max_length), dtype=torch.long)

    fp32_path = MODELS_DIR / "all-MiniLM-L6-v2.onnx"
    int8_path = MODELS_DIR / "all-MiniLM-L6-v2_int8.onnx"

    console.print(
        f"[bold]Exporting to ONNX[/bold] (seq_len={max_length}, hidden={hidden_dim}, opset={OPSET})"
    )
    torch.onnx.export(
        exportable,
        (sample_input_ids, sample_attn),
        str(fp32_path),
        input_names=INPUT_NAMES,
        output_names=[OUTPUT_NAME],
        opset_version=OPSET,
        do_constant_folding=True,
        dynamic_axes=None,  # static [1, max_length]
    )
    # Inline weights into a single self-contained .onnx (torch externalises them
    # to a sidecar) — DesktopAuxModels resolves one file per model.
    _consolidate_single_file(fp32_path)
    fp32_mb = fp32_path.stat().st_size / 1e6
    console.print(f"[green]Wrote FP32 .onnx[/green] → {fp32_path} ({fp32_mb:.1f} MB)")

    ship_path = fp32_path
    if int8_weights:
        from onnxruntime.quantization import QuantType, quantize_dynamic

        console.print("[bold]Applying ONNX Runtime weight-only INT8 quantization...[/bold]")
        quantize_dynamic(
            model_input=str(fp32_path),
            model_output=str(int8_path),
            weight_type=QuantType.QInt8,
        )
        _consolidate_single_file(int8_path)
        int8_mb = int8_path.stat().st_size / 1e6
        console.print(
            f"[green]Wrote INT8 .onnx[/green] → {int8_path} "
            f"({int8_mb:.1f} MB, {(1 - int8_mb / fp32_mb) * 100:.0f}% reduction from FP32)"
        )
        ship_path = int8_path

    # ------------------------------------------------------------------
    # Sanity + canonical fixtures via ONNX Runtime
    # ------------------------------------------------------------------
    import onnxruntime as ort

    console.print("[bold]Sanity test + fixture generation via ONNX Runtime...[/bold]")
    sess = ort.InferenceSession(str(ship_path), providers=["CPUExecutionProvider"])
    out_meta = sess.get_outputs()
    console.print(f"[dim]Inputs:[/dim]  {[(i.name, i.shape) for i in sess.get_inputs()]}")
    console.print(f"[dim]Outputs:[/dim] {[(o.name, o.shape) for o in out_meta]}")
    if len(out_meta) != 1:
        raise click.ClickException(
            f"embedder must have exactly 1 output, got {[o.name for o in out_meta]}."
        )
    onnx_output_name = out_meta[0].name

    def run_onnx(text: str) -> np.ndarray:
        enc = tokenizer(
            text,
            truncation=True,
            max_length=max_length,
            padding="max_length",
            return_tensors=None,
        )
        ids = np.array([enc["input_ids"]], dtype=np.int64)
        mask = np.array([enc["attention_mask"]], dtype=np.int64)
        return sess.run([onnx_output_name], {"input_ids": ids, "attention_mask": mask})[0][0]

    embedded: list[dict[str, object]] = []
    for fid, text in CANONICAL_STRINGS:
        vec = run_onnx(text)
        if vec.shape[0] != EMBEDDING_DIM:
            raise click.ClickException(
                f"fixture '{fid}' dim {vec.shape[0]} != {EMBEDDING_DIM}."
            )
        norm = float(np.linalg.norm(vec))
        if not (0.95 <= norm <= 1.05):
            raise click.ClickException(
                f"fixture '{fid}' L2-norm {norm:.4f} outside [0.95, 1.05] — "
                f"mean-pool + normalize step is broken."
            )
        embedded.append({
            "id": fid,
            "text": text,
            "vector": [float(x) for x in vec.tolist()],
            "vector_norm": norm,
        })

    def cos(a: list[float], b: list[float]) -> float:
        return float(sum(x * y for x, y in zip(a, b, strict=False)))

    eagles = next(e for e in embedded if e["id"] == "preference_statement")["vector"]
    history = next(e for e in embedded if e["id"] == "settled_history")["vector"]
    location = next(e for e in embedded if e["id"] == "location_statement")["vector"]
    console.print(
        f"[dim]similarity probes:[/dim]\n"
        f"  cos(eagles, ww2)  = {cos(eagles, history):+.3f}  (unrelated)\n"  # type: ignore[arg-type]
        f"  cos(toronto, ww2) = {cos(location, history):+.3f}  (unrelated)"  # type: ignore[arg-type]
    )

    if compare_litert is not None:
        _compare_against_tflite(console, compare_litert, tokenizer, max_length, embedded)

    if not skip_fixtures:
        FIXTURES_DIR.mkdir(parents=True, exist_ok=True)
        emb_path = FIXTURES_DIR / "embedder_onnx_canonical_outputs.json"
        emb_path.write_text(json.dumps({
            "model_id": model_id,
            "max_length": max_length,
            "embedding_dim": EMBEDDING_DIM,
            "opset": OPSET,
            "ship_artifact": ship_path.name,
            "int8": int8_weights,
            "output_name": onnx_output_name,
            "fixtures": embedded,
        }, indent=2, ensure_ascii=False))
        console.print(f"[green]Wrote embedder fixture[/green] → {emb_path}")

    sha = _sha256(ship_path)
    console.print(
        f"[bold cyan]Ship artifact:[/bold cyan] {ship_path}\n"
        f"[bold cyan]SHA-256:[/bold cyan] {sha}"
    )
    console.print("[bold green]Export complete.[/bold green]")


def _compare_against_tflite(
    console: object,
    tflite_path: Path,
    tokenizer: object,
    max_length: int,
    onnx_fixtures: list[dict[str, object]],
) -> None:
    """Cross-check ONNX vectors vs the shipped .tflite (re-export fidelity).
    Skips gracefully if ai-edge-litert / the .tflite isn't present."""
    import numpy as np

    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:
        console.print("[yellow]Skipping .tflite compare — ai-edge-litert not installed.[/yellow]")  # type: ignore[attr-defined]
        return

    interp = Interpreter(model_path=str(tflite_path))
    interp.allocate_tensors()
    in_details = interp.get_input_details()
    out_details = interp.get_output_details()

    def in_idx(suffix: str) -> int:
        for d in in_details:
            if d["name"].endswith(suffix):
                return d["index"]
        raise click.ClickException(f"tflite input ending '{suffix}' not found")

    ids_idx, mask_idx = in_idx("args_0:0"), in_idx("args_1:0")

    min_cos = 1.0
    for fx in onnx_fixtures:
        enc = tokenizer(  # type: ignore[operator]
            fx["text"],
            truncation=True,
            max_length=max_length,
            padding="max_length",
            return_tensors=None,
        )
        interp.set_tensor(ids_idx, np.array([enc["input_ids"]], dtype=np.int64))
        interp.set_tensor(mask_idx, np.array([enc["attention_mask"]], dtype=np.int64))
        interp.invoke()
        tflite_vec = interp.get_tensor(out_details[0]["index"])[0]
        onnx_vec = np.array(fx["vector"], dtype=np.float32)
        c = float(np.dot(onnx_vec, tflite_vec))  # both L2-normalised
        min_cos = min(min_cos, c)

    console.print(  # type: ignore[attr-defined]
        f"[bold]ONNX vs .tflite min cosine:[/bold] {min_cos:.4f}  "
        f"[dim](≈1.0 ⇒ faithful re-export; INT8 drift expected)[/dim]"
    )


def _consolidate_single_file(path: Path) -> None:
    """Re-save an exported `.onnx` as a single self-contained file.

    torch 2.x's exporter writes large initializers to an external-data sidecar
    (`<name>.onnx.data`), leaving the `.onnx` as just the graph (a few hundred
    KB). The desktop engines load the model BY FILE PATH, so ORT would resolve
    the sidecar relative to the `.onnx` dir — but only if the operator ships it
    alongside. Inline the weights into one file (these models are well under the
    2 GB protobuf limit) so dropping the single `.onnx` in the app-data `models/`
    dir is sufficient, then delete the now-orphaned sidecar(s). No-op when the
    export was already self-contained.
    """
    import onnx
    from onnx.external_data_helper import ExternalDataInfo, uses_external_data

    proto = onnx.load(str(path), load_external_data=False)
    sidecars = {
        ExternalDataInfo(t).location
        for t in proto.graph.initializer
        if uses_external_data(t)
    }
    if not sidecars:
        return
    # Default load pulls the external bytes into raw_data and clears the external
    # flags; re-saving with save_as_external_data=False embeds everything.
    model = onnx.load(str(path))
    onnx.save_model(model, str(path), save_as_external_data=False)
    for loc in sidecars:
        (path.parent / loc).unlink(missing_ok=True)


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(64 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


if __name__ == "__main__":
    sys.exit(main() or 0)

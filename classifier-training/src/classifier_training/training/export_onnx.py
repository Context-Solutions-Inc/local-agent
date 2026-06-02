"""PyTorch → ONNX export — `ct-export-onnx`.

Desktop counterpart of `ct-export-litert` (`export_litert.py`). The Android app
runs the shared DistilBERT encoder + 3 task heads as a `.tflite` on
`ai-edge-litert`, which is **Android-only** (CLAUDE.md invariant #18). The
desktop port (docs/DESKTOP_PORT_PLAN.md, Phase 5) runs the *same* trained
checkpoint re-exported to ONNX via ONNX Runtime (Java) — `OnnxClassifierEngine`.

The exported graph keeps the SAME three named outputs as the Kotlin engine
resolves **by name** (invariant #12 — `OnnxClassifierEngine` calls
`OrtSession.Result.get(name)`, never positional):
  - preflight_logits      [1, 3]
  - presence_logits       [1, 2]
  - category_logits       [1, 6]
and the two named inputs `input_ids` / `attention_mask`, int64, statically
shaped [1, 128] (invariant #15 — sequence length is baked at export, matching
the shared `WordPieceTokenizer.MAX_SEQUENCE_LENGTH`).

We export FP32 by default (desktop has the RAM/CPU headroom and FP32 is the
most faithful to the trained model); `--int8` applies ONNX Runtime weight-only
dynamic quantization — the ONNX parallel to the Android `ai-edge-quantizer`
weight-only INT8 path. The Kotlin engine reads whichever `.onnx` sits at the
model path, so the choice is the operator's.

Alongside the model the run writes a canonical OUTPUT fixture
(`classifier_onnx_canonical_outputs.json`) — per-string logits the desktop
numeric-parity test asserts the `OnnxClassifierEngine` reproduces (Kotlin-ORT
vs Python-ORT on the same graph). The Android `.tflite` has no output fixture
today (only tokenizer inputs in `tokenizer_canonical_inputs.json`); pass
`--compare-tflite PATH` to additionally cross-check the ONNX re-export against
the shipped `.tflite` via `ai-edge-litert` (skipped gracefully if unavailable).

Usage:
  ct-export-onnx \\
      --ckpt eval/runs/phaseF_full_<ts>/best.pt \\
      --output models/preflight_memory_shared_v1.0.0.onnx
  ct-export-onnx ... --int8                    # weight-only INT8 ship artifact
  ct-export-onnx ... --compare-tflite models/preflight_memory_shared_v1.0.0_int8.tflite
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import click

# repo_root/classifier-training/src/classifier_training/training/export_onnx.py
#   parents[0]=training [1]=classifier_training [2]=src [3]=classifier-training [4]=repo_root
_THIS = Path(__file__).resolve()
FIXTURES_DIR = _THIS.parents[3] / "tests" / "fixtures"

DEFAULT_MAX_LENGTH = 128
# torch 2.x's exporter builds the graph at opset >= 18; requesting 17 triggers a
# fragile (and observed-failing) down-conversion via the ONNX C API. Target 18
# directly — ONNX Runtime 1.20 (the desktop runtime) supports it.
OPSET = 18

INPUT_NAMES = ["input_ids", "attention_mask"]
# MUST match OnnxClassifierEngine.{PREFLIGHT,PRESENCE,CATEGORY}_OUTPUT — the
# engine dispatches each head by these exact strings (invariant #12).
OUTPUT_NAMES = ["preflight_logits", "presence_logits", "category_logits"]

# Curated classification-meaningful probes (NOT the tokenizer stress strings in
# tokenizer_canonical_inputs.json). Doubles as a behavioural smoke test: the
# search_* should fire preflight, the memory_* should fire presence/category.
CANONICAL_STRINGS: list[tuple[str, str]] = [
    ("search_weather", "what's the weather in toronto right now"),
    ("search_sports", "who won the super bowl last year"),
    ("search_finance", "what is nvidia's stock price today"),
    ("no_search_greeting", "hello how are you doing today"),
    ("no_search_chitchat", "tell me a joke about cats"),
    ("ambiguous_general", "what is the capital of france"),
    ("memory_identity", "i'm a software engineer working on mobile apps"),
    ("memory_preference", "my favorite nfl team is the philadelphia eagles"),
    ("memory_relationship", "i have a dog named rex"),
    ("memory_none", "what time is it right now"),
]


@click.command()
@click.option(
    "--ckpt",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    required=True,
    help="FP32 PyTorch checkpoint (best.pt from ct-train-classifier).",
)
@click.option(
    "--output",
    type=click.Path(dir_okay=False, path_type=Path),
    required=True,
    help="Output .onnx path. Convention: models/preflight_memory_shared_v1.0.0.onnx",
)
@click.option("--base-model", default="distilbert-base-uncased")
@click.option(
    "--max-length",
    default=DEFAULT_MAX_LENGTH,
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
    "--compare-tflite",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=None,
    help="Optional: cross-check the ONNX re-export against this .tflite via ai-edge-litert.",
)
@click.option(
    "--skip-fixtures",
    is_flag=True,
    default=False,
    help="Skip writing the canonical output fixture (re-export-only runs).",
)
def main(
    ckpt: Path,
    output: Path,
    base_model: str,
    max_length: int,
    int8_weights: bool,
    compare_tflite: Path | None,
    skip_fixtures: bool,
) -> None:
    """Export a checkpoint to a single ONNX graph with all three heads."""
    import numpy as np
    import torch
    from rich.console import Console
    from transformers import AutoTokenizer

    from .model import ModelConfig, SharedEncoderTwoHeads

    console = Console()
    output.parent.mkdir(parents=True, exist_ok=True)

    console.print(f"[bold]Loading FP32 checkpoint[/bold] {ckpt}")
    model = SharedEncoderTwoHeads(ModelConfig(base_model_name=base_model))
    model.load_state_dict(torch.load(ckpt, map_location="cpu", weights_only=True))
    model.eval()
    exportable = model.export_wrapper().eval()

    # ai-edge-torch / litert traces graphs; torch.onnx.export is the canonical
    # ONNX path and gives us explicit control over the input/output names the
    # Kotlin engine dispatches on. Fully static [1, max_length] int64 inputs —
    # the desktop engine always feeds exactly one [1, 128] row.
    sample_input_ids = torch.zeros((1, max_length), dtype=torch.long)
    sample_attn = torch.ones((1, max_length), dtype=torch.long)

    fp32_path = output
    console.print(
        f"[bold]Exporting to ONNX[/bold] (seq_len={max_length}, opset={OPSET}, "
        f"outputs={OUTPUT_NAMES})"
    )
    torch.onnx.export(
        exportable,
        (sample_input_ids, sample_attn),
        str(fp32_path),
        input_names=INPUT_NAMES,
        output_names=OUTPUT_NAMES,
        opset_version=OPSET,
        do_constant_folding=True,
        # Static batch + seq: omit dynamic_axes so the graph is locked to
        # [1, max_length] — exactly what OnnxClassifierEngine feeds.
        dynamic_axes=None,
    )
    # Inline weights into a single self-contained .onnx (torch externalises them
    # to a sidecar) — DesktopAuxModels resolves one file per model.
    _consolidate_single_file(fp32_path)
    fp32_mb = fp32_path.stat().st_size / 1e6
    console.print(f"[green]Wrote FP32 .onnx[/green] → {fp32_path} ({fp32_mb:.1f} MB)")

    ship_path = fp32_path
    if int8_weights:
        from onnxruntime.quantization import QuantType, quantize_dynamic

        int8_path = fp32_path.with_name(fp32_path.stem + "_int8" + fp32_path.suffix)
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
    # Sanity + canonical fixtures via ONNX Runtime (the same runtime the
    # Kotlin engine uses, just the Python binding).
    # ------------------------------------------------------------------
    import onnxruntime as ort

    console.print("[bold]Sanity test + fixture generation via ONNX Runtime...[/bold]")
    sess = ort.InferenceSession(str(ship_path), providers=["CPUExecutionProvider"])
    in_names = {i.name for i in sess.get_inputs()}
    out_names = {o.name for o in sess.get_outputs()}
    console.print(f"[dim]Inputs:[/dim]  {[(i.name, i.shape, i.type) for i in sess.get_inputs()]}")
    console.print(f"[dim]Outputs:[/dim] {[(o.name, o.shape, o.type) for o in sess.get_outputs()]}")

    missing_in = set(INPUT_NAMES) - in_names
    missing_out = set(OUTPUT_NAMES) - out_names
    if missing_in or missing_out:
        raise click.ClickException(
            f"signature mismatch — missing inputs {missing_in}, missing outputs {missing_out}. "
            f"OnnxClassifierEngine resolves heads by these exact names (invariant #12)."
        )

    tokenizer = AutoTokenizer.from_pretrained(base_model)

    def run_onnx(text: str) -> dict[str, list[float]]:
        enc = tokenizer(
            text,
            truncation=True,
            max_length=max_length,
            padding="max_length",
            return_tensors=None,
        )
        ids = np.array([enc["input_ids"]], dtype=np.int64)
        mask = np.array([enc["attention_mask"]], dtype=np.int64)
        outs = sess.run(OUTPUT_NAMES, {"input_ids": ids, "attention_mask": mask})
        named = dict(zip(OUTPUT_NAMES, outs, strict=True))
        return {name: [float(x) for x in named[name][0].tolist()] for name in OUTPUT_NAMES}

    def softmax(xs: list[float]) -> list[float]:
        a = np.array(xs, dtype=np.float64)
        e = np.exp(a - a.max())
        return [float(x) for x in (e / e.sum())]

    def sigmoid(xs: list[float]) -> list[float]:
        return [float(x) for x in (1.0 / (1.0 + np.exp(-np.array(xs, dtype=np.float64))))]

    fixtures: list[dict[str, object]] = []
    for fid, text in CANONICAL_STRINGS:
        logits = run_onnx(text)
        if len(logits["preflight_logits"]) != 3 or len(logits["presence_logits"]) != 2 or len(
            logits["category_logits"]
        ) != 6:
            raise click.ClickException(
                f"fixture '{fid}' head sizes wrong: "
                f"{[(k, len(v)) for k, v in logits.items()]} (expected 3/2/6)."
            )
        fixtures.append({
            "id": fid,
            "text": text,
            "preflight_logits": logits["preflight_logits"],
            "presence_logits": logits["presence_logits"],
            "category_logits": logits["category_logits"],
            # Probabilities for human review — the parity test asserts on logits.
            "preflight_probs": softmax(logits["preflight_logits"]),
            "presence_probs": softmax(logits["presence_logits"]),
            "category_probs": sigmoid(logits["category_logits"]),
        })

    # Behavioural smoke print: search_* should rank search_required highest.
    for f in fixtures:
        p = f["preflight_probs"]  # type: ignore[index]
        console.print(
            f"  [dim]{f['id']:<22}[/dim] "
            f"p(search)={p[0]:.3f} p(no)={p[1]:.3f} p(amb)={p[2]:.3f}"
        )

    if compare_tflite is not None:
        _compare_against_tflite(console, compare_tflite, tokenizer, max_length, fixtures)

    if not skip_fixtures:
        FIXTURES_DIR.mkdir(parents=True, exist_ok=True)
        fixture_path = FIXTURES_DIR / "classifier_onnx_canonical_outputs.json"
        payload = {
            "base_model": base_model,
            "max_length": max_length,
            "opset": OPSET,
            "ship_artifact": ship_path.name,
            "int8": int8_weights,
            "output_names": OUTPUT_NAMES,
            "preflight_order": [
                "search_required",
                "search_not_required",
                "ambiguous",
            ],
            "presence_order": ["no_extraction", "has_extraction"],
            "category_order": [
                "personal_identity",
                "preference",
                "professional",
                "interest",
                "relationship",
                "temporary_context",
            ],
            "fixtures": fixtures,
        }
        fixture_path.write_text(json.dumps(payload, indent=2, ensure_ascii=False))
        console.print(f"[green]Wrote classifier fixture[/green] → {fixture_path}")

    sha = _sha256(ship_path)
    console.print(
        f"[bold cyan]Ship artifact:[/bold cyan] {ship_path}\n"
        f"[bold cyan]SHA-256:[/bold cyan] {sha}\n"
        f"[dim]Fill DesktopClassifierSpec sha256/sizeBytes with these (Phase 5/6).[/dim]"
    )
    console.print("[bold green]Export complete.[/bold green]")


def _compare_against_tflite(
    console: object,
    tflite_path: Path,
    tokenizer: object,
    max_length: int,
    onnx_fixtures: list[dict[str, object]],
) -> None:
    """Cross-check the ONNX re-export against the shipped .tflite (re-export
    fidelity, invariant #18 numerics). Skips gracefully if ai-edge-litert or
    the .tflite isn't available — this is the operator's optional sanity gate."""
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

    # .tflite identifies inputs by name suffix (invariant #12); outputs by head
    # shape (each head's [3]/[2]/[6] is unique).
    def in_idx(suffix: str) -> int:
        for d in in_details:
            if d["name"].endswith(suffix):
                return d["index"]
        raise click.ClickException(f"tflite input ending '{suffix}' not found")

    ids_idx, mask_idx = in_idx("args_0:0"), in_idx("args_1:0")
    shape_to_head = {3: "preflight_logits", 2: "presence_logits", 6: "category_logits"}

    max_diff = 0.0
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
        for d in out_details:
            vec = interp.get_tensor(d["index"])[0]
            head = shape_to_head.get(len(vec))
            if head is None:
                continue
            onnx_vec = np.array(fx[head], dtype=np.float32)
            max_diff = max(max_diff, float(np.abs(onnx_vec - vec).max()))

    console.print(  # type: ignore[attr-defined]
        f"[bold]ONNX vs .tflite max abs logit diff:[/bold] {max_diff:.4f}  "
        f"[dim](INT8 quant drift expected; large values ⇒ broken re-export)[/dim]"
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
    main()

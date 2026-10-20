#!/usr/bin/env python3
"""Prepare pinned inputs and named LiteRT models for the opt-in Android benchmark.

Run with conda dl. The resulting directory is local test data, never app assets.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import re
import shutil
import tomllib

import numpy as np

from prepare_models import ROOT, CHECKPOINT_SHA256, samples, sha256


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", action="append", required=True, metavar="NAME=PATH")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/models/quantization/android-benchmark")
    parser.add_argument("--warmups", type=int, default=5)
    parser.add_argument("--iterations", type=int, default=25)
    parser.add_argument("--rounds", type=int, default=3)
    args = parser.parse_args()
    if not (1 <= args.warmups <= 100 and 5 <= args.iterations <= 1000 and 1 <= args.rounds <= 20):
        parser.error("Use 1..100 warmups, 5..1000 iterations and 1..20 rounds")

    reference_path = ROOT / "models/conversion-validation.json"
    baseline = json.loads(reference_path.read_text())
    if baseline["checkpoint_sha256"] != CHECKPOINT_SHA256:
        raise ValueError("The pinned reference belongs to a different checkpoint")
    cases = list(samples())
    expected = {"gradient", "gray"} | {f"author-{index:02d}" for index in range(22)}
    if {name for name, _ in cases} != expected or set(baseline["reference_years"]) != expected:
        raise ValueError("All 22 hash-pinned author portraits and two patterns are required; run prepare_models.py --test-portraits")

    model_entries = []
    names = set()
    args.output.mkdir(parents=True, exist_ok=True)
    for entry in args.model:
        name, separator, value = entry.partition("=")
        if not separator or not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", name) or name in names:
            parser.error("Each --model requires a unique lowercase NAME=PATH")
        source = Path(value).resolve(strict=True)
        if not source.is_file() or source.stat().st_size == 0:
            parser.error(f"Invalid model file: {source}")
        names.add(name)
        digest = sha256(source)
        destination = args.output / f"{name}.tflite"
        if not destination.exists() or sha256(destination) != digest:
            shutil.copy2(source, destination)
        model_entries.append({"name": name, "file": destination.name, "sha256": digest, "bytes": source.stat().st_size})

    inputs = args.output / "inputs"
    inputs.mkdir(exist_ok=True)
    case_entries = []
    for name, pixels in cases:
        reference = baseline["reference_years"][name]
        if pixels.shape != (1, 112, 112, 3) or not np.isfinite(pixels).all() or not math.isfinite(reference):
            raise ValueError(f"Invalid input/reference: {name}")
        path = inputs / f"{name}.f32"
        path.write_bytes(pixels.astype("<f4").tobytes(order="C"))
        case_entries.append({"name": name, "file": str(path.relative_to(args.output)), "sha256": sha256(path),
                             "reference_years": reference, "kind": "portrait" if name.startswith("author-") else "pattern"})

    versions = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())
    manifest = {
        "schema": 1, "checkpoint_sha256": CHECKPOINT_SHA256,
        "reference_file_sha256": sha256(reference_path),
        "litert_dependency_version": versions["versions"]["litert"],
        "input": {"shape": [1, 112, 112, 3], "dtype": "float32", "byte_order": "little", "range": [0, 255]},
        "models": model_entries, "cases": case_entries, "timing_case": "author-03",
        "warmups": args.warmups, "iterations": args.iterations, "rounds": args.rounds,
        "timing_scope": "Tensor write + CompiledModel.run + tensor read; excludes detection, preprocessing and file IO",
        "license": "CC-BY-NC-SA-4.0; author test portraits are local validation data only",
    }
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Prepared {len(model_entries)} models and {len(case_entries)} inputs in {args.output}")


if __name__ == "__main__":
    main()

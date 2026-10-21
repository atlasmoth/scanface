#!/usr/bin/env python3
"""Generate and measure iOS 17 Core ML compression candidates in conda dl.

No training or image calibration is used: portraits are evaluation-only inputs.
Run build/evaluate while experimenting, then benchmark on an otherwise idle host.
Artifacts stay under artifacts/models/quantization/coreml; nothing is installed.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import gc
import hashlib
import importlib.metadata
import json
import math
from pathlib import Path
import platform
import subprocess
import time
import uuid

import coremltools as ct
from coremltools.proto import Model_pb2
import numpy as np
import torch

from prepare_models import CHECKPOINT_SHA256, MobileAge, ROOT, WORK, load_author_model, samples, sha256

OUT = WORK / "quantization/coreml"
BASELINE_SPEC_CANONICAL_SHA256 = "38d413e83004a2b3e64b491bddb584ac6d381214879becd0ceefa38140784847"
VARIANTS = ("float32", "float16", "int8", "palette4", "int8-float32")
SOURCES = [
    "https://apple.github.io/coremltools/docs-guides/source/opt-palettization-overview.html",
    "https://apple.github.io/coremltools/docs-guides/source/opt-quantization-perf.html",
    "https://apple.github.io/coremltools/docs-guides/source/opt-overview.html",
]
CONFIG = {
    "float32": {"weights": "float32", "compute": "float32"},
    "float16": {"weights": "float16", "compute": "float16"},
    "int8": {"weights": "int8", "compute": "float16", "mode": "linear_symmetric", "granularity": "per_channel", "weight_threshold": 2048},
    "palette4": {"weights": "4-bit scalar palette", "compute": "float16", "mode": "kmeans", "granularity": "per_tensor", "weight_threshold": 2048, "seed": 0},
    "int8-float32": {"weights": "int8", "compute": "float32", "mode": "linear_symmetric", "granularity": "per_channel", "weight_threshold": 2048},
}


def write_json(path: Path, value: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")
    temporary.replace(path)


def model_path(variant: str) -> Path:
    return WORK / "FAHRFaceAge.mlpackage" if variant == "float32" else OUT / f"FAHRFaceAge-{variant}.mlpackage"


def files(path: Path) -> dict:
    return {str(p.relative_to(path)): {"bytes": p.stat().st_size, "sha256": sha256(p)}
            for p in sorted(path.rglob("*")) if p.is_file()}


def checked_manifest(path: Path) -> dict:
    manifest = json.loads((path / "Manifest.json").read_text())
    entries = manifest.get("itemInfoEntries", {})
    expected = {"com.apple.CoreML/model.mlmodel": ("model.mlmodel", "CoreML Model Specification"),
                "com.apple.CoreML/weights": ("weights", "CoreML Model Weights")}
    if manifest.get("fileFormatVersion") != "1.0.0" or len(entries) != 2:
        raise ValueError("Unexpected Core ML package manifest")
    if {e.get("path") for e in entries.values()} != set(expected):
        raise ValueError("Unexpected Core ML package resources")
    for entry in entries.values():
        name, description = expected[entry["path"]]
        if entry != {"author": "com.apple.CoreML", "description": description, "name": name, "path": entry["path"]}:
            raise ValueError("Unexpected Core ML resource metadata")
    if entries.get(manifest.get("rootModelIdentifier"), {}).get("path") != "com.apple.CoreML/model.mlmodel":
        raise ValueError("Core ML manifest does not identify the checked model as root")
    return manifest


def canonical_spec(path: Path) -> bytes:
    spec = Model_pb2.Model()
    spec.ParseFromString((path / "Data/com.apple.CoreML/model.mlmodel").read_bytes())
    return spec.SerializeToString(deterministic=True)


def normalize_package(path: Path, variant: str):
    """Normalize save-time UUIDs and protobuf map ordering; no graph values change."""
    manifest = checked_manifest(path)
    identities = {old: str(uuid.uuid5(uuid.NAMESPACE_URL, f"scanface:{CHECKPOINT_SHA256}:{variant}:{entry['path']}")).upper()
                  for old, entry in manifest["itemInfoEntries"].items()}
    manifest["rootModelIdentifier"] = identities[manifest["rootModelIdentifier"]]
    manifest["itemInfoEntries"] = {identities[old]: entry for old, entry in manifest["itemInfoEntries"].items()}
    (path / "Manifest.json").write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n")
    (path / "Data/com.apple.CoreML/model.mlmodel").write_bytes(canonical_spec(path))


def validate_source() -> dict:
    reference = json.loads((ROOT / "models/conversion-validation.json").read_text())
    if reference["checkpoint_sha256"] != CHECKPOINT_SHA256:
        raise ValueError("Unexpected source checkpoint in committed reference")
    checked_manifest(model_path("float32"))
    # Historical packages have random UUIDs and nondeterministic protobuf maps.
    # Keep the original package untouched, lock its canonical graph semantics and
    # exact weight bytes, and check the resource references above separately.
    actual = files(model_path("float32"))
    expected = reference["runtimes"]["coreml"]["files"]
    if (set(actual) != set(expected) or
            actual["Data/com.apple.CoreML/weights/weight.bin"] != expected["Data/com.apple.CoreML/weights/weight.bin"] or
            hashlib.sha256(canonical_spec(model_path("float32"))).hexdigest() != BASELINE_SPEC_CANONICAL_SHA256):
        raise ValueError("Float32 package differs from the committed conversion reference")
    return reference


def contract(model) -> dict:
    spec = model.get_spec()
    if spec.specificationVersion != 8:
        raise ValueError(f"Expected iOS17 specification 8, found {spec.specificationVersion}")
    inputs, outputs = spec.description.input, spec.description.output
    if len(inputs) != 1 or len(outputs) != 1:
        raise ValueError("Expected exactly one model input and output")
    for feature, name, shape in ((inputs[0], "pixels", [1, 112, 112, 3]), (outputs[0], "age", [1])):
        tensor = feature.type.multiArrayType
        if feature.name != name or list(tensor.shape) != shape or tensor.dataType != 65568:
            raise ValueError(f"Unexpected float32 tensor contract: {feature}")
    ops = Counter()
    opsets = []
    for function in spec.mlProgram.functions.values():
        opsets.append(function.opset)
        for block in function.block_specializations.values():
            for op in block.operations:
                ops[op.type] += 1
    if set(opsets) != {"CoreML7"}:
        raise ValueError(f"Unexpected iOS17 program opset: {opsets}")
    return {"specification_version": spec.specificationVersion, "opsets": sorted(set(opsets)), "operations": dict(sorted(ops.items()))}


def metadata(variant: str) -> dict:
    package = files(model_path(variant))
    model = ct.models.MLModel(str(model_path(variant)), skip_model_load=True)
    result = {"configuration": CONFIG[variant], "path": str(model_path(variant).relative_to(ROOT)),
              "bytes": sum(v["bytes"] for v in package.values()), "files": package, **contract(model)}
    expected_op = "constexpr_affine_dequantize" if variant.startswith("int8") else "constexpr_lut_to_dense" if variant.startswith("palette") else None
    if expected_op and result["operations"].get(expected_op, 0) == 0:
        raise ValueError(f"No compressed weight operations found for {variant}")
    del model
    return result


def build(variants: list[str], force: bool):
    needed = set(variants)
    if any(CONFIG[v]["compute"] == "float16" for v in needed):
        needed.add("float16")
    for variant in VARIANTS:
        if variant not in needed or variant == "float32":
            continue
        path = model_path(variant)
        lock = OUT / f"{variant}-build.json"
        if path.exists() and lock.exists() and not force:
            previous = json.loads(lock.read_text())
            if previous["configuration"] != CONFIG[variant] or previous["files"] != files(path):
                raise ValueError(f"Changed cached candidate: {variant}; use --force to rebuild")
            normalize_package(path, variant)
            write_json(lock, metadata(variant))
            print(f"Using verified cached {variant}", flush=True)
            continue
        if path.exists() and not force:
            raise ValueError(f"Candidate has no build lock: {path}; use --force to rebuild")
        print(f"Building {variant}", flush=True)
        if variant == "float16":
            torch.set_num_threads(4)
            author = load_author_model()
            wrapper = MobileAge(author).eval()
            example = torch.zeros((1, 112, 112, 3), dtype=torch.float32)
            traced = torch.jit.trace(wrapper, example)
            model = ct.convert(
                traced, convert_to="mlprogram", minimum_deployment_target=ct.target.iOS17,
                inputs=[ct.TensorType(name="pixels", shape=(1, 112, 112, 3), dtype=np.float32)],
                outputs=[ct.TensorType(name="age", dtype=np.float32)],
                compute_precision=ct.precision.FLOAT16, skip_model_load=True,
            )
            del traced, wrapper, author
        else:
            source_variant = "float16" if CONFIG[variant]["compute"] == "float16" else "float32"
            source = ct.models.MLModel(str(model_path(source_variant)), skip_model_load=True)
            if variant.startswith("int8"):
                config = ct.optimize.coreml.OptimizationConfig(global_config=ct.optimize.coreml.OpLinearQuantizerConfig(
                    mode="linear_symmetric", dtype="int8", granularity="per_channel", weight_threshold=2048,
                ))
                model = ct.optimize.coreml.linear_quantize_weights(source, config)
            else:
                np.random.seed(0)
                config = ct.optimize.coreml.OptimizationConfig(global_config=ct.optimize.coreml.OpPalettizerConfig(
                    mode="kmeans", nbits=int(variant.split("-")[0].removeprefix("palette")), granularity="per_tensor",
                    num_kmeans_workers=1, weight_threshold=2048,
                ))
                model = ct.optimize.coreml.palettize_weights(source, config)
            del source
        model.author = "Haugg, Lee et al.; AIM-Harvard. Mobile conversion: ScanFace."
        model.license = "CC BY-NC-SA 4.0; https://creativecommons.org/licenses/by-nc-sa/4.0/"
        model.short_description = "FAHR-FaceAge research facial-age estimate; aligned face input required."
        model.user_defined_metadata["source_sha256"] = CHECKPOINT_SHA256
        model.user_defined_metadata["compression"] = json.dumps(CONFIG[variant], sort_keys=True)
        contract(model)
        model.save(str(path))
        normalize_package(path, variant)
        del model
        gc.collect()
        write_json(lock, metadata(variant))
        print(f"Built {variant}: {json.loads(lock.read_text())['bytes'] / 2**20:.2f} MiB", flush=True)


def prediction(model, pixels) -> float:
    result = np.asarray(model.predict({"pixels": pixels})["age"])
    if result.shape != (1,) or result.dtype != np.float32:
        raise ValueError(f"Unexpected prediction tensor: {result.shape}, {result.dtype}")
    value = float(result[0])
    if not math.isfinite(value):
        raise ValueError("Nonfinite prediction")
    return value


def drift(predictions: dict, reference: dict, names: list[str]) -> dict:
    signed = [predictions[n] - reference[n] for n in names]
    absolute = np.abs(signed)
    return {"count": len(names), "mean_absolute_years": float(np.mean(absolute)),
            "maximum_absolute_years": float(np.max(absolute)), "p95_absolute_years": float(np.percentile(absolute, 95)),
            "mean_signed_years": float(np.mean(signed)),
            "per_case": {n: {"prediction_years": predictions[n], "reference_years": reference[n],
                              "signed_drift_years": predictions[n] - reference[n]} for n in names}}


def evaluate(variants, cases, reference, units):
    report_path = OUT / "evaluation.json"
    report = {"checkpoint_sha256": CHECKPOINT_SHA256, "reference": "committed PyTorch float32 predictions",
              "evaluation_only": True, "calibration": "none; data-free weight compression", "host": host(),
              "sources": SOURCES, "variants": {}}
    names = [name for name, _ in cases]
    for variant in variants:
        item = {**metadata(variant), "compute_units": {}}
        report["variants"][variant] = item
        for unit in units:
            print(f"Evaluating {variant}/{unit}", flush=True)
            model = ct.models.MLModel(str(model_path(variant)), compute_units=getattr(ct.ComputeUnit, unit))
            actual = {name: prediction(model, pixels) for name, pixels in cases}
            item["compute_units"][unit] = {
                "all": drift(actual, reference, names),
                "portraits": drift(actual, reference, [n for n in names if n.startswith("author-")]),
                "patterns": drift(actual, reference, [n for n in names if not n.startswith("author-")]),
            }
            del model
            gc.collect()
            write_json(report_path, report)
            print(f"{variant}/{unit}: {item['compute_units'][unit]['portraits']['mean_absolute_years']:.6f} mean / "
                  f"{item['compute_units'][unit]['portraits']['maximum_absolute_years']:.6f} max portrait drift", flush=True)


def host() -> dict:
    def command(*args):
        return subprocess.check_output(args, text=True).strip()
    return {"cpu": command("sysctl", "-n", "machdep.cpu.brand_string"), "memory_bytes": int(command("sysctl", "-n", "hw.memsize")),
            "os": command("sw_vers", "-productVersion"), "os_build": command("sw_vers", "-buildVersion"),
            "machine": platform.machine(), "python": platform.python_version(),
            "versions": {p: importlib.metadata.version(p) for p in ("coremltools", "torch", "numpy", "pillow")},
            "coreml_threads": "runtime managed; no public thread-count setting", "compute_assignment": "requested units; per-operation hardware placement is not measured"}


def benchmark(variants, cases, units, warmup, rounds):
    report = {"host": host(), "timestamp_utc": datetime.now(timezone.utc).isoformat(), "variants": {},
              "method": {"warmup_predictions": warmup, "timed_rounds": rounds, "inputs_per_round": len(cases),
                         "clock": "time.perf_counter_ns", "scope": "synchronous Python MLModel.predict including input/output bridge; excludes detection, resizing and load",
                         "ordering": "serial by compute-unit and variant; input order rotates one place per round", "phone_performance": False,
                         "load": "package load/compile, with pre-existing compiler caches; descriptive only, not cold-start benchmark"}}
    for unit in units:
        for variant in variants:
            start = time.perf_counter_ns()
            model = ct.models.MLModel(str(model_path(variant)), compute_units=getattr(ct.ComputeUnit, unit))
            load_ms = (time.perf_counter_ns() - start) / 1e6
            for index in range(warmup):
                prediction(model, cases[index % len(cases)][1])
            timings = []
            for repeat in range(rounds):
                for offset in range(len(cases)):
                    name, pixels = cases[(repeat + offset) % len(cases)]
                    start = time.perf_counter_ns()
                    value = prediction(model, pixels)
                    timings.append({"round": repeat, "input": name, "ms": (time.perf_counter_ns() - start) / 1e6,
                                    "prediction_years": value})
            durations = [item["ms"] for item in timings]
            stats = {"load_ms": load_ms, "count": len(durations), "median_ms": float(np.median(durations)),
                     "mean_ms": float(np.mean(durations)), "p90_ms": float(np.percentile(durations, 90)),
                     "p95_ms": float(np.percentile(durations, 95)), "minimum_ms": min(durations), "maximum_ms": max(durations),
                     "samples": timings}
            item = report["variants"].setdefault(variant, {**metadata(variant), "compute_units": {}})
            item["compute_units"][unit] = stats
            write_json(OUT / "benchmark.json", report)
            print(f"{variant}/{unit}: median {stats['median_ms']:.3f} ms, p95 {stats['p95_ms']:.3f} ms", flush=True)
            del model
            gc.collect()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=("build", "evaluate", "benchmark", "all"), default="all")
    parser.add_argument("--variants", nargs="+", choices=VARIANTS, default=list(VARIANTS))
    parser.add_argument("--compute-units", nargs="+", choices=("CPU_ONLY", "ALL"), default=["CPU_ONLY", "ALL"])
    parser.add_argument("--warmup", type=int, default=12)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    if args.warmup < 1 or args.rounds < 1:
        parser.error("warmup and rounds must be positive")
    OUT.mkdir(parents=True, exist_ok=True)
    reference = validate_source()["reference_years"]
    cases = list(samples())
    if len(cases) != 24 or {n for n, _ in cases} != set(reference):
        raise ValueError("All 22 hash-verified author portraits and 2 patterns are required; prepare --validation-portraits first")
    if args.phase in ("build", "all"):
        build(args.variants, args.force)
    if args.phase in ("evaluate", "all"):
        evaluate(args.variants, cases, reference, args.compute_units)
    if args.phase in ("benchmark", "all"):
        benchmark(args.variants, cases, args.compute_units, args.warmup, args.rounds)


if __name__ == "__main__":
    main()

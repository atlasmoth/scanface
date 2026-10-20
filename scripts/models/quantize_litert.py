#!/usr/bin/env python3
"""Evaluate LiteRT compression with conda dl; never changes installed app assets.

Generate and evaluate: python scripts/models/quantize_litert.py --phase generate
Time on an otherwise idle host: ... --phase benchmark
Recipes need no calibration; all 22 pinned portraits remain evaluation inputs.
"""
from __future__ import annotations

import argparse
import gc
import importlib.metadata
import json
import math
from pathlib import Path
import platform
import subprocess
import time

import numpy as np

from prepare_models import ROOT, WORK, CHECKPOINT_SHA256, samples, sha256

DESTINATION = WORK / "quantization/litert"
CANDIDATES = ("fp32", "fp16", "int8-weight", "int8-dynamic", "int4-weight", "int4-block32")
FACE_GATE = {"mean_absolute_years": 0.10, "max_absolute_years": 0.25}


def cases_and_reference():
    cases = list(samples())
    reference = json.loads((ROOT / "models/conversion-validation.json").read_text())
    expected = {"gradient", "gray"} | {f"author-{n:02}" for n in range(22)}
    if {name for name, _ in cases} != expected or set(reference["reference_years"]) != expected:
        raise ValueError("All 22 pinned author portraits and both diagnostic patterns are required")
    if reference["checkpoint_sha256"] != CHECKPOINT_SHA256:
        raise ValueError("Reference checkpoint identity changed")
    source = WORK / "fahr_faceage.tflite"
    if sha256(source) != reference["runtimes"]["litert"]["sha256"]:
        raise ValueError("Float32 baseline differs from the verified conversion")
    return cases, reference, source


def make_recipe(name):
    from ai_edge_quantizer import algorithm_manager, qtyping, recipe, recipe_manager
    if name == "fp16":
        manager = recipe_manager.RecipeManager()
        manager.add_weight_only_config(
            regex=".*", operation_name=qtyping.TFLOperationName.ALL_SUPPORTED,
            num_bits=16, algorithm_key=algorithm_manager.AlgorithmName.FLOAT_CASTING,
        )
        return manager.get_quantization_recipe()
    return {
        "int8-weight": recipe.weight_only_wi8_afp32,
        "int8-dynamic": recipe.dynamic_wi8_afp32,
        "int4-weight": recipe.weight_only_wi4_afp32,
        "int4-block32": recipe.dynamic_wi4b32_afp32,
    }[name]()


def open_model(path):
    from ai_edge_litert.interpreter import Interpreter
    interpreter = Interpreter(model_path=str(path), num_threads=2)
    interpreter.allocate_tensors()
    inputs, outputs = interpreter.get_input_details(), interpreter.get_output_details()
    if len(inputs) != 1 or len(outputs) != 1:
        raise ValueError("Expected exactly one input and output")
    if inputs[0]["shape"].tolist() != [1, 112, 112, 3] or outputs[0]["shape"].tolist() != [1]:
        raise ValueError("Model IO shapes changed")
    if inputs[0]["dtype"] != np.float32 or outputs[0]["dtype"] != np.float32:
        raise ValueError("The app requires float32 IO even with compressed weights")
    signature = interpreter.get_signature_list()
    if signature != {"serving_default": {"inputs": ["args_0"], "outputs": ["output_0"]}}:
        raise ValueError(f"Signature changed: {signature}")
    return interpreter, inputs[0]["index"], outputs[0]["index"]


def predict(runtime, pixels):
    interpreter, input_index, output_index = runtime
    interpreter.set_tensor(input_index, pixels)
    interpreter.invoke()
    output = interpreter.get_tensor(output_index)
    age = float(output[0])
    if output.shape != (1,) or not math.isfinite(age):
        raise ValueError(f"Invalid model output {output}")
    return age


def stats(differences):
    values = np.asarray(list(differences), dtype=np.float64)
    return {
        "count": len(values), "mean_signed_years": float(np.mean(values)),
        "mean_absolute_years": float(np.mean(np.abs(values))),
        "p95_absolute_years": float(np.percentile(np.abs(values), 95)),
        "max_absolute_years": float(np.max(np.abs(values))),
    }


def evaluate(path, cases, reference):
    runtime = open_model(path)
    predictions = {name: predict(runtime, pixels) for name, pixels in cases}
    del runtime
    gc.collect()
    differences = {name: age - reference[name] for name, age in predictions.items()}
    faces = stats(v for name, v in differences.items() if name.startswith("author-"))
    return {
        "predictions_years": predictions, "signed_drift_years": differences,
        "face_drift": faces,
        "pattern_drift": {name: differences[name] for name in ("gradient", "gray")},
        "all_outputs_in_app_range": all(0 <= age <= 130 for age in predictions.values()),
        "passes_face_gate": all(faces[key] <= limit for key, limit in FACE_GATE.items())
            and all(0 <= age <= 130 for age in predictions.values()),
    }


def environment():
    def hardware(key, fallback):
        if platform.system() != "Darwin":
            return fallback
        return subprocess.check_output(["sysctl", "-n", key], text=True).strip()

    return {
        "platform": platform.platform(),
        "hardware": hardware("hw.model", platform.machine()),
        "chip": hardware("machdep.cpu.brand_string", platform.processor() or platform.machine()),
        "python": platform.python_version(),
        "versions": {p: importlib.metadata.version(p) for p in ("ai-edge-quantizer", "ai-edge-litert", "numpy")},
        "runtime": "Python LiteRT Interpreter with default XNNPACK delegate; CPU, 2 threads",
        "scope": "host inference only; not Android device latency or end-to-end face processing",
    }


def benchmark(path, cases, rounds):
    start = time.perf_counter_ns()
    runtime = open_model(path)
    construction_ms = (time.perf_counter_ns() - start) / 1e6
    faces = [(name, pixels) for name, pixels in cases if name.startswith("author-")]
    start = time.perf_counter_ns()
    first_age = predict(runtime, faces[0][1])
    first_ms = (time.perf_counter_ns() - start) / 1e6
    for _, pixels in faces:
        predict(runtime, pixels)
    durations = []
    checksum = 0.
    for _ in range(rounds):
        for name, pixels in faces:
            start = time.perf_counter_ns()
            age = predict(runtime, pixels)
            elapsed = (time.perf_counter_ns() - start) / 1e6
            checksum += age
            durations.append({"case": name, "milliseconds": elapsed})
    del runtime
    gc.collect()
    values = [row["milliseconds"] for row in durations]
    return {
        "construction_ms": construction_ms, "first_prediction_ms": first_ms,
        "first_age_years": first_age, "warmup_predictions": len(faces),
        "timed_predictions": len(values), "rounds": rounds,
        "median_ms": float(np.median(values)), "p90_ms": float(np.percentile(values, 90)),
        "mean_ms": float(np.mean(values)), "prediction_checksum": checksum,
        "timing_includes": "input copy, invoke, output copy and finite check; no image preprocessing",
        "samples": durations,
    }


def write_report(path, report):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=("generate", "benchmark"), default="generate")
    parser.add_argument("--candidates", nargs="+", choices=CANDIDATES, default=list(CANDIDATES))
    parser.add_argument("--rounds", type=int, default=3)
    args = parser.parse_args()
    if args.rounds < 1:
        parser.error("--rounds must be positive")
    cases, reference, source = cases_and_reference()
    DESTINATION.mkdir(parents=True, exist_ok=True)
    report_path = DESTINATION / "evaluation.json"
    if args.phase == "generate":
        report = json.loads(report_path.read_text()) if report_path.exists() else {
            "schema_version": 1, "checkpoint_sha256": CHECKPOINT_SHA256,
            "source_sha256": sha256(source), "source_bytes": source.stat().st_size,
            "license": "CC-BY-NC-SA-4.0", "environment": environment(),
            "face_gate": FACE_GATE,
            "interpretation": "Engineering drift screening on 22 synthetic crops, not clinical validation. No calibration.",
            "candidates": {},
        }
        from ai_edge_quantizer import quantizer
        for name in args.candidates:
            print(f"Generating {name}", flush=True)
            path = source if name == "fp32" else DESTINATION / f"{name}.tflite"
            try:
                if name != "fp32":
                    recipe = make_recipe(name)
                    qt = quantizer.Quantizer(source)
                    qt.load_quantization_recipe(recipe)
                    result = qt.quantize(enable_progress_bar=False, enable_progress_report=False)
                    result.save(DESTINATION, name, overwrite=True)
                    del result, qt
                    gc.collect()
                evaluation = evaluate(path, cases, reference["reference_years"])
                report["candidates"][name] = {
                    "status": "ok", "relative_path": str(path.relative_to(ROOT)),
                    "sha256": sha256(path), "bytes": path.stat().st_size,
                    "size_reduction_percent": 100 * (1 - path.stat().st_size / source.stat().st_size),
                    **evaluation,
                }
                print(name, json.dumps({k: v for k, v in report["candidates"][name].items() if k not in ("predictions_years", "signed_drift_years")}), flush=True)
            except Exception as error:
                report["candidates"][name] = {"status": "error", "error": f"{type(error).__name__}: {error}"}
                print(f"{name}: {error}", flush=True)
            write_report(report_path, report)
    else:
        report = json.loads(report_path.read_text())
        timing = {"environment": environment(), "order": args.candidates, "candidates": {}}
        for name in args.candidates:
            candidate = report["candidates"][name]
            if candidate["status"] != "ok":
                continue
            path = ROOT / candidate["relative_path"]
            if sha256(path) != candidate["sha256"]:
                raise ValueError(f"Candidate changed: {name}")
            measured = benchmark(path, cases, args.rounds)
            timing["candidates"][name] = {"sha256": candidate["sha256"], **measured}
            print(name, {k: v for k, v in measured.items() if k != "samples"}, flush=True)
            write_report(DESTINATION / "host-timing.json", timing)


if __name__ == "__main__":
    main()

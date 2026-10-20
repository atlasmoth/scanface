#!/usr/bin/env python3
"""Install the reviewed quantized selection using ``conda run -n dl python``.

The selection lock records evaluation evidence, not clinical validation. This
installer independently checks that evidence, the artifact bytes and native I/O
contracts, then stages complete model directories before replacing either app.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import shutil
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[2]
CHECKPOINT_SHA256 = "980ad2f134c5d82e9aca39a738f88a5b10aaf15ca9a5462bf95599be023cdbba"
LICENSE = "CC-BY-NC-SA-4.0"
INPUT = {"shape": [1, 112, 112, 3], "dtype": "float32", "range": [0, 255]}
CASES = {"gradient", "gray"} | {f"author-{index:02d}" for index in range(22)}
FACE_MEAN_LIMIT = 0.10
FACE_MAX_LIMIT = 0.25
DESTINATIONS = {
    "litert": "androidApp/src/main/assets/models",
    "coreml": "iosApp/ScanFace/Models",
}
MODEL_NAMES = {"litert": "fahr_faceage.tflite", "coreml": "FAHRFaceAge.mlpackage"}
NOTICE_FILES = {"NOTICE.md", "Apache-2.0.txt", "CC-BY-NC-SA-4.0.txt", "Pillow-MIT-CMU.txt", "RetinaFace-MIT.txt"}


def read_json(path: Path) -> dict:
    return json.loads(path.read_text())


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def finite_number(value, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"Expected finite numeric {label}")
    return float(value)


def prediction_drift(predictions: dict, reference: dict, label: str) -> dict:
    if set(predictions) != CASES:
        raise ValueError(f"{label} must contain all 22 pinned faces and both patterns")
    errors = {}
    for name, value in predictions.items():
        age = finite_number(value, f"{label}/{name}")
        if not 0 <= age <= 130:
            raise ValueError(f"{label}/{name}: prediction outside app's accepted age range")
        errors[name] = abs(age - finite_number(reference[name], f"reference/{name}"))
    result = {}
    for group, values in (("faces", [value for name, value in errors.items() if name.startswith("author-")]),
                          ("all", list(errors.values()))):
        result[group] = {"mean_absolute_years": sum(values) / len(values), "max_absolute_years": max(values)}
    if result["faces"]["mean_absolute_years"] > FACE_MEAN_LIMIT or result["faces"]["max_absolute_years"] > FACE_MAX_LIMIT:
        raise ValueError(f"{label}: face drift exceeds reviewed engineering gate: {result['faces']}")
    return result


def validate_lock(lock: dict, root: Path = ROOT) -> None:
    source = read_json(root / "models/source-manifest.json")
    baseline = read_json(root / "models/conversion-validation.json")
    if (lock.get("schema_version") != 1 or
            lock.get("source_checkpoint_sha256") != CHECKPOINT_SHA256 or
            source["checkpoint"]["sha256"] != CHECKPOINT_SHA256 or
            baseline["checkpoint_sha256"] != CHECKPOINT_SHA256 or
            lock.get("license") != LICENSE or source["checkpoint"]["license"] != LICENSE or
            lock.get("input") != INPUT):
        raise ValueError("Selection has incompatible source, license or input contract")
    reference = baseline["reference_years"]
    if set(reference) != CASES or set(lock["runtimes"]) != set(DESTINATIONS):
        raise ValueError("Selection must cover both runtimes and the complete pinned reference")
    for runtime, selected in lock["runtimes"].items():
        for name in ("candidate", "model_id", "precision", "recipe", "artifact"):
            if not selected.get(name):
                raise ValueError(f"{runtime} selection is missing {name}")
        if not selected["model_id"].startswith(f"fahr-faceage-1fbb84e-{runtime}-"):
            raise ValueError(f"{runtime} model ID must identify its source and runtime")
        actual = prediction_drift(selected["predictions_years"], reference, runtime)
        for group, metrics in actual.items():
            for key, value in metrics.items():
                recorded = finite_number(selected["drift"][group][key], f"{runtime} drift {group}/{key}")
                if not math.isclose(recorded, value, rel_tol=1e-6, abs_tol=1e-9):
                    raise ValueError(f"{runtime}: recorded {group}/{key} differs from predictions")
        for backend, result in selected.get("validation_backends", {}).items():
            prediction_drift(result["predictions_years"], reference, f"{runtime}/{backend}")


def artifact_path(selected: dict, root: Path = ROOT) -> Path:
    relative = Path(selected["artifact"])
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError("Artifact must be a repository-relative generated model path")
    path = root / relative
    if path.is_symlink() or not path.exists():
        raise ValueError(f"Missing model artifact or unsupported symlink: {path}")
    path.resolve().relative_to((root / "artifacts/models").resolve())
    return path


def check_file(path: Path, expected: dict) -> None:
    if path.is_symlink() or not path.is_file() or path.stat().st_size != expected["bytes"] or sha256(path) != expected["sha256"]:
        raise ValueError(f"Artifact size/hash mismatch: {path}")


def validate_bytes(runtime: str, path: Path, selected: dict) -> None:
    if runtime == "litert":
        check_file(path, selected)
        return
    expected = selected["files"]
    if not expected or not path.is_dir():
        raise ValueError("Core ML requires a complete package file manifest")
    actual = {}
    for item in path.rglob("*"):
        if item.is_symlink():
            raise ValueError(f"Symlinks are not supported inside a model package: {item}")
        if item.is_file():
            actual[item.relative_to(path).as_posix()] = item
    if set(actual) != set(expected):
        raise ValueError("Core ML package contains missing or unrecorded files")
    for name, item in actual.items():
        check_file(item, expected[name])


def validate_runtime(runtime: str, path: Path) -> None:
    if runtime == "litert":
        import numpy as np
        from ai_edge_litert.interpreter import Interpreter
        model = Interpreter(model_path=str(path), num_threads=2)
        inputs, outputs = model.get_input_details(), model.get_output_details()
        if (len(inputs) != 1 or len(outputs) != 1 or
                inputs[0]["dtype"] != np.float32 or outputs[0]["dtype"] != np.float32 or
                inputs[0]["shape"].tolist() != INPUT["shape"] or outputs[0]["shape"].tolist() != [1] or
                model.get_signature_list() != {"serving_default": {"inputs": ["args_0"], "outputs": ["output_0"]}}):
            raise ValueError("Unexpected LiteRT float32 tensor/signature contract")
        return
    import coremltools as ct
    model = ct.models.MLModel(str(path), skip_model_load=True)
    spec = model.get_spec()
    if spec.specificationVersion != 8 or spec.WhichOneof("Type") != "mlProgram":
        raise ValueError("Expected an iOS 17 specification-8 Core ML program")
    if {function.opset for function in spec.mlProgram.functions.values()} != {"CoreML7"}:
        raise ValueError("Core ML program is not using the iOS 17-compatible opset")
    inputs, outputs = spec.description.input, spec.description.output
    if len(inputs) != 1 or len(outputs) != 1:
        raise ValueError("Core ML requires exactly one input and output")
    for feature, name, shape in ((inputs[0], "pixels", INPUT["shape"]), (outputs[0], "age", [1])):
        array = feature.type.multiArrayType
        if (feature.name != name or feature.type.WhichOneof("Type") != "multiArrayType" or
                list(array.shape) != shape or array.dataType != ct.proto.FeatureTypes_pb2.ArrayFeatureType.FLOAT32):
            raise ValueError("Unexpected Core ML float32 tensor contract")


def metadata(lock: dict, runtime: str) -> dict:
    return {"schema_version": 1, "runtime": runtime,
            "source_checkpoint_sha256": lock["source_checkpoint_sha256"], "license": lock["license"],
            "input": lock["input"], "output": {"shape": [1], "dtype": "float32", "unit": "years"},
            "engineering_gate": {"face_mean_absolute_years": FACE_MEAN_LIMIT, "face_max_absolute_years": FACE_MAX_LIMIT},
            "validation_scope": "22 synthetic author faces and 2 patterns; numerical drift, not clinical accuracy",
            **lock["runtimes"][runtime]}


def install(lock: dict, runtimes: list[str], root: Path = ROOT) -> None:
    validate_lock(lock, root)
    if not runtimes or len(set(runtimes)) != len(runtimes) or not set(runtimes) <= set(DESTINATIONS):
        raise ValueError("Select each supported runtime at most once")
    notices = root / "models/licenses"
    for name in NOTICE_FILES:
        if not (notices / name).is_file() or (notices / name).stat().st_size == 0:
            raise ValueError(f"Missing model notice: {name}")
    stages = []
    transactions = []
    try:
        # All artifacts are checked before any installed model is moved.
        for runtime in runtimes:
            selected = lock["runtimes"][runtime]
            source = artifact_path(selected, root)
            validate_bytes(runtime, source, selected)
            validate_runtime(runtime, source)
            destination = root / DESTINATIONS[runtime]
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.is_symlink():
                raise ValueError(f"Refusing symlink destination: {destination}")
            if destination.exists():
                allowed = {MODEL_NAMES[runtime], "conversion.json", "licenses", ".DS_Store"}
                if any(child.name not in allowed for child in destination.iterdir()):
                    raise ValueError(f"Unexpected files in generated model directory: {destination}")
            stage = Path(tempfile.mkdtemp(prefix=f".{destination.name}-stage-", dir=destination.parent))
            stages.append((stage, destination))
            model = stage / MODEL_NAMES[runtime]
            if runtime == "coreml":
                shutil.copytree(source, model)
            else:
                shutil.copy2(source, model)
                shutil.copytree(root / "models/licenses", stage / "licenses")
                for name in NOTICE_FILES:
                    check_file(stage / "licenses" / name,
                               {"bytes": (notices / name).stat().st_size, "sha256": sha256(notices / name)})
            (stage / "conversion.json").write_text(json.dumps(metadata(lock, runtime), indent=2, allow_nan=False) + "\n")
            validate_bytes(runtime, model, selected)
        for stage, destination in stages:
            backup = destination.with_name(f".{destination.name}-backup-{uuid.uuid4().hex}")
            had_existing = destination.exists()
            transactions.append((destination, backup, had_existing))
            if had_existing:
                destination.rename(backup)
            stage.rename(destination)
    except BaseException:
        # A failed second runtime must also restore the first runtime's bundle.
        for destination, backup, had_existing in reversed(transactions):
            if backup.exists() or not had_existing:
                if destination.exists():
                    shutil.rmtree(destination)
                if had_existing:
                    backup.rename(destination)
        raise
    finally:
        for stage, _ in stages:
            if stage.exists():
                shutil.rmtree(stage)
    for _, backup, _ in transactions:
        if backup.exists():
            shutil.rmtree(backup)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", choices=["both", "litert", "coreml"], default="both")
    args = parser.parse_args()
    lock = read_json(ROOT / "models/selected-models.json")
    runtimes = list(DESTINATIONS) if args.runtime == "both" else [args.runtime]
    install(lock, runtimes)
    for runtime in runtimes:
        print(f"Installed {runtime}: {lock['runtimes'][runtime]['model_id']}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Reproduce mobile FAHR-FaceAge artifacts; run with `conda run -n dl python`.

Weights are CC BY-NC-SA 4.0, not covered by the app's code license.
Downloads are pinned and SHA-256 checked before weights-only deserialization.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import math
from pathlib import Path
import shutil
import sys
import urllib.request
import zipfile

import numpy as np
from PIL import Image
import torch
from torch import nn
from ViTForAgeEstimation import ViTForAgeRegression, ViTConfig

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "artifacts/models"
REVISION = "1fbb84ef25956aeeb26e9e279abda2eaeef0504c"
CHECKPOINT_SHA256 = "980ad2f134c5d82e9aca39a738f88a5b10aaf15ca9a5462bf95599be023cdbba"
URL = f"https://huggingface.co/fridolin22/FAHR-Face/resolve/{REVISION}/fahr_faceage.pth"
PORTRAITS_URL = "https://www.dropbox.com/scl/fo/hm9a5x60vya2810gyqty9/AD4yp_v6UY-1HLqMKy902X8?rlkey=d8hfle133ecsvh4bp7ripzeq8&dl=1"
PORTRAIT_SHA256 = "949e6af0c82ca07c4f90ad3344171c9cf69f1c3a003c26d34e2336b18be21f79"


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def checkpoint() -> Path:
    WORK.mkdir(parents=True, exist_ok=True)
    path = WORK / "fahr_faceage.pth"
    if not path.exists():
        temporary = path.with_suffix(".download")
        urllib.request.urlretrieve(URL, temporary)
        if sha256(temporary) != CHECKPOINT_SHA256:
            raise ValueError("Checkpoint hash mismatch; refusing to deserialize")
        temporary.replace(path)
    if sha256(path) != CHECKPOINT_SHA256:
        raise ValueError("Checkpoint hash mismatch; remove the corrupt download and retry")
    return path


def load_author_model() -> nn.Module:
    model = ViTForAgeRegression(ViTConfig(image_size=112, attn_implementation="eager")).eval()
    state = torch.load(checkpoint(), map_location="cpu", weights_only=True)["model_state_dict"]
    # Transformers 5 renamed ViT modules without changing the architecture. Strict
    # loading catches missing/extra tensors; no parameter is initialized at random.
    if "vit.layers.0.attention.q_proj.weight" in model.state_dict():
        replacements = {
            "vit.encoder.layer.": "vit.layers.",
            "attention.attention.query.": "attention.q_proj.",
            "attention.attention.key.": "attention.k_proj.",
            "attention.attention.value.": "attention.v_proj.",
            "attention.output.dense.": "attention.o_proj.",
            "intermediate.dense.": "mlp.fc1.",
            "output.dense.": "mlp.fc2.",
        }
        renamed = {}
        for key, value in state.items():
            for old, new in replacements.items():
                key = key.replace(old, new)
            renamed[key] = value
        state = renamed
    model.load_state_dict(state, strict=True)
    return model


class MobileAge(nn.Module):
    """One aligned 112px RGB crop, float32 NHWC 0..255 → age in years [1]."""

    def __init__(self, author: nn.Module):
        super().__init__()
        self.author = author
        self.register_buffer("mean", torch.tensor([.485, .456, .406]).reshape(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor([.229, .224, .225]).reshape(1, 3, 1, 1))

    def forward(self, pixels):
        normalized = (pixels.permute(0, 3, 1, 2) / 255.0 - self.mean) / self.std
        return self.author(normalized).age_output.reshape(1)


def samples():
    y, x = np.mgrid[:112, :112]
    gradient = np.stack(((3*x + y) % 256, (x + 2*y) % 256, (7*x + 5*y) % 256), axis=-1)
    yield "gradient", gradient.astype(np.float32)[None]
    yield "gray", np.full((1, 112, 112, 3), 127., dtype=np.float32)
    # Optional author-provided synthetic portraits are local test data, not
    # redistributed in Git or the shipping app. They are already face crops.
    portrait_lock = json.loads((ROOT / "models/author-test-images.json").read_text())
    for entry in portrait_lock["images"]:
        path = WORK / "author-portraits" / entry["local_name"]
        if not path.exists():
            continue
        if sha256(path) != entry["sha256"]:
            raise ValueError(f"Author fixture changed: {path.name}")
        im = Image.open(path).convert("RGB").resize((112, 112), Image.Resampling.BILINEAR)
        yield f"author-{path.stem}", np.asarray(im, dtype=np.float32)[None]


def prepare_test_portraits():
    """Author synthetic fixtures for local tests only; never shipping resources."""
    WORK.mkdir(parents=True, exist_ok=True)
    archive = WORK / "author-synthetic.zip"
    if not archive.exists():
        urllib.request.urlretrieve(PORTRAITS_URL, archive)
    portraits = WORK / "author-portraits"
    portraits.mkdir(exist_ok=True)
    portrait_lock = json.loads((ROOT / "models/author-test-images.json").read_text())
    with zipfile.ZipFile(archive) as source:
        names = sorted(n for n in source.namelist() if n.endswith(".png"))
        if names != sorted(e["archive_name"] for e in portrait_lock["images"]) or any(source.getinfo(n).file_size > 10_000_000 for n in names):
            raise ValueError("The author portrait archive changed; review before using it")
        for entry in portrait_lock["images"]:
            data = source.read(entry["archive_name"])
            if hashlib.sha256(data).hexdigest() != entry["sha256"]:
                raise ValueError(f"Author portrait hash mismatch: {entry['local_name']}")
            (portraits / entry["local_name"]).write_bytes(data)
    image = portraits / "00.png"
    if sha256(image) != PORTRAIT_SHA256:
        raise ValueError("Author test portrait changed; refusing to provision it")
    android = ROOT / "androidApp/src/androidTest/assets/author-face.png"
    android.parent.mkdir(parents=True, exist_ok=True)
    android_image = portraits / "03.png"
    if sha256(android_image) != "42743ca2d36c4f2e6f3aeea6a84e4de959bf0f30615f02a5af7e37fd29062db2":
        raise ValueError("Android author test portrait changed")
    shutil.copy2(android_image, android)
    portrait = Image.open(image).convert("RGB")
    portrait.thumbnail((512, 512), Image.Resampling.LANCZOS)
    padded = Image.new("RGB", (portrait.width+256, portrait.height+256), (240, 240, 240))
    padded.paste(portrait, (128, 128))
    ios = WORK / "ios-smoke/author-portrait.jpg"
    ios.parent.mkdir(parents=True, exist_ok=True)
    padded.save(ios, quality=95)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", choices=["coreml", "litert", "both", "reference"], default="both")
    parser.add_argument("--install", action="store_true", help="Retired: use install_quantized_models.py after quantization")
    parser.add_argument("--test-portraits", action="store_true", help="Download author synthetic portraits for local validation (not redistributed)")
    args = parser.parse_args()
    if args.install:
        parser.error(
            "Float32 baseline installation is retired to avoid replacing the selected compressed model. "
            "Prepare baselines without --install, run quantize_litert.py / quantize_coreml.py, "
            "then run scripts/models/install_quantized_models.py. See docs/models.md."
        )
    torch.set_num_threads(4)
    if args.test_portraits:
        prepare_test_portraits()
    author = load_author_model()
    model = MobileAge(author).eval()
    cases = list(samples())
    with torch.inference_mode():
        reference = {name: float(model(torch.from_numpy(pixels))[0]) for name, pixels in cases}
    if not all(math.isfinite(age) for age in reference.values()):
        raise ValueError("Non-finite PyTorch reference prediction")
    report = {
        "checkpoint_sha256": CHECKPOINT_SHA256, "reference_years": reference, "runtimes": {},
        "license": "CC-BY-NC-SA-4.0", "modifications": "Inference-only graph; optimizer removed; RGB NHWC normalization wrapper; float32",
        "input": {"shape": [1,112,112,3], "dtype": "float32", "range": [0,255]},
        "versions": {p: importlib.metadata.version(p) for p in ("torch", "transformers", "numpy", "pillow")},
    }
    (WORK / "reference.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Reference:", reference, flush=True)

    if args.runtime in ("coreml", "both"):
        import coremltools as ct
        traced = torch.jit.trace(model, torch.from_numpy(cases[0][1]))
        converted = ct.convert(
            traced, convert_to="mlprogram", minimum_deployment_target=ct.target.iOS17,
            inputs=[ct.TensorType(name="pixels", shape=(1, 112, 112, 3), dtype=np.float32)],
            outputs=[ct.TensorType(name="age", dtype=np.float32)],
            compute_precision=ct.precision.FLOAT32,
        )
        converted.author = "Haugg, Lee et al.; AIM-Harvard. Mobile conversion: ScanFace."
        converted.license = "CC BY-NC-SA 4.0; https://creativecommons.org/licenses/by-nc-sa/4.0/"
        converted.short_description = "FAHR-FaceAge research facial-age estimate; aligned face input required."
        converted.user_defined_metadata["source_sha256"] = CHECKPOINT_SHA256
        path = WORK / "FAHRFaceAge.mlpackage"
        converted.save(str(path))
        errors = {}
        for name, pixels in cases:
            actual = float(converted.predict({"pixels": pixels})["age"].reshape(-1)[0])
            if not math.isfinite(actual):
                raise ValueError(f"Non-finite Core ML prediction: {name}")
            errors[name] = abs(actual - reference[name])
        if max(errors.values()) > .02:
            raise ValueError(f"Core ML parity failed: {errors}")
        report["runtimes"]["coreml"] = {
            "max_error_years": max(errors.values()), "errors": errors,
            "coremltools": ct.__version__, "minimum_target": "iOS17", "precision": "float32",
            "files": {str(p.relative_to(path)): {"sha256": sha256(p), "bytes": p.stat().st_size} for p in path.rglob("*") if p.is_file()},
        }
        print("Core ML parity:", report["runtimes"]["coreml"], flush=True)

    if args.runtime in ("litert", "both"):
        # Converter-only packages live in this ignored overlay, keeping their
        # older xDSL dependency isolated from the user's conda dl environment.
        overlay = WORK / "converter-python"
        if overlay.exists():
            sys.path.insert(0, str(overlay))
        import litert_torch
        converted = litert_torch.convert(model, (torch.from_numpy(cases[0][1]),))
        path = WORK / "fahr_faceage.tflite"
        converted.export(str(path))
        from ai_edge_litert.interpreter import Interpreter
        interpreter = Interpreter(model_path=str(path), num_threads=4)
        interpreter.allocate_tensors()
        inputs, outputs = interpreter.get_input_details(), interpreter.get_output_details()
        assert len(inputs) == len(outputs) == 1
        assert inputs[0]["shape"].tolist() == [1, 112, 112, 3]
        assert outputs[0]["shape"].tolist() == [1]
        errors = {}
        for name, pixels in cases:
            interpreter.set_tensor(inputs[0]["index"], pixels)
            interpreter.invoke()
            actual = float(interpreter.get_tensor(outputs[0]["index"])[0])
            if not math.isfinite(actual):
                raise ValueError(f"Non-finite LiteRT prediction: {name}")
            errors[name] = abs(actual - reference[name])
        if max(errors.values()) > .02:
            raise ValueError(f"LiteRT parity failed: {errors}")
        report["runtimes"]["litert"] = {
            "max_error_years": max(errors.values()), "errors": errors,
            "sha256": sha256(path), "bytes": path.stat().st_size,
            "signatures": interpreter.get_signature_list(),
            "litert_torch": importlib.metadata.version("litert-torch"),
            "litert": importlib.metadata.version("ai-edge-litert"),
        }
        print("LiteRT parity:", report["runtimes"]["litert"], flush=True)
    (WORK / f"conversion-{args.runtime}.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()

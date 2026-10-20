"""Installer safety tests: ``conda run -n dl python -m unittest discover -s scripts/models/tests``."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("install_quantized_models", Path(__file__).resolve().parents[1] / "install_quantized_models.py")
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class SelectedModelInstallerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        models = self.root / "models"
        models.mkdir()
        self.reference = dict.fromkeys(sorted(installer.CASES), 50.0)
        (models / "conversion-validation.json").write_text(json.dumps({"checkpoint_sha256": installer.CHECKPOINT_SHA256, "reference_years": self.reference}))
        (models / "source-manifest.json").write_text(json.dumps({"checkpoint": {"sha256": installer.CHECKPOINT_SHA256, "license": installer.LICENSE}}))
        (models / "licenses").mkdir()
        for name in installer.NOTICE_FILES:
            (models / "licenses" / name).write_text(f"fixture {name}")
        self.lock = {"schema_version": 1, "source_checkpoint_sha256": installer.CHECKPOINT_SHA256,
                     "license": installer.LICENSE, "input": installer.INPUT, "runtimes": {}}
        work = self.root / "artifacts/models"
        work.mkdir(parents=True)
        for runtime, filename in installer.MODEL_NAMES.items():
            artifact = work / filename
            if runtime == "coreml":
                artifact.mkdir()
                (artifact / "new-model.bin").write_bytes(b"verified Core ML fixture")
                hashes = {"files": {"new-model.bin": self.digest(artifact / "new-model.bin")}}
            else:
                artifact.write_bytes(b"verified LiteRT fixture")
                hashes = self.digest(artifact)
            self.lock["runtimes"][runtime] = {
                "candidate": "int8", "model_id": f"fahr-faceage-1fbb84e-{runtime}-int8-test",
                "precision": "int8 weights", "recipe": {"kind": "fixture"},
                "artifact": artifact.relative_to(self.root).as_posix(), **hashes,
                "predictions_years": self.reference.copy(),
                "drift": {group: {"mean_absolute_years": 0, "max_absolute_years": 0} for group in ("faces", "all")},
            }
            destination = self.root / installer.DESTINATIONS[runtime]
            destination.mkdir(parents=True)
            old_model = destination / filename
            if runtime == "coreml":
                old_model.mkdir()
                (old_model / "stale.bin").write_bytes(b"old")
            else:
                old_model.write_bytes(b"old")
            (destination / "conversion.json").write_text("old metadata")

    @staticmethod
    def digest(path):
        return {"bytes": path.stat().st_size, "sha256": installer.sha256(path)}

    def assert_old_bundles(self):
        for runtime, directory in installer.DESTINATIONS.items():
            self.assertEqual((self.root / directory / "conversion.json").read_text(), "old metadata")
        self.assertTrue((self.root / installer.DESTINATIONS["coreml"] / installer.MODEL_NAMES["coreml"] / "stale.bin").is_file())

    def test_requires_all_face_predictions(self):
        del self.lock["runtimes"]["litert"]["predictions_years"]["author-21"]
        with self.assertRaisesRegex(ValueError, "22 pinned faces"):
            installer.validate_lock(self.lock, self.root)

    def test_recomputes_recorded_metrics(self):
        self.lock["runtimes"]["litert"]["drift"]["faces"]["mean_absolute_years"] = 0.01
        with self.assertRaisesRegex(ValueError, "differs from predictions"):
            installer.validate_lock(self.lock, self.root)

    def test_checks_every_backend_for_drift(self):
        backend = copy.deepcopy(self.reference)
        backend["author-00"] += 0.5
        self.lock["runtimes"]["coreml"]["validation_backends"] = {"cpu": {"predictions_years": backend}}
        with self.assertRaisesRegex(ValueError, "exceeds reviewed engineering gate"):
            installer.validate_lock(self.lock, self.root)

    def test_rejects_nonfinite_prediction(self):
        self.lock["runtimes"]["litert"]["predictions_years"]["gray"] = float("nan")
        with self.assertRaisesRegex(ValueError, "finite numeric"):
            installer.validate_lock(self.lock, self.root)

    def test_corrupt_artifact_preserves_existing_install(self):
        (self.root / self.lock["runtimes"]["litert"]["artifact"]).write_bytes(b"corrupt")
        with patch.object(installer, "validate_runtime"), self.assertRaisesRegex(ValueError, "size/hash mismatch"):
            installer.install(self.lock, ["litert", "coreml"], self.root)
        self.assert_old_bundles()

    def test_rejects_unrecorded_package_file(self):
        source = self.root / self.lock["runtimes"]["coreml"]["artifact"]
        (source / "unrecorded.bin").write_bytes(b"unexpected")
        with patch.object(installer, "validate_runtime"), self.assertRaisesRegex(ValueError, "unrecorded files"):
            installer.install(self.lock, ["litert", "coreml"], self.root)
        self.assert_old_bundles()

    def test_success_replaces_package_and_matches_metadata(self):
        with patch.object(installer, "validate_runtime") as validate:
            installer.install(self.lock, ["litert", "coreml"], self.root)
        self.assertEqual(validate.call_count, 2)
        for runtime, directory in installer.DESTINATIONS.items():
            destination = self.root / directory
            metadata = json.loads((destination / "conversion.json").read_text())
            self.assertEqual(metadata["model_id"], self.lock["runtimes"][runtime]["model_id"])
            self.assertEqual(metadata["predictions_years"], self.reference)
        self.assertFalse((self.root / installer.DESTINATIONS["coreml"] / installer.MODEL_NAMES["coreml"] / "stale.bin").exists())
        self.assertFalse(list(self.root.rglob(".*-backup-*")))
        self.assertFalse(list(self.root.rglob(".*-stage-*")))

    def test_second_runtime_install_failure_rolls_back_both(self):
        original_rename = Path.rename

        def fail_second_install(path, destination):
            if path.name.startswith(".Models-stage-"):
                raise OSError("simulated install interruption")
            return original_rename(path, destination)

        with patch.object(installer, "validate_runtime"), patch.object(Path, "rename", fail_second_install), self.assertRaisesRegex(OSError, "interruption"):
            installer.install(self.lock, ["litert", "coreml"], self.root)
        self.assert_old_bundles()
        self.assertFalse(list(self.root.rglob(".*-backup-*")))
        self.assertFalse(list(self.root.rglob(".*-stage-*")))


if __name__ == "__main__":
    unittest.main()

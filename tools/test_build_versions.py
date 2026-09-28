"""Validation and failure propagation for the version build runner (no downloads)."""

import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import patch

import build_versions as builds


class BuildVersionsTests(unittest.TestCase):
    def load(self, targets):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "targets.json"
            path.write_text(json.dumps(targets), encoding="utf-8")
            return builds.load_targets(path)

    def test_configured_targets(self):
        self.assertTrue({"8.16.0", "8.19.0", "9.1.0", "9.1.9", "9.4.2"}.issubset(
            {target["elasticsearch"] for target in builds.load_targets()}))

    def test_invalid_targets(self):
        for version in ["8.15.9", "7.17.0", "10.0.0", "9.1.0-rc1", "../../outside"]:
            with self.subTest(version=version), self.assertRaises(ValueError):
                self.load([{"elasticsearch": version, "gradle": "8.11", "java": "21"}])

    def test_duplicate_targets(self):
        target = {"elasticsearch": "8.16.0", "gradle": "8.11", "java": "21"}
        with self.assertRaises(ValueError):
            self.load([target, target])

    def test_future_patch_can_be_added(self):
        targets = self.load([{"elasticsearch": "9.4.3", "gradle": "9.5.1", "java": "21"}])
        self.assertEqual(targets[0]["elasticsearch"], "9.4.3")

    def test_unknown_selection_fails(self):
        with self.assertRaises(ValueError):
            builds.select_targets(builds.load_targets(), ["8.16.1"])

    def test_selection_preserves_config_order(self):
        targets = builds.select_targets(builds.load_targets(), ["9.4.2", "8.16.0"])
        self.assertEqual([t["elasticsearch"] for t in targets], ["8.16.0", "9.4.2"])

    def test_failed_build_does_not_skip_remaining_targets(self):
        targets = builds.load_targets()[:2]
        with patch("sys.argv", ["build_versions.py"]), patch.object(builds, "load_targets", return_value=targets), \
                patch.object(builds, "build_target", side_effect=[subprocess.CalledProcessError(1, "gradle"), None]) as run, \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(builds.main(), 1)
            self.assertEqual(run.call_count, 2)

    def test_build_isolates_cache_from_clean_and_checks_descriptor(self):
        target = {"elasticsearch": "8.16.0", "gradle": "8.11", "java": "21"}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            wrapper = root / "gradle/wrapper/gradle-wrapper.jar"
            wrapper.parent.mkdir(parents=True)
            wrapper.write_bytes(b"test wrapper")
            output = root / "build/versions/8.16.0"

            def fake_gradle(command, **kwargs):
                cache = Path(command[command.index("--project-cache-dir") + 1])
                self.assertNotIn(output, cache.parents)
                self.assertIn("clean", command)
                self.assertIn("build", command)
                self.assertIn("-Pversion=8.16.0", command)
                archives = output / "distributions"
                archives.mkdir(parents=True, exist_ok=True)
                with zipfile.ZipFile(archives / "plugin.zip", "w") as archive:
                    archive.writestr("plugin-descriptor.properties",
                                     "elasticsearch.version=8.16.0\nversion=8.16.0-surnames.4\n")

            with patch.object(builds, "ROOT", root), patch.object(builds.subprocess, "run", side_effect=fake_gradle), \
                    contextlib.redirect_stdout(io.StringIO()):
                builds.build_target(target)


if __name__ == "__main__":
    unittest.main()

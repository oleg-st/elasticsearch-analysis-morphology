#!/usr/bin/env python3
"""Build the current surname sources for the targets in build-versions.json."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CONFIG = Path(__file__).with_name("build-versions.json")


def load_targets(path=CONFIG):
    targets = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(targets, list) or not targets:
        raise ValueError("The build matrix must be a non-empty list")
    seen = set()
    for target in targets:
        if not isinstance(target, dict) or set(target) != {"elasticsearch", "gradle", "java"}:
            raise ValueError("Each target must contain elasticsearch, gradle and java")
        version = target["elasticsearch"]
        if not isinstance(version, str) or not re.fullmatch(r"\d+\.\d+\.\d+", version):
            raise ValueError(f"Invalid Elasticsearch version: {version}")
        parts = tuple(map(int, version.split(".")))
        if parts < (8, 16, 0) or parts[0] not in (8, 9):
            raise ValueError(f"Unsupported Elasticsearch version: {version}")
        if version in seen:
            raise ValueError(f"Duplicate Elasticsearch version: {version}")
        seen.add(version)
        if not isinstance(target["gradle"], str) or not re.fullmatch(r"\d+\.\d+(?:\.\d+)?", target["gradle"]):
            raise ValueError(f"Invalid Gradle version for {version}")
        if not isinstance(target["java"], str) or not re.fullmatch(r"\d+", target["java"]) or int(target["java"]) < 21:
            raise ValueError(f"Invalid Java version for {version}")
    return targets


def select_targets(targets, versions):
    missing = set(versions) - {target["elasticsearch"] for target in targets}
    if missing:
        raise ValueError(f"Versions missing from {CONFIG.name}: {', '.join(sorted(missing))}")
    return [target for target in targets if not versions or target["elasticsearch"] in versions]


def java_executable(target):
    # Per-version JDK overrides allow future targets to use a newer compiler.
    java_home = os.environ.get(f"JAVA{target['java']}_HOME") or os.environ.get("JAVA_HOME")
    if java_home:
        return str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java"))
    return "java"


def build_target(target):
    version = target["elasticsearch"]
    gradle = target["gradle"]
    wrapper_dir = ROOT / "build" / "version-wrappers" / gradle
    wrapper_dir.mkdir(parents=True, exist_ok=True)
    jar = wrapper_dir / "gradle-wrapper.jar"
    shutil.copy2(ROOT / "gradle/wrapper/gradle-wrapper.jar", jar)
    jar.with_suffix(".properties").write_text(
        f"distributionUrl=https\\://services.gradle.org/distributions/gradle-{gradle}-bin.zip\n"
        "distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n"
        "zipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n",
        encoding="utf-8",
    )
    print(f"Building Elasticsearch {version}, Gradle {gradle}, JDK {target['java']}", flush=True)
    subprocess.run(
        [java_executable(target), "-classpath", str(jar), "org.gradle.wrapper.GradleWrapperMain",
         "--project-dir", str(ROOT), "--project-cache-dir", str(ROOT / "build/version-caches" / version),
         "--no-daemon", "--console=plain", "clean", "build", f"-Pversion={version}", "-PmatrixBuild=true"],
        cwd=ROOT, check=True,
    )
    archives = list((ROOT / "build/versions" / version / "distributions").glob("*.zip"))
    if len(archives) != 1:
        raise ValueError(f"Expected one plugin ZIP for {version}, found {len(archives)} (clean its build directory)")
    with zipfile.ZipFile(archives[0]) as archive:
        descriptor = archive.read("plugin-descriptor.properties").decode("utf-8")
    properties = dict(line.split("=", 1) for line in descriptor.splitlines() if "=" in line and not line.startswith("#"))
    if properties.get("elasticsearch.version") != version or not properties.get("version", "").startswith(version + "-surnames."):
        raise ValueError(f"Unexpected plugin compatibility/version in {archives[0]}")
    print(f"Verified: {archives[0]}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", action="append", default=[], help="Build only this configured version (repeatable)")
    parser.add_argument("--matrix", action="store_true", help="Print the GitHub Actions matrix without building")
    args = parser.parse_args()
    try:
        targets = select_targets(load_targets(), args.version)
        if args.matrix:
            print(json.dumps({"include": targets}, separators=(",", ":")))
            return 0
        failed = []
        for target in targets:
            try:
                build_target(target)
            except (subprocess.CalledProcessError, OSError, ValueError, zipfile.BadZipFile, KeyError) as error:
                failed.append(target["elasticsearch"])
                print(f"FAILED {target['elasticsearch']}: {error}", file=sys.stderr, flush=True)
        if failed:
            print(f"Failed targets: {', '.join(failed)}", file=sys.stderr)
            return 1
        return 0
    except (OSError, ValueError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    sys.exit(main())

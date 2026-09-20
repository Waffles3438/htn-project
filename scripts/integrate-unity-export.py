#!/usr/bin/env python3
"""Adapt Unity's generated library to the native host. Never import its launcher app."""
from pathlib import Path
import re
ROOT = Path(__file__).resolve().parents[1]
export = ROOT / "unity-export"
library = export / "unityLibrary"
if not (library / "build.gradle").is_file():
    raise SystemExit("No Unity export. Run scripts/export-unity.sh with an activated editor first.")
# Host imports Unity compression/native build properties without overriding unrelated settings.
properties = export / "gradle.properties"
if properties.exists():
    values = dict(line.split("=", 1) for line in properties.read_text().splitlines() if "=" in line and not line.startswith("#"))
    selected = {k: v for k, v in values.items() if k.startswith("unity") or k in ("android.bundle.enableUncompressedNativeLibs", "android.useAndroidX")}
    (export / "unity-host.properties").write_text("\n".join(f"{k}={v}" for k, v in selected.items()) + "\n")
print("Unity export found. Native Gradle host will include unityLibrary on the next build.")

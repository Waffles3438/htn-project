#!/usr/bin/env python3
"""Synchronize generated map/fixtures. Run circuit.export with the backend venv first."""
from pathlib import Path
import shutil
ROOT = Path(__file__).resolve().parents[1]
source = ROOT / "circuit-api"
android = ROOT / "app/src/main/assets"
unity = ROOT / "unity/Assets/CircuitXR/Resources"
android.mkdir(parents=True, exist_ok=True)
unity.mkdir(parents=True, exist_ok=True)
shutil.copyfile(source / "handoff/board-hole-map.meters.json", android / "board-map.json")
shutil.copyfile(android / "board-map.json", unity / "board-map.json")
for name in ("led", "button_led", "arduino_led"):
    shutil.copyfile(source / f"fixtures/{name}.placement.json", android / f"{name}.placement.json")
shutil.copyfile(android / "button_led.placement.json", unity / "demo-circuit.json")
print("Android and Unity maps/fixtures synchronized.")

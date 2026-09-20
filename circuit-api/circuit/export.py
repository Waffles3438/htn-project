"""Regenerate reviewable Unity handoff fixtures and schemas: python -m circuit.export."""
import json
from zipfile import ZipFile, ZIP_DEFLATED
from pathlib import Path

UNITY_SOURCE = Path(__file__).resolve().parents[2] / "unity/Assets/CircuitXR/Runtime"
from . import contracts
from .board import hole_map
from .fixtures import fixture, request_for, PROMPTS
from .service import make_placement


def export():
    contracts.export()
    directory = contracts.ROOT / "fixtures"
    directory.mkdir(exist_ok=True)
    for name in PROMPTS:
        request = request_for(name)
        plan, draft = fixture(name)
        placement = make_placement(request, plan, draft, "fixture")
        placement["generatedAt"] = "2026-09-19T00:00:00+00:00"
        for suffix, value in [("request", request), ("placement", placement)]:
            (directory / (name + "." + suffix + ".json")).write_text(json.dumps(value, indent=2) + "\n")
        print("Exported", name)
    handoff = contracts.ROOT / "handoff"
    handoff.mkdir(exist_ok=True)
    board_path = handoff / "board-hole-map.meters.json"
    board_path.write_text(json.dumps(hole_map(), indent=2) + "\n")
    files = ["UNITY_HANDOFF.md", "UNITY_TEAM_MESSAGE.md", "AGENTS.md", "schemas/placement.schema.json",
             "reference/assets/breadboard.fbx", "reference/assets/breadboard.metadata.json"]
    files += ["fixtures/" + name + ".placement.json" for name in PROMPTS]
    unity = ("CircuitDefinition.cs", "CircuitBoardMap.cs", "CircuitPrefabCatalog.cs", "CircuitBuilder.cs")
    with ZipFile(handoff / "circuit-api-to-unity.zip", "w", ZIP_DEFLATED) as archive:
        for name in files:
            archive.write(contracts.ROOT / name, name)
        for name in unity:
            archive.write(UNITY_SOURCE / name, "unity/" + name)
        archive.write(board_path, board_path.name)
    print("Exported Unity handoff")


if __name__ == "__main__":
    export()

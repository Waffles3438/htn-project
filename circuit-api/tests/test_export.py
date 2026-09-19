import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile

from circuit import contracts
from circuit.board import hole_map
from circuit.export import export
from circuit.fixtures import PROMPTS


class ExportTests(unittest.TestCase):
    def test_package_has_current_map_fixtures_and_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("UNITY_HANDOFF.md", "UNITY_TEAM_MESSAGE.md", "AGENTS.md",
                         "reference/assets/breadboard.fbx", "reference/assets/breadboard.metadata.json"):
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((contracts.ROOT / name).read_bytes())
            with patch.object(contracts, "ROOT", root):
                export()
            with ZipFile(root / "handoff/circuit-api-to-unity.zip") as archive:
                board = json.loads(archive.read("board-hole-map.meters.json"))
                self.assertEqual(board, hole_map())
                positions = {h["id"]: h["position"] for h in board["holes"]}
                schema = json.loads(archive.read("schemas/placement.schema.json"))
                self.assertEqual(schema, contracts.PLACEMENT)
                for name in PROMPTS:
                    placement = json.loads(archive.read("fixtures/" + name + ".placement.json"))
                    self.assertEqual(placement["breadboard"]["holeMapVersion"], board["holeMapVersion"])
                    self.assertFalse(placement["breadboard"]["physicalVerified"])
                    for component in placement["components"]:
                        for terminal in component["terminals"]:
                            self.assertEqual(terminal["position"], positions[terminal["holeId"]])
                    for wire in placement["jumperWires"]:
                        self.assertEqual(wire["startPosition"], positions[wire["fromHole"]])
                        self.assertEqual(wire["endPosition"], positions[wire["toHole"]])
                    for connection in placement.get("externalConnections", []):
                        self.assertEqual(connection["boardPosition"], positions[connection["holeId"]])
                self.assertIsNone(archive.testzip())

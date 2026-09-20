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
                schema = json.loads(archive.read("schemas/placement.schema.json"))
                self.assertEqual(schema, contracts.PLACEMENT)
                for name in PROMPTS:
                    placement = json.loads(archive.read("fixtures/" + name + ".placement.json"))
                    self.assertEqual(placement["breadboard"]["holeMapVersion"], board["holeMapVersion"])
                    self.assertFalse(placement["breadboard"]["physicalVerified"])
                    self.assertEqual(placement["version"], 3)
                    holes = {h["id"] for h in board["holes"]}
                    terminals = {}
                    for component in placement["components"]:
                        self.assertEqual(component["mount"]["type"], "breadboard")
                        self.assertEqual(component["mount"]["board"], board["id"])
                        for terminal, address in component["mount"]["terminals"].items():
                            hole = address.split(":", 1)[1]
                            self.assertIn(hole, holes)
                            terminals[component["id"] + ":" + terminal] = hole
                    device_pins = {d["id"] + ":" + pin for d in placement.get("externalDevices", [])
                                   for pin in ("D13", "GND")}
                    for wire in placement["jumperWires"]:
                        for endpoint in (wire["from"], wire["to"]):
                            if endpoint.startswith("BB1:"):
                                self.assertIn(endpoint.split(":", 1)[1], holes)
                            else:
                                self.assertIn(endpoint, set(terminals) | device_pins)
                    net_ids = [n["id"] for n in placement["nets"]]
                    self.assertEqual(len(net_ids), len(set(net_ids)))
                    for net in placement["nets"]:
                        self.assertGreaterEqual(len(net["members"]), 2)
                        for member in net["members"]:
                            self.assertIn(member, set(terminals) | device_pins)
                self.assertIsNone(archive.testzip())
                for name in ("unity/CircuitDefinition.cs", "unity/CircuitBoardMap.cs",
                             "unity/CircuitPrefabCatalog.cs", "unity/CircuitBuilder.cs"):
                    self.assertIn("namespace CircuitXR", archive.read(name).decode())

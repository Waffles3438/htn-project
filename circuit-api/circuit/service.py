import json
import os
import tempfile
import threading
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from . import contracts
from .addresses import BOARD_ID, hole_address
from .board import BOARD, WARNING
from .fixtures import fixture, PROMPTS
from .nets import build_nets
from .provider import CircuitProvider
from .validation import CircuitError, schema_check, validate_request, validate_plan, validate_layout

ASSETS = {"led": "led_red_v1", "resistor": "resistor_220ohm_v1", "button": "button_momentary_v1", "power_supply": "power_supply_5v_v1"}
ORDER = {"led": ["anode", "cathode"], "resistor": ["a", "b"], "button": ["a1", "b1", "a2", "b2"], "power_supply": ["positive", "negative"]}


def make_placement(request, plan, draft, source):
    if any(c["type"] == "arduino_uno" for c in plan["components"]):
        from .mcu import make_mcu_placement
        return make_mcu_placement(request, plan, draft, source)
    inventory = validate_request(request)
    checks = validate_layout(plan, draft, inventory)
    definitions = {c["id"]: c for c in plan["components"]}
    components, instructions = [], []
    for c in draft["components"]:
        definition = definitions[c["id"]]
        kind = definition["type"]
        pins = {t["name"]: t["hole"] for t in c["terminals"]}
        # Semantic mount only: renderers derive coordinates from the board map.
        mount = {"type": "breadboard", "board": BOARD_ID,
                 "terminals": {name: hole_address(pins[name]) for name in ORDER[kind]}}
        components.append({"id": c["id"], "type": kind, "value": definition["value"], "assetId": ASSETS[kind],
                           "mount": mount, "buildStep": c["buildStep"]})
        labels = {"positive": "+5V (+)", "negative": "GND (−)", "anode": "anode (+, long lead)", "cathode": "cathode (−, short lead)", "a": "lead A", "b": "lead B"}
        leads = ", ".join(labels.get(name, name.upper()) + " → " + pins[name] for name in ORDER[kind])
        text = "With power disconnected, place %s (%s): %s." % (kind.replace("_", " "), definition["value"], leads)
        if kind == "led":
            text += " The long lead is normally the anode; verify the flat side/short lead is the cathode."
        if kind == "button":
            text += " Verify a1/a2 and b1/b2 are the internally connected pin pairs."
        if kind == "power_supply":
            text = "After checking the full circuit, connect the external 5 V source last: %s." % leads
        instructions.append({"step": c["buildStep"], "componentIds": [c["id"]], "text": text})
    wires = []
    for w in draft["wires"]:
        wires.append({"id": w["id"], "from": hole_address(w["from"]), "to": hole_address(w["to"]),
                      "color": w["color"], "buildStep": w["buildStep"]})
        instructions.append({"step": w["buildStep"], "componentIds": [w["id"]],
                             "text": "With power disconnected, connect the %s jumper from %s to %s." % (w["color"], w["from"], w["to"])})
    quantities = Counter((c["type"], c["value"]) for c in plan["components"])
    required = [{"type": t, "value": v, "quantity": n} for (t, v), n in quantities.items()]
    required.append({"type": "jumper_wire", "value": "male-male", "quantity": len(wires)})
    ordered_components = sorted(components, key=lambda c: c["buildStep"])
    ordered_wires = wires
    placement = {"version": 3, "sessionId": request["sessionId"], "breadboardModel": BOARD["model"],
                 "title": plan["title"], "prompt": request["prompt"], "source": source,
                 "generatedAt": datetime.now(timezone.utc).isoformat(),
                 "breadboard": {"model": BOARD["model"], "holeMapVersion": BOARD["holeMapVersion"], "physicalVerified": False},
                 "requiredParts": required, "components": ordered_components,
                 "jumperWires": ordered_wires, "nets": build_nets(ordered_components, ordered_wires),
                 "validation": {"valid": True, "checks": checks, "warnings": [WARNING]},
                 "instructions": sorted(instructions, key=lambda i: i["step"])}
    schema_check(placement, contracts.PLACEMENT)
    return placement


class CircuitService:
    def __init__(self, data_dir=None, provider=None):
        self.data_dir = Path(data_dir) if data_dir else contracts.ROOT / "data"
        self.provider = provider or CircuitProvider()
        self.lock = threading.Lock()

    def analyze(self, request):
        inventory = validate_request(request)
        plan = self.provider.analyze(request)
        validate_plan(plan, inventory)
        return plan

    def generate(self, request):
        plan = self.analyze(request)
        draft = self.provider.layout(request, plan)
        placement = make_placement(request, plan, draft, getattr(self.provider, "source", "openai"))
        self.save(placement)
        return placement

    def demo(self, name, request):
        validate_request(request)
        plan, draft = fixture(name)
        # Demo endpoint is explicitly a named lesson, with truthful provenance.
        request = {**request, "prompt": PROMPTS[name]}
        placement = make_placement(request, plan, draft, "fixture")
        self.save(placement)
        return placement

    def save(self, placement):
        self.data_dir.mkdir(parents=True, exist_ok=True)
        destination = self.data_dir / (placement["sessionId"] + ".placement.json")
        with self.lock:
            with tempfile.NamedTemporaryFile(mode="w", dir=self.data_dir, delete=False, suffix=".tmp") as file:
                json.dump(placement, file, indent=2, allow_nan=False)
                temporary = file.name
            try:
                os.replace(temporary, destination)
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)

    def latest(self, session_id):
        schema_check(session_id, contracts.REQUEST["properties"]["sessionId"], "INVALID_SESSION", 400)
        path = self.data_dir / (session_id + ".placement.json")
        if not path.is_file():
            raise CircuitError("NO_PLACEMENT", "This session has no validated placement yet.", 404)
        placement = json.loads(path.read_text())
        if placement.get("version") != 3:
            raise CircuitError("OUTDATED_PLACEMENT", "This saved circuit uses an older coordinate-based format. Generate it again to refresh it.", 409)
        schema_check(placement, contracts.PLACEMENT)
        return placement

import json
import math
import os
import tempfile
import threading
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from . import contracts
from .board import BOARD, HOLES, WARNING
from .fixtures import fixture, PROMPTS
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
        terminals = [{"id": n, "holeId": pins[n], "position": HOLES[pins[n]]["position"]} for n in ORDER[kind]]
        points = [t["position"] for t in terminals]
        position = {axis: round(sum(p[axis] for p in points)/len(points), 8) for axis in "xyz"}
        # Normalized prefab +X runs from terminal 0 to terminal 1; +Y out of board.
        yaw = -math.atan2(points[1]["z"]-points[0]["z"], points[1]["x"]-points[0]["x"])
        rotation = {"x": 0, "y": round(math.sin(yaw/2), 8), "z": 0, "w": round(math.cos(yaw/2), 8)}
        components.append({"id": c["id"], "type": kind, "value": definition["value"], "assetId": ASSETS[kind],
                           "terminals": terminals, "position": position, "rotation": rotation, "buildStep": c["buildStep"]})
        labels = {"positive": "+5V (+)", "negative": "GND (−)", "anode": "anode (+, long lead)", "cathode": "cathode (−, short lead)", "a": "lead A", "b": "lead B"}
        leads = ", ".join(labels.get(t["id"], t["id"].upper()) + " → " + t["holeId"] for t in terminals)
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
        wires.append({"id": w["id"], "fromHole": w["from"], "toHole": w["to"], "color": w["color"], "buildStep": w["buildStep"],
                      "startPosition": HOLES[w["from"]]["position"], "endPosition": HOLES[w["to"]]["position"]})
        instructions.append({"step": w["buildStep"], "componentIds": [w["id"]],
                             "text": "With power disconnected, connect the %s jumper from %s to %s." % (w["color"], w["from"], w["to"])})
    quantities = Counter((c["type"], c["value"]) for c in plan["components"])
    required = [{"type": t, "value": v, "quantity": n} for (t, v), n in quantities.items()]
    required.append({"type": "jumper_wire", "value": "male-male", "quantity": len(wires)})
    placement = {"version": 1, "sessionId": request["sessionId"], "breadboardModel": BOARD["model"],
                 "title": plan["title"], "prompt": request["prompt"], "source": source,
                 "generatedAt": datetime.now(timezone.utc).isoformat(), "breadboard": BOARD,
                 "requiredParts": required, "components": sorted(components, key=lambda c: c["buildStep"]),
                 "jumperWires": wires, "validation": {"valid": True, "checks": checks, "warnings": [WARNING]},
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
        schema_check(placement, contracts.PLACEMENT)
        return placement

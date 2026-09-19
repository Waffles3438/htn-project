"""Fail closed for the supported single LED series circuits, not a general SPICE simulator."""
import re
from collections import Counter
from jsonschema import Draft202012Validator
from . import contracts
from .board import HOLES, MODEL


class CircuitError(Exception):
    def __init__(self, code, message, status=422, details=None):
        super().__init__(message)
        self.code, self.message, self.status, self.details = code, message, status, details or []


def require(condition, code, message, details=None):
    if not condition:
        raise CircuitError(code, message, details=details)


def schema_check(value, schema, code="INVALID_SCHEMA", status=422):
    errors = sorted(Draft202012Validator(schema).iter_errors(value), key=lambda e: str(list(e.path)))
    if errors:
        raise CircuitError(code, "JSON does not match the required schema.", status,
                           [{"path": ".".join(map(str, e.path)), "message": e.message} for e in errors[:8]])


def normalize_value(value):
    return value.lower().replace(" ", "").replace("ω", "ohm").replace("ohms", "ohm")


CATALOG = {"arduino_uno": "unor3", "power_supply": "5v", "led": "red", "resistor": "220ohm", "button": "momentary", "jumper_wire": "male-male"}
PINS = {"power_supply": {"positive", "negative"}, "led": {"anode", "cathode"},
        "resistor": {"a", "b"}, "button": {"a1", "a2", "b1", "b2"}}


def validate_request(request):
    schema_check(request, contracts.REQUEST, "INVALID_REQUEST", 400)
    require(request["prompt"].strip(), "INVALID_REQUEST", "Enter a circuit prompt.")
    require(request["breadboardModel"] == MODEL, "UNKNOWN_BOARD", "Select the supported reference breadboard.")
    inventory = Counter()
    for part in request["availableParts"]:
        value = normalize_value(part["value"])
        kind = "jumper_wire" if part["type"] == "jumper" else part["type"]
        require(value == CATALOG[kind], "UNSUPPORTED_PART", "Choose a supported kit value: red LED, 220 ohm, momentary button, 5V supply, or Uno R3.")
        inventory[(kind, value)] += part["quantity"]
    return inventory


def validate_plan(plan, inventory):
    schema_check(plan, contracts.PLAN)
    require(plan["behavior"] != "unsupported", "UNSUPPORTED_CIRCUIT", plan["explanation"])
    ids = [c["id"] for c in plan["components"]]
    require(len(ids) == len(set(ids)), "DUPLICATE_ID", "Component IDs must be unique.")
    counts = Counter(c["type"] for c in plan["components"])
    mcu = counts.get("arduino_uno", 0) > 0
    require(not mcu or plan["behavior"] in ("always_on", "blink"), "UNSUPPORTED_CIRCUIT", "Uno R3 currently supports an always-on or one-second blinking external LED.")
    require(plan["behavior"] != "blink" or mcu, "UNSUPPORTED_CIRCUIT", "Blinking requires an Arduino Uno R3.")
    expected = Counter({"arduino_uno" if mcu else "power_supply": 1, "led": 1, "resistor": 1})
    if plan["behavior"] == "while_pressed":
        expected["button"] = 1
    require(counts == expected, "UNSUPPORTED_CIRCUIT", "Use one source/controller, one red LED and one 220 ohm resistor. A series button is supported with the 5 V supply.")
    missing = []
    for c in plan["components"]:
        value = normalize_value(c["value"])
        require(value == CATALOG[c["type"]], "UNSUPPORTED_PART", "Only the fixed demo kit values are supported.")
        if inventory[(c["type"], value)] < 1:
            missing.append({"type": c["type"], "value": c["value"], "quantity": 1})
    require(not missing, "MISSING_PARTS", "The selected inventory is missing required parts.", missing)


class Nets:
    def __init__(self):
        self.parent = {hole["net"]: hole["net"] for hole in HOLES.values()}

    def find(self, net):
        if self.parent[net] != net:
            self.parent[net] = self.find(self.parent[net])
        return self.parent[net]

    def hole(self, hole):
        return self.find(HOLES[hole]["net"])

    def join(self, a, b):
        self.parent[self.hole(a)] = self.hole(b)


def validate_layout(plan, draft, inventory):
    validate_plan(plan, inventory)
    schema_check(draft, contracts.DRAFT)
    definitions = {c["id"]: c for c in plan["components"]}
    ids = [c["id"] for c in draft["components"]]
    wire_ids = [w["id"] for w in draft["wires"]]
    require(len(ids + wire_ids) == len(set(ids + wire_ids)), "DUPLICATE_ID", "All component and wire IDs must be unique.")
    require(set(ids) == set(definitions), "PARTS_CHANGED", "Layout must place exactly the analyzed components.")
    require(len(draft["wires"]) <= inventory[("jumper_wire", "male-male")], "MISSING_PARTS", "Not enough jumper wires in the selected inventory.")
    occupied = set()
    pins = {}
    steps = []

    def occupy(hole):
        require(hole in HOLES, "INVALID_HOLE", "Unknown hole: " + hole)
        require(hole not in occupied, "OVERLAPPING_TERMINALS", "More than one lead occupies " + hole)
        occupied.add(hole)

    for component in draft["components"]:
        kind = definitions[component["id"]]["type"]
        terminals = component["terminals"]
        require({t["name"] for t in terminals} == PINS[kind] and len(terminals) == len(PINS[kind]),
                "INVALID_TERMINALS", "Incorrect terminal names for " + kind)
        pins[kind] = {t["name"]: t["hole"] for t in terminals}
        for t in terminals:
            occupy(t["hole"])
        p = pins[kind]
        if kind in ("led", "resistor"):
            a, b = [p[name] for name in (("anode", "cathode") if kind == "led" else ("a", "b"))]
            require(all(re.fullmatch(r"[A-J][1-9][0-9]*", h) for h in [a, b]), "INVALID_FOOTPRINT", "Place component leads on terminal strips.")
            span = abs(int(a[1:]) - int(b[1:]))
            require(a[0] == b[0] and (span == 1 if kind == "led" else 3 <= span <= 10),
                    "INVALID_FOOTPRINT", "Unsupported lead spacing for " + kind)
        if kind == "button":
            require(re.fullmatch(r"E[1-9][0-9]*", p["a1"]), "INVALID_FOOTPRINT", "Button a1 must start on column E.")
            r = int(p["a1"][1:])
            require(p == {"a1": "E" + str(r), "a2": "E" + str(r+3), "b1": "F" + str(r), "b2": "F" + str(r+3)},
                    "INVALID_FOOTPRINT", "Button must straddle E/F with paired pins three rows apart.")
            # Reserve its body so another component cannot be hidden underneath.
            for other in draft["components"]:
                if other["id"] != component["id"]:
                    require(not any(re.fullmatch(r"[EF][1-9][0-9]*", t["hole"]) and r <= int(t["hole"][1:]) <= r+3 for t in other["terminals"]),
                            "BODY_COLLISION", "A component sits inside the button footprint.")
        steps.append(component["buildStep"])
    for wire in draft["wires"]:
        occupy(wire["from"])
        occupy(wire["to"])
        if "button" in pins:
            r = int(pins["button"]["a1"][1:])
            require(not any(re.fullmatch(r"[EF][1-9][0-9]*", h) and r <= int(h[1:]) <= r+3 for h in [wire["from"], wire["to"]]),
                    "BODY_COLLISION", "A jumper endpoint sits under the button.")
        steps.append(wire["buildStep"])
    require(sorted(steps) == list(range(1, len(steps)+1)), "INVALID_STEPS", "Build steps must be unique and consecutive from 1.")
    supply = next(c for c in draft["components"] if definitions[c["id"]]["type"] == "power_supply")
    require(supply["buildStep"] == max(steps), "POWER_SEQUENCE", "Connect the power supply last.")

    def circuit_state(pressed):
        nets = Nets()
        for wire in draft["wires"]:
            nets.join(wire["from"], wire["to"])
        if "button" in pins:
            p = pins["button"]
            nets.join(p["a1"], p["a2"])
            nets.join(p["b1"], p["b2"])
            if pressed:
                nets.join(p["a1"], p["b1"])
        pos, neg = (nets.hole(pins["power_supply"][n]) for n in ("positive", "negative"))
        require(pos != neg, "POWER_SHORT", "The supply is shorted through breadboard connections, wires, or the pressed button.")
        ra, rb = (nets.hole(pins["resistor"][n]) for n in ("a", "b"))
        la, lc = (nets.hole(pins["led"][n]) for n in ("anode", "cathode"))
        require(ra != rb and la != lc, "BYPASSED_COMPONENT", "A wire or connected row bypasses the resistor or LED.")
        require(not (la == pos and lc == neg), "UNPROTECTED_LED", "LED is across the supply without a series resistor.")
        # With one resistor and one diode there are exactly two valid directed series arrangements.
        resistor_first = ((ra == pos and rb == la) or (rb == pos and ra == la)) and lc == neg
        led_first = la == pos and ((ra == lc and rb == neg) or (rb == lc and ra == neg))
        return resistor_first or led_first, nets, {pos, neg, ra, rb, la, lc}

    active, nets, used = circuit_state(True)
    require(active, "INVALID_CIRCUIT", "No complete forward LED path with a series 220 ohm resistor. Check polarity and wiring.")
    for wire in draft["wires"]:
        require(nets.hole(wire["from"]) in used, "DANGLING_WIRE", "A jumper is disconnected from the circuit.")
    if plan["behavior"] == "while_pressed":
        released, _, _ = circuit_state(False)
        require(not released, "BUTTON_BYPASSED", "The LED is on even when the button is released.")
    return ["schema", "inventory", "known_holes", "unique_leads", "reference_footprints", "power_connected_last",
            "no_supply_short_in_either_button_state", "forward_led_with_series_220ohm", "requested_button_behavior"]

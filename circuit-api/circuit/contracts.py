"""Single source of truth for exported JSON Schemas."""
import json
from copy import deepcopy
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def obj(**properties):
    return {"type": "object", "properties": properties,
            "required": list(properties), "additionalProperties": False}


def arr(items, minimum=0, maximum=100):
    return {"type": "array", "items": items, "minItems": minimum, "maxItems": maximum}


def string(*values):
    return {"type": "string", **({"enum": list(values)} if values else {"minLength": 1, "maxLength": 2000})}


ID = {"type": "string", "pattern": "^[A-Za-z][A-Za-z0-9_-]{0,39}$"}
STEP = {"type": "integer", "minimum": 1, "maximum": 100}
TYPE = string("power_supply", "led", "resistor", "button", "arduino_uno")
PART = obj(type=string("power_supply", "led", "resistor", "button", "arduino_uno", "jumper_wire", "jumper"),
           value=string(), quantity={"type": "integer", "minimum": 0, "maximum": 30})
# Android contract: a bare {"prompt"} is valid; the backend fills the default kit,
# the reference board and a shared session id before validation (see validation.normalize_request).
REQUEST = {"type": "object",
           "properties": {"prompt": {"type": "string", "minLength": 3, "maxLength": 1000},
                          "availableParts": arr(PART, 1, 20), "breadboardModel": string(),
                          "sessionId": {"type": "string", "pattern": "^[A-Za-z0-9_-]{1,64}$"}},
           "required": ["prompt"], "additionalProperties": False}
PLAN_PART = obj(id=ID, type=TYPE, value=string(), purpose=string())
PLAN = obj(title=string(), behavior=string("always_on", "while_pressed", "blink", "unsupported"),
           explanation=string(), components=arr(PLAN_PART, 0, 8))
TERMINAL = obj(name=string("positive", "negative", "anode", "cathode", "a", "b", "a1", "a2", "b1", "b2"), hole=string())
DRAFT_COMPONENT = obj(id=ID, terminals=arr(TERMINAL, 2, 4), buildStep=STEP)
WIRE = obj(id=ID, **{"from": string(), "to": string()},
           color=string("red", "black", "yellow", "blue", "green"), buildStep=STEP)
DRAFT = obj(components=arr(DRAFT_COMPONENT, 3, 4), wires=arr(WIRE, 0, 12))
# Semantic endpoints: board addresses (BB1:A15, BB1:RAIL:L:+:A:12) or component terminals (led_1:anode).
ENDPOINT = {"type": "string",
            "pattern": "^(BB1:(RAIL:(L\\+|L-|R\\+|R-):(A|B)(:[1-9][0-9]*)?|[A-J][1-9][0-9]*)|[A-Za-z][A-Za-z0-9_-]{0,39}:[A-Za-z][A-Za-z0-9_-]{0,39})$"}
TERMINAL_MAP = {"type": "object", "additionalProperties": ENDPOINT, "minProperties": 2, "maxProperties": 4}
# Footprint-based mounting is reserved in the format: a mount may carry anchor + orientation
# instead of an explicit terminal map (breadboard-compatible controllers). Not emitted yet.
BREADBOARD_MOUNT = {"type": "object",
                    "properties": {"type": string("breadboard"), "board": string("BB1"),
                                   "terminals": TERMINAL_MAP, "anchor": ENDPOINT,
                                   "orientation": string("north", "south", "east", "west")},
                    "required": ["type", "board", "terminals"], "additionalProperties": False}
PLACEMENT_COMPONENT = obj(id=ID, type=string("power_supply", "led", "resistor", "button"), value=string(), assetId=string(),
                          mount=BREADBOARD_MOUNT, buildStep=STEP)
PLACEMENT_WIRE = obj(id=ID, **{"from": ENDPOINT, "to": ENDPOINT},
                     color=string("red", "black", "yellow", "blue", "green"), buildStep=STEP)
NET = obj(id=string(), members=arr(ENDPOINT, 2, 8))
PLACEMENT_V3 = obj(version={"type": "integer", "const": 3}, sessionId=REQUEST["properties"]["sessionId"],
                   breadboardModel=string(), title=string(), prompt=string(), source=string("openai", "openrouter", "fixture"), generatedAt=string(),
                   breadboard=obj(model=string(), holeMapVersion=string(), physicalVerified={"type": "boolean"}),
                   requiredParts=arr(PART, 1, 8), components=arr(PLACEMENT_COMPONENT, 3, 4),
                   jumperWires=arr(PLACEMENT_WIRE, 0, 12), nets=arr(NET, 2, 6),
                   validation=obj(valid={"type": "boolean", "const": True}, checks=arr(string()), warnings=arr(string())),
                   instructions=arr(obj(step=STEP, componentIds=arr(ID), text=string()), 1))
# Controllers too large for the breadboard mount externally: renderers derive the pose
# from relativeTo/side plus their own asset anchors. No XYZ coordinates are exchanged.
PLACEMENT_V3_MCU = deepcopy(PLACEMENT_V3)
PLACEMENT_V3_MCU['properties']['components'] = arr(PLACEMENT_COMPONENT, 2, 2)
PLACEMENT_V3_MCU['properties']['externalDevices'] = arr(obj(id=ID, type=string('arduino_uno'),
    model=string('uno_r3'), assetId=string('arduino_uno_r3_v1'),
    mount=obj(type=string('external'), relativeTo=string('BB1'), side=string('left', 'right', 'top', 'bottom'))), 1, 1)
PLACEMENT_V3_MCU['properties']['firmware'] = obj(filename=string('circuit.ino'), board=string('Arduino Uno R3'),
    language=string('arduino'), code=string(), uploadInstructions=string())
PLACEMENT_V3_MCU['required'] += ['externalDevices', 'firmware']
PLACEMENT = {'oneOf': [PLACEMENT_V3, PLACEMENT_V3_MCU]}


def export():
    directory = ROOT / "schemas"
    directory.mkdir(exist_ok=True)
    for name, schema in {"request": REQUEST, "plan": PLAN, "layout-draft": DRAFT, "placement": PLACEMENT}.items():
        (directory / (name + ".schema.json")).write_text(json.dumps(schema, indent=2) + "\n")


if __name__ == "__main__":
    export()

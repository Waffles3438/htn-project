"""Single source of truth for exported JSON Schemas."""
import json
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
TYPE = string("power_supply", "led", "resistor", "button")
PART = obj(type=string("power_supply", "led", "resistor", "button", "jumper_wire", "jumper"),
           value=string(), quantity={"type": "integer", "minimum": 0, "maximum": 30})
REQUEST = obj(prompt={"type": "string", "minLength": 3, "maxLength": 1000},
              availableParts=arr(PART, 1, 20), breadboardModel=string(),
              sessionId={"type": "string", "pattern": "^[A-Za-z0-9_-]{1,64}$"})
PLAN_PART = obj(id=ID, type=TYPE, value=string(), purpose=string())
PLAN = obj(title=string(), behavior=string("always_on", "while_pressed", "unsupported"),
           explanation=string(), components=arr(PLAN_PART, 0, 8))
TERMINAL = obj(name=string("positive", "negative", "anode", "cathode", "a", "b", "a1", "a2", "b1", "b2"), hole=string())
DRAFT_COMPONENT = obj(id=ID, terminals=arr(TERMINAL, 2, 4), buildStep=STEP)
WIRE = obj(id=ID, **{"from": string(), "to": string()},
           color=string("red", "black", "yellow", "blue", "green"), buildStep=STEP)
DRAFT = obj(components=arr(DRAFT_COMPONENT, 3, 4), wires=arr(WIRE, 0, 12))
VEC = obj(x={"type": "number"}, y={"type": "number"}, z={"type": "number"})
QUAT = obj(x={"type": "number"}, y={"type": "number"}, z={"type": "number"}, w={"type": "number"})
FRAME = obj(units=string("meters"), handedness=string("left"), originHole=string("A1"),
            xAxis=string("A1 toward J1"), yAxis=string("out of board"),
            zAxis=string("A1 toward A63"))
PLACEMENT_COMPONENT = obj(id=ID, type=TYPE, value=string(), assetId=string(),
                          terminals=arr(obj(id=string(), holeId=string(), position=VEC), 2, 4),
                          position=VEC, rotation=QUAT, buildStep=STEP)
PLACEMENT_WIRE = obj(id=ID, fromHole=string(), toHole=string(), color=string(),
                     startPosition=VEC, endPosition=VEC, buildStep=STEP)
PLACEMENT = obj(version={"type": "integer", "const": 1}, sessionId=REQUEST["properties"]["sessionId"],
                breadboardModel=string(), title=string(), prompt=string(), source=string("openai", "openrouter", "fixture"), generatedAt=string(),
                breadboard=obj(model=string(), holeMapVersion=string(), physicalVerified={"type": "boolean"},
                               coordinateFrame=FRAME, calibrationReferences=arr(obj(hole=string(), position=VEC), 3, 3)),
                requiredParts=arr(PART, 1, 8), components=arr(PLACEMENT_COMPONENT, 3, 4),
                jumperWires=arr(PLACEMENT_WIRE, 0, 12),
                validation=obj(valid={"type": "boolean", "const": True}, checks=arr(string()), warnings=arr(string())),
                instructions=arr(obj(step=STEP, componentIds=arr(ID), text=string()), 1))


def export():
    directory = ROOT / "schemas"
    directory.mkdir(exist_ok=True)
    for name, schema in {"request": REQUEST, "plan": PLAN, "layout-draft": DRAFT, "placement": PLACEMENT}.items():
        (directory / (name + ".schema.json")).write_text(json.dumps(schema, indent=2) + "\n")


if __name__ == "__main__":
    export()

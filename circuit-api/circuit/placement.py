"""Map an AI-authored series topology onto distinct holes of the reference board."""
from .board import HOLES
from .validation import require


def place_series(plan, design):
    definitions = {c["id"]: c for c in plan["components"]}
    supply_id = next(c["id"] for c in plan["components"] if c["type"] == "power_supply")
    path = design["seriesPath"]
    required = set(definitions) - {supply_id}
    require(len(path) == len(required) and set(path) == required,
            "INVALID_TOPOLOGY", "The electrical path must contain each required load exactly once.")
    row, column = design["startRow"], design["column"]
    occupied, blocked, components, links = set(), set(), [], []

    def component(cid, pins, step):
        for hole in pins.values():
            require(hole in HOLES and hole not in occupied, "INVALID_PLACEMENT", "Unable to fit the generated design on the board.")
            occupied.add(hole)
        return {"id": cid, "terminals": [{"name": name, "hole": hole} for name, hole in pins.items()], "buildStep": step}

    supply = component(supply_id, {"positive": "J1", "negative": "J2"}, 1)
    previous = "J1"
    for index, cid in enumerate(path):
        kind = definitions[cid]["type"]
        if kind == "button":
            pins = {"a1": "E"+str(row), "a2": "E"+str(row+3), "b1": "F"+str(row), "b2": "F"+str(row+3)}
            entry, exit_hole, span = pins["a1"], pins["b1"], 3
            blocked.update(col+str(r) for col in "EF" for r in range(row, row+4))
        else:
            span = 1 if kind == "led" else 3
            names = ("anode", "cathode") if kind == "led" else ("a", "b")
            pins = {names[0]: column+str(row), names[1]: column+str(row+span)}
            entry, exit_hole = pins[names[0]], pins[names[1]]
        components.append(component(cid, pins, index+1))
        links.append((previous, entry))
        previous = exit_hole
        row += span + 3
    links.append((previous, "J2"))

    def wire_endpoint(terminal):
        target = HOLES[terminal]
        candidates = [h for h in HOLES.values() if h["net"] == target["net"] and h["id"] not in occupied | blocked]
        candidates.sort(key=lambda h: (sum((h["position"][a]-target["position"][a])**2 for a in "xyz"), h["id"]))
        require(candidates, "NO_FREE_HOLE", "No vacant hole remains on a required conductive strip.")
        hole = candidates[0]["id"]
        occupied.add(hole)
        return hole

    wires = []
    ids = set(definitions)
    for index, (start, end) in enumerate(links):
        wid = "wire_"+str(index+1)
        while wid in ids:
            wid += "_w"
        ids.add(wid)
        wires.append({"id": wid, "from": wire_endpoint(start), "to": wire_endpoint(end),
                      "color": "red" if index == 0 else "black" if index == len(links)-1 else "yellow",
                      "buildStep": len(components)+index+1})
    supply["buildStep"] = len(components)+len(wires)+1
    components.append(supply)
    return {"components": components, "wires": wires}

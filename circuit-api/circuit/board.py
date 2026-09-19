"""Geometry imported unchanged from the hardware owner's person2 branch."""
import json
import re
from .contracts import ROOT

REFERENCE = ROOT / "reference" / "person2"
RAW_BOARD = json.loads((REFERENCE / "breadboard.json").read_text())
MODEL = RAW_BOARD["id"]
FRAME = {"units": "meters", "handedness": "left", "originHole": "A1",
         "xAxis": "A1 toward J1", "yAxis": "out of board", "zAxis": "A1 toward A63"}


def vector(x, y, z):
    return {"x": round(x, 8), "y": round(y, 8), "z": round(z, 8)}


HOLES = {}
for h in RAW_BOARD["holes"]:
    # Source publishes geometry, not conductivity: standard strip assumption.
    if re.fullmatch(r"[A-J][1-9][0-9]*", h["id"]):
        net = ("left" if h["id"][0] in "ABCDE" else "right") + h["id"][1:]
    else:
        net = re.sub(r"\d+$", "", h["id"])
    HOLES[h["id"]] = {"id": h["id"], "position": vector(*(h[k]/1000 for k in "xyz")), "net": net}

BOARD = {"model": MODEL, "holeMapVersion": "person2-9d81633", "physicalVerified": False,
         "coordinateFrame": FRAME,
         "calibrationReferences": [{"hole": h, "position": HOLES[h]["position"]} for h in ["A1", "J1", "A63"]]}
WARNING = ("Geometry comes from person2 commit 9d81633. Electrical strip connectivity and the button's "
           "7.62 mm square footprint/pin pairing still need physical confirmation. The source dimensions "
           "do not agree with its hole extents; hole coordinates are authoritative for placement. "
           "Only LED asset metadata is supplied; other asset IDs require normalized Unity prefabs.")


def hole_map():
    return {**BOARD, "holes": list(HOLES.values()), "notes": WARNING,
            "footprints": {"led": "Adjacent rows in one terminal-strip column; 2.54 mm pin spacing",
                           "resistor": "Same terminal-strip column, 3–10 row intervals; formed axial leads",
                           "button": "a1=E(r), a2=E(r+3), b1=F(r), b2=F(r+3); provisional 7.62 mm square",
                           "power_supply": "External 5 V source; positive/negative are flying lead insertion points"}}


def default_inventory():
    return [{**p, "type": "jumper_wire" if p["type"] == "jumper" else p["type"]}
            for p in json.loads((REFERENCE / "components.json").read_text())["components"]]

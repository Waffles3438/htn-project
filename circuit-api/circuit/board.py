"""Pinned terminal geometry with a modeled symmetric rail correction; see UNITY_HANDOFF.md."""
import json
import re
from .contracts import ROOT

REFERENCE = ROOT / "reference" / "person2"
RAW_BOARD = json.loads((REFERENCE / "breadboard.json").read_text())
MODEL = RAW_BOARD["id"]
PITCH_MM = RAW_BOARD["pitch"]
FRAME = {"units": "meters", "handedness": "left", "originHole": "A1",
         "xAxis": "A1 toward J1", "yAxis": "out of board", "zAxis": "A1 toward A63"}

# The raw map places the left rails 1 pitch from column A but the right rails 5 pitches from column J, and runs
# every rail as an unbroken 25-hole strip from row 1. Our corrected model places each rail pair
# 3 pitches outside the nearest terminal column, with each 25-hole segment in five 5-hole groups separated by one
# empty pitch, spanning rows 3-31 (segment A) and 33-61 (segment B). The overall rail-to-rail span (48.26 mm) and
# the +/- ordering of the raw map are unchanged. This model still requires physical measurement.
RAIL_X = {"L+": -4 * PITCH_MM, "L-": -3 * PITCH_MM, "R+": 27.94 + 3 * PITCH_MM, "R-": 27.94 + 4 * PITCH_MM}
RAIL_SEGMENT_FIRST_ROW = {"A": 3, "B": 33}


def rail_row(segment, index):
    """1-based terminal-strip row that rail hole `index` (1-25) of `segment` (A/B) lines up with."""
    group, offset = divmod(index - 1, 5)
    return RAIL_SEGMENT_FIRST_ROW[segment] + group * 6 + offset


def vector(x, y, z):
    return {"x": round(x, 8), "y": round(y, 8), "z": round(z, 8)}


HOLES = {}
for h in RAW_BOARD["holes"]:
    # Source publishes geometry, not conductivity: standard strip assumption.
    if re.fullmatch(r"[A-J][1-9][0-9]*", h["id"]):
        net = ("left" if h["id"][0] in "ABCDE" else "right") + h["id"][1:]
        position = vector(*(h[k]/1000 for k in "xyz"))
    else:
        net = re.sub(r"\d+$", "", h["id"])
        rail, segment, index = net[:2], net[2], int(h["id"][3:])
        position = vector(RAIL_X[rail] / 1000, 0, (rail_row(segment, index) - 1) * PITCH_MM / 1000)
    HOLES[h["id"]] = {"id": h["id"], "position": position, "net": net}

BOARD = {"model": MODEL, "holeMapVersion": "person2-9d81633+rails1", "physicalVerified": False,
         "coordinateFrame": FRAME,
         "calibrationReferences": [{"hole": h, "position": HOLES[h]["position"]} for h in ["A1", "J1", "A63"]]}
WARNING = ("Terminal-strip geometry comes from person2 commit 9d81633. Power rail holes use a modeled symmetric "
           "layout, not measured hardware (rails 3 pitches outside A and J, 5-hole groups spanning rows 3-61), because the source rails were "
           "asymmetric; see UNITY_HANDOFF.md. Electrical strip connectivity and the button's 7.62 mm square "
           "footprint/pin pairing still need physical confirmation. The source dimensions do not agree with its "
           "hole extents; hole coordinates are authoritative for placement. Only LED asset metadata is supplied; "
           "other asset IDs require normalized Unity prefabs.")


def hole_map():
    return {**BOARD, "holes": list(HOLES.values()), "notes": WARNING,
            "footprints": {"led": "Adjacent rows in one terminal-strip column; 2.54 mm pin spacing",
                           "resistor": "Same terminal-strip column, 3–10 row intervals; formed axial leads",
                           "button": "a1=E(r), a2=E(r+3), b1=F(r), b2=F(r+3); provisional 7.62 mm square",
                           "power_supply": "External 5 V source; positive/negative are flying lead insertion points"}}


def default_inventory():
    return [{**p, "type": "jumper_wire" if p["type"] == "jumper" else p["type"]}
            for p in json.loads((REFERENCE / "components.json").read_text())["components"]]

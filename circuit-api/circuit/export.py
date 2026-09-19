"""Regenerate reviewable Unity handoff fixtures and schemas: python -m circuit.export."""
import json
from . import contracts
from .fixtures import fixture, request_for, PROMPTS
from .service import make_placement


def export():
    contracts.export()
    directory = contracts.ROOT / "fixtures"
    directory.mkdir(exist_ok=True)
    for name in PROMPTS:
        request = request_for(name)
        plan, draft = fixture(name)
        placement = make_placement(request, plan, draft, "fixture")
        placement["generatedAt"] = "2026-09-19T00:00:00+00:00"
        for suffix, value in [("request", request), ("placement", placement)]:
            (directory / (name + "." + suffix + ".json")).write_text(json.dumps(value, indent=2) + "\n")
        print("Exported", name)


if __name__ == "__main__":
    export()

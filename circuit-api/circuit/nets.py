"""Named electrical nets derived from semantic placement — no rendering geometry involved.

Nets group component terminal endpoints (and unmounted controller pins) by electrical
connectivity: shared breadboard strips, jumper wires, and internal component joins
(the button's a1/a2 and b1/b2 pairs). Naming is deterministic: GND and VCC by power
membership, then LED_SIGNAL / BUTTON_SIGNAL by component role, then SIGNAL_n.
"""
from .addresses import endpoint_parts, hole_from_address
from .validation import INTERNAL_JOINS, Nets


def build_nets(components, jumper_wires):
    """Return [{id, members}] for the given mounted components and endpoint wires."""
    nets = Nets()
    kinds = {c["id"]: c["type"] for c in components}
    mounted = {}
    for component in components:
        for terminal, address in component["mount"]["terminals"].items():
            hole = hole_from_address(address)
            if hole:
                mounted[component["id"] + ":" + terminal] = hole

    def node(endpoint):
        """Union-find node for an endpoint: its strip net when mounted/on-board, else a virtual node."""
        hole = mounted.get(endpoint)
        if hole is None and not endpoint_parts(endpoint):
            hole = hole_from_address(endpoint)
        if hole:
            return nets.hole(hole)
        if endpoint not in nets.parent:
            nets.parent[endpoint] = endpoint
        return nets.parent[endpoint]

    def join(a, b):
        nets.parent[node(a)] = node(b)

    for wire in jumper_wires:
        join(wire["from"], wire["to"])
    for component in components:
        for a, b in INTERNAL_JOINS.get(component["type"], ()):
            join(component["id"] + ":" + a, component["id"] + ":" + b)

    members = {}
    endpoints = list(mounted)
    endpoints += [w[side] for w in jumper_wires for side in ("from", "to") if endpoint_parts(w[side])]
    for endpoint in endpoints:
        members.setdefault(node(endpoint), set()).add(endpoint)

    used = set()
    signals = 0
    result = []
    for group in sorted(sorted(members) for members in members.values() if len(members) > 1):
        parts = [endpoint_parts(member) for member in group]
        component_ids = {part[0] for part in parts}
        terminal_names = {part[1] for part in parts}
        if "negative" in terminal_names or "GND" in terminal_names:
            name = "GND"
        elif "positive" in terminal_names:
            name = "VCC"
        elif any(kinds.get(cid) == "led" for cid in component_ids):
            name = "LED_SIGNAL"
        elif any(kinds.get(cid) == "button" for cid in component_ids):
            name = "BUTTON_SIGNAL"
        else:
            signals += 1
            name = "SIGNAL_%d" % signals
        if name in used:
            signals += 1
            name = "SIGNAL_%d" % signals
        used.add(name)
        result.append({"id": name, "members": group})
    return result

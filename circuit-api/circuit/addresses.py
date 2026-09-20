"""Canonical semantic board addresses — the interchange truth renderers resolve to geometry.

Board addresses name breadboard points without coordinates:
  BB1:A15                     strip hole A15
  BB1:RAIL:L:+:A:12           rail hole L+A12 (rail L+, segment A, 1-based hole index 12)
  BB1:RAIL:L:+:A              a whole rail segment net (every hole in segment A of rail L+)
Component endpoints name a component terminal without coordinates:
  led_1:anode, resistor_1:b, mcu_1:D13
Coordinates are derived by each renderer from the board map and component definitions.
"""
import re

BOARD_ID = "BB1"

STRIP_ADDRESS = re.compile(r"^BB1:([A-J])([1-9][0-9]*)$")
RAIL_ADDRESS = re.compile(r"^BB1:RAIL:(L|R):(\+|-):(A|B):([1-9][0-9]*)$")
RAIL_NET_ADDRESS = re.compile(r"^BB1:RAIL:(L|R):(\+|-):(A|B)$")
COMPONENT_ENDPOINT = re.compile(r"^([A-Za-z][A-Za-z0-9_-]{0,39}):([A-Za-z][A-Za-z0-9_-]{0,39})$")


def hole_address(hole_id):
    """Board address for a terminal-strip hole id (A1..J63)."""
    return BOARD_ID + ":" + hole_id


def rail_address(rail, segment, index):
    """Board address for one rail hole; index is the 1-based hole index within the segment."""
    return "%s:RAIL:%s:%s:%d" % (BOARD_ID, rail, segment, index)


def hole_from_address(address):
    """Hole id for a board address, or None when the address is not a single board hole."""
    strip = STRIP_ADDRESS.match(address)
    if strip:
        return strip.group(1) + strip.group(2)
    rail = RAIL_ADDRESS.match(address)
    if rail:
        if int(rail.group(4)) > 25:
            return None
        return rail.group(1) + rail.group(2) + rail.group(3) + rail.group(4)
    return None


def is_board_net_address(address):
    return RAIL_NET_ADDRESS.match(address) is not None


def endpoint_parts(endpoint):
    """(component_id, terminal_id) for a component endpoint, else None for board addresses."""
    match = COMPONENT_ENDPOINT.match(endpoint)
    if not match or match.group(1) == BOARD_ID:
        return None
    return match.group(1), match.group(2)


def component_endpoint(component_id, terminal):
    return component_id + ":" + terminal

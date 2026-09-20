"""Uno LED adapter: validate the driven-high circuit before exposing external pin endpoints."""
from copy import deepcopy
from .addresses import BOARD_ID, hole_from_address
from . import contracts
from .board import HOLES
from .nets import build_nets
from .validation import validate_request, validate_plan, require, schema_check


def electrical_plan(plan):
    result = deepcopy(plan)
    for component in result['components']:
        if component['type'] == 'arduino_uno':
            component.update(type='power_supply', value='5V')
            result['behavior'] = 'always_on'
    return result


def make_mcu_placement(request, plan, draft, source):
    from .service import make_placement
    inventory = validate_request(request)
    validate_plan(plan, inventory)
    require(len(draft['wires']) + 2 <= inventory[('jumper_wire', 'male-male')],
            'MISSING_PARTS', 'Include two jumper wires for the Uno D13 and GND connections.')
    virtual = deepcopy(request)
    virtual['availableParts'] = [p for p in virtual['availableParts'] if p['type'] != 'power_supply']
    for part in virtual['availableParts']:
        if part['type'] == 'arduino_uno':
            part.update(type='power_supply', value='5V')
    result = make_placement(virtual, electrical_plan(plan), draft, source)
    # The virtual supply becomes the external controller: its two terminals turn into
    # component endpoints on the wires that touched their strips (mcu_1:D13, mcu_1:GND).
    device = next(c for c in result['components'] if c['type'] == 'power_supply')
    result['components'].remove(device)
    replaced = []
    used_wires = set()
    for terminal, pin in (('positive', 'D13'), ('negative', 'GND')):
        hole = hole_from_address(device['mount']['terminals'][terminal])
        target = HOLES[hole]['net']
        for wire in result['jumperWires']:
            if wire['id'] in used_wires:
                continue
            for side in ('from', 'to'):
                endpoint_hole = hole_from_address(wire[side])
                if endpoint_hole and HOLES[endpoint_hole]['net'] == target:
                    wire[side] = device['id'] + ':' + pin
                    used_wires.add(wire['id'])
                    replaced.append((pin, endpoint_hole, wire['id']))
                    break
    require(len(replaced) == 2, 'INVALID_TOPOLOGY',
            'Could not attach the controller pins to the generated wiring.')
    result['externalDevices'] = [dict(id=device['id'], type='arduino_uno', model='uno_r3',
                                    assetId='arduino_uno_r3_v1',
                                    mount={'type': 'external', 'relativeTo': BOARD_ID, 'side': 'left'})]
    for part in result['requiredParts']:
        if part['type'] == 'power_supply':
            part.update(type='arduino_uno', value='Uno R3')
        if part['type'] == 'jumper_wire':
            part['quantity'] += 2
    code = 'const int LED_PIN = 13;\n\nvoid setup() {\n  pinMode(LED_PIN, OUTPUT);\n}\n\nvoid loop() {\n  digitalWrite(LED_PIN, HIGH);\n'
    if plan['behavior'] == 'blink':
        code += '  delay(1000);\n  digitalWrite(LED_PIN, LOW);\n  delay(1000);\n'
    code += '}\n'
    result['firmware'] = dict(filename='circuit.ino', board='Arduino Uno R3', language='arduino', code=code,
        uploadInstructions='Select Arduino Uno and its USB port in Arduino IDE. Wire with USB disconnected; check polarity, then connect USB and upload circuit.ino. No separate 5 V supply is used.')
    result['instructions'][-1]['text'] = 'With USB disconnected, connect Uno ' + ', '.join(pin + ' → ' + hole for pin, hole, _ in replaced) + '. Check wiring, then connect USB and upload circuit.ino.'
    result['instructions'][-1]['componentIds'] += [wire_id for _, _, wire_id in replaced]
    result['validation']['checks'] += ['uno_d13_high_series_path', 'uno_d13_low_no_external_supply', 'external_lead_inventory']
    result['validation']['warnings'].append('Uno R3 only: D13 drives one red LED through 220 ohm. Unity derives the Uno pose from its external mount plus measured pin anchors. Firmware has not been uploaded to physical hardware.')
    result['nets'] = build_nets(result['components'], result['jumperWires'])
    schema_check(result, contracts.PLACEMENT)
    return result

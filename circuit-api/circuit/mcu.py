"""Uno LED adapter: validate the driven-high circuit before exposing external pin endpoints."""
from copy import deepcopy
from . import contracts
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
    device = next(c for c in result['components'] if c['type'] == 'power_supply')
    result['components'].remove(device)
    result['version'] = 2
    result['externalDevices'] = [dict(id=device['id'], type='arduino_uno', model='uno_r3',
                                    assetId='arduino_uno_r3_v1', placementMode='separate_anchor_required')]
    used = {c['id'] for c in draft['components']} | {w['id'] for w in draft['wires']}
    connections = []
    for terminal, pin, color in zip(device['terminals'], ['D13', 'GND'], ['red', 'black']):
        wire_id = 'external_' + pin.lower()
        while wire_id in used:
            wire_id += '_x'
        used.add(wire_id)
        connections.append(dict(id=wire_id, deviceId=device['id'], pin=pin,
                                holeId=terminal['holeId'], boardPosition=terminal['position'],
                                color=color, buildStep=device['buildStep']))
    result['externalConnections'] = connections
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
    result['instructions'][-1]['text'] = 'With USB disconnected, connect Uno ' + ', '.join(c['pin'] + ' → ' + c['holeId'] for c in connections) + '. Check wiring, then connect USB and upload circuit.ino.'
    result['instructions'][-1]['componentIds'] += [c['id'] for c in connections]
    result['validation']['checks'] += ['uno_d13_high_series_path', 'uno_d13_low_no_external_supply', 'external_lead_inventory']
    result['validation']['warnings'].append('Uno R3 only: D13 drives one red LED through 220 ohm. Uno pose and pin anchors must be measured separately in Unity. Firmware has not been uploaded to physical hardware.')
    schema_check(result, contracts.PLACEMENT)
    return result

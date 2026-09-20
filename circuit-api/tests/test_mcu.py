import unittest
from copy import deepcopy
from circuit import contracts
from circuit.fixtures import fixture, request_for
from circuit.service import make_placement
from circuit.mcu import electrical_plan
from circuit.placement import place_series
from circuit.validation import CircuitError, schema_check


class McuTests(unittest.TestCase):
    def setUp(self):
        self.request = request_for('arduino_led')
        self.plan, self.draft = fixture('arduino_led')

    def generate(self):
        return make_placement(self.request, self.plan, self.draft, 'fixture')

    def test_external_pins_and_firmware(self):
        result = self.generate()
        schema_check(result, contracts.PLACEMENT)
        self.assertEqual(result['version'], 3)
        self.assertEqual({c['type'] for c in result['components']}, {'led', 'resistor'})
        device = result['externalDevices'][0]
        self.assertEqual(device['mount'], {'type': 'external', 'relativeTo': 'BB1', 'side': 'left'})
        self.assertNotIn('position', device)
        endpoints = {w['from'] for w in result['jumperWires']} | {w['to'] for w in result['jumperWires']}
        self.assertIn('mcu_1:D13', endpoints)
        self.assertIn('mcu_1:GND', endpoints)
        self.assertIn('digitalWrite(LED_PIN, LOW)', result['firmware']['code'])
        self.assertEqual(next(p['quantity'] for p in result['requiredParts'] if p['type']=='jumper_wire'), 4)
        gnd = next(n for n in result['nets'] if n['id'] == 'GND')
        self.assertIn('mcu_1:GND', gnd['members'])

    def test_live_geometry_orders_and_bounds(self):
        for path in [['led_1','resistor_1'], ['resistor_1','led_1']]:
            for column in 'ABCDGHIJ':
                for row in [4,40]:
                    with self.subTest(path=path,column=column,row=row):
                        self.draft = place_series(electrical_plan(self.plan), {'seriesPath':path,'startRow':row,'column':column})
                        self.generate()

    def test_steady_firmware(self):
        self.plan['behavior']='always_on'
        self.assertNotIn('LOW', self.generate()['firmware']['code'])

    def test_external_jumper_inventory_counted(self):
        for p in self.request['availableParts']:
            if p['type']=='jumper_wire':p['quantity']=3
        with self.assertRaises(CircuitError) as e:self.generate()
        self.assertEqual(e.exception.code,'MISSING_PARTS')

    def test_missing_controller_rejected(self):
        self.request['availableParts']=[p for p in self.request['availableParts'] if p['type']!='arduino_uno']
        with self.assertRaises(CircuitError) as e:self.generate()
        self.assertEqual(e.exception.code,'MISSING_PARTS')

    def test_short_rejected_before_export(self):
        self.draft['wires'][0]['to']='H2'
        with self.assertRaises(CircuitError):self.generate()

    def test_mcu_button_behavior_is_not_silently_changed(self):
        self.plan['behavior']='while_pressed'
        with self.assertRaises(CircuitError) as e:self.generate()
        self.assertEqual(e.exception.code,'UNSUPPORTED_CIRCUIT')

    def test_led_placement_is_semantic_v3(self):
        plan, draft = fixture('led')
        p = make_placement(request_for('led'), plan, draft, 'fixture')
        schema_check(p, contracts.PLACEMENT)
        self.assertEqual(p['version'], 3)
        self.assertNotIn('firmware', p)
        self.assertNotIn('externalDevices', p)
        self.assertTrue(all(c['mount']['type'] == 'breadboard' for c in p['components']))
        self.assertNotIn('position', p['components'][0])

import json
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch
from circuit import contracts
from circuit.board import HOLES, RAW_BOARD, RAIL_X, rail_row
from circuit.fixtures import fixture, request_for
from circuit.provider import parse_response, responses_json, parse_chat_response, openrouter_json, provider_settings, CircuitProvider
from circuit.service import CircuitService, make_placement
from circuit.validation import CircuitError, Nets, validate_request, validate_layout, schema_check
from server import create_server


class LayoutTests(unittest.TestCase):
    def setUp(self):
        self.request = request_for()
        self.plan, self.draft = fixture("button_led")
        self.inventory = validate_request(self.request)

    def reject(self, code):
        with self.assertRaises(CircuitError) as caught:
            validate_layout(self.plan, self.draft, self.inventory)
        self.assertEqual(caught.exception.code, code)

    def test_both_fixtures_and_output_contract(self):
        for name in ["led", "button_led"]:
            with self.subTest(name=name):
                plan, draft = fixture(name)
                p = make_placement(request_for(name), plan, draft, "fixture")
                schema_check(p, contracts.PLACEMENT)
                self.assertTrue(p["validation"]["valid"])
                self.assertEqual(p["components"][-1]["type"], "power_supply")

    def test_person2_geometry_exact_conversion(self):
        self.assertEqual(len(HOLES), 830)
        terminal = [h for h in RAW_BOARD["holes"] if h["id"][0] != "L" and h["id"][0] != "R"]
        self.assertEqual(len(terminal), 630)
        for h in terminal:
            for axis in "xyz":
                self.assertAlmostEqual(HOLES[h["id"]]["position"][axis], h[axis]/1000)
        self.assertAlmostEqual(HOLES["A63"]["position"]["z"], .15748)

    def test_power_rails_follow_symmetric_modeled_layout(self):
        rails = [h for h in HOLES.values() if h["id"][0] in "LR"]
        self.assertEqual(len(rails), 200)
        # Symmetric: 3 pitches outside A and J, pairs 1 pitch apart, same 48.26 mm rail-to-rail span as the source.
        self.assertAlmostEqual(HOLES["L-A1"]["position"]["x"], -.00762)
        self.assertAlmostEqual(HOLES["L+A1"]["position"]["x"], -.01016)
        self.assertAlmostEqual(HOLES["R+A1"]["position"]["x"], .02794 + .00762)
        self.assertAlmostEqual(HOLES["R-A1"]["position"]["x"], .02794 + .01016)
        self.assertAlmostEqual(RAIL_X["R-"] - RAIL_X["L+"], 48.26)
        self.assertAlmostEqual(HOLES["A1"]["position"]["x"] - HOLES["L-A1"]["position"]["x"],
                               HOLES["R+A1"]["position"]["x"] - HOLES["J1"]["position"]["x"])
        # Five 5-hole groups per segment with one empty pitch between groups, rows 3-31 and 33-61.
        self.assertEqual([rail_row("A", i) for i in (1, 5, 6, 10, 25)], [3, 7, 9, 13, 31])
        self.assertEqual([rail_row("B", i) for i in (1, 25)], [33, 61])
        self.assertAlmostEqual(HOLES["L+A1"]["position"]["z"], HOLES["A3"]["position"]["z"])
        self.assertAlmostEqual(HOLES["R-B25"]["position"]["z"], HOLES["J61"]["position"]["z"])
        for h in rails:
            self.assertEqual(h["position"]["y"], 0)
        for prefix in ("L+", "L-", "R+", "R-"):
            for segment, first_row in (("A", 3), ("B", 33)):
                for index in range(1, 26):
                    h = HOLES[prefix + segment + str(index)]
                    row = first_row + ((index - 1) // 5) * 6 + (index - 1) % 5
                    self.assertAlmostEqual(h["position"]["x"], RAIL_X[prefix] / 1000)
                    self.assertAlmostEqual(h["position"]["z"], (row - 1) * .00254)
                    self.assertEqual(h["net"], prefix + segment)
        self.assertEqual(len({(h["position"]["x"], h["position"]["z"]) for h in HOLES.values()}), 830)

    def test_electrical_strips_and_split_rails(self):
        n = Nets()
        self.assertEqual(n.hole("A1"), n.hole("E1"))
        self.assertNotEqual(n.hole("E1"), n.hole("F1"))
        self.assertNotEqual(n.hole("A1"), n.hole("A2"))
        self.assertEqual(n.hole("L+A1"), n.hole("L+A25"))
        self.assertNotEqual(n.hole("L+A25"), n.hole("L+B1"))
        self.assertNotEqual(n.hole("L+A1"), n.hole("L-A1"))

    def test_person2_jumper_inventory_alias(self):
        for p in self.request["availableParts"]:
            if p["type"] == "jumper_wire":
                p["type"] = "jumper"
        inventory = validate_request(self.request)
        self.assertEqual(inventory[("jumper_wire", "male-male")], 10)
        validate_layout(self.plan, self.draft, inventory)

    def test_unknown_hole(self):
        self.draft["wires"][0]["from"] = "A999"
        self.reject("INVALID_HOLE")

    def test_overlapping_wire_and_component_leads(self):
        self.draft["wires"][0]["from"] = "J1"
        self.reject("OVERLAPPING_TERMINALS")

    def test_resistor_shorted_by_jumper(self):
        self.draft["wires"].append({"id":"bypass", "from":"D12", "to":"D15", "color":"red", "buildStep":7})
        self.draft["components"][-1]["buildStep"] = 8
        self.reject("BYPASSED_COMPONENT")

    def test_direct_power_ground_short(self):
        self.draft["wires"][0]["to"] = "H2"
        self.reject("POWER_SHORT")

    def test_short_only_when_button_pressed(self):
        self.draft["wires"][1]["to"] = "H2"
        self.reject("POWER_SHORT")

    def test_reversed_led(self):
        t = self.draft["components"][0]["terminals"]
        t[0]["hole"], t[1]["hole"] = t[1]["hole"], t[0]["hole"]
        self.reject("INVALID_CIRCUIT")

    def test_open_circuit(self):
        self.draft["wires"][1]["to"] = "C13"
        self.reject("INVALID_CIRCUIT")

    def test_button_bypassed(self):
        self.draft["wires"][0]["to"] = "H8"
        self.reject("BUTTON_BYPASSED")

    def test_led_unprotected(self):
        self.draft["wires"][1]["to"] = "C15"
        self.reject("UNPROTECTED_LED")

    def test_button_footprint(self):
        self.draft["components"][2]["terminals"][1]["hole"] = "E12"
        self.reject("INVALID_FOOTPRINT")

    def test_component_leads_on_rails_rejected_cleanly(self):
        self.draft["components"][0]["terminals"][0]["hole"] = "L+A1"
        self.reject("INVALID_FOOTPRINT")

    def test_missing_resistor_inventory(self):
        self.inventory[("resistor", "220ohm")] = 0
        self.reject("MISSING_PARTS")

    def test_missing_jumper_inventory(self):
        self.inventory[("jumper_wire", "male-male")] = 0
        self.reject("MISSING_PARTS")

    def test_duplicate_ids(self):
        self.draft["wires"][0]["id"] = "led_1"
        self.reject("DUPLICATE_ID")

    def test_unknown_component_and_extra_properties(self):
        self.plan["components"][0]["type"] = "motor"
        self.reject("INVALID_SCHEMA")

    def test_no_resistor(self):
        self.plan["components"] = [p for p in self.plan["components"] if p["type"] != "resistor"]
        self.reject("UNSUPPORTED_CIRCUIT")

    def test_power_last(self):
        self.draft["components"][0]["buildStep"], self.draft["components"][-1]["buildStep"] = 7, 1
        self.reject("POWER_SEQUENCE")

    def test_unsupported_voltage(self):
        self.request["availableParts"][-1]["value"] = "12V"
        with self.assertRaises(CircuitError) as caught:
            validate_request(self.request)
        self.assertEqual(caught.exception.code, "UNSUPPORTED_PART")

    def test_semantic_mounts_nets_and_no_geometry(self):
        p = make_placement(self.request, self.plan, self.draft, "fixture")
        led = p["components"][0]
        self.assertEqual(led["mount"], {"type": "breadboard", "board": "BB1",
                                        "terminals": {"anode": "BB1:A15", "cathode": "BB1:A16"}})
        self.assertEqual(led["assetId"], "led_red_v1")
        self.assertNotIn("position", led)
        self.assertNotIn("rotation", led)
        for wire in p["jumperWires"]:
            self.assertNotIn("startPosition", wire)
            self.assertNotIn("endPosition", wire)
            self.assertTrue(wire["from"].startswith("BB1:") and wire["to"].startswith("BB1:"))
        names = {n["id"] for n in p["nets"]}
        self.assertEqual(names, {"VCC", "GND", "LED_SIGNAL", "BUTTON_SIGNAL"})
        gnd = next(n for n in p["nets"] if n["id"] == "GND")
        self.assertEqual(gnd["members"], ["led_1:cathode", "power_1:negative"])
        vcc = next(n for n in p["nets"] if n["id"] == "VCC")
        self.assertEqual(vcc["members"], ["button_1:a1", "button_1:a2", "power_1:positive"])


class AddressTests(unittest.TestCase):
    def test_board_address_round_trip(self):
        from circuit.addresses import endpoint_parts, hole_address, hole_from_address
        self.assertEqual(hole_from_address("BB1:A15"), "A15")
        self.assertEqual(hole_from_address("BB1:RAIL:L:+:A:12"), "L+A12")
        self.assertEqual(hole_address("A1"), "BB1:A1")
        self.assertIsNone(hole_from_address("BB1:RAIL:L:+:A:26"))
        self.assertIsNone(hole_from_address("BB1:Z15"))
        self.assertIsNone(hole_from_address("led_1:anode"))
        self.assertEqual(endpoint_parts("led_1:anode"), ("led_1", "anode"))
        self.assertIsNone(endpoint_parts("BB1:A15"))


class ProviderTests(unittest.TestCase):
    @patch.dict(os.environ, {"CIRCUIT_PROVIDER":"auto", "OPENROUTER_API_KEY":"test-placeholder"}, clear=True)
    def test_openrouter_autodetection(self):
        self.assertEqual(provider_settings()["provider"], "openrouter")
        self.assertTrue(provider_settings()["liveConfigured"])

    @patch.dict(os.environ, {"CIRCUIT_PROVIDER":"openrouter", "OPENROUTER_API_KEY":"test-placeholder"}, clear=True)
    def test_openrouter_strict_schema_request(self):
        plan, _ = fixture("led")
        result = {"choices":[{"finish_reason":"stop", "message":{"content":json.dumps(plan)}}]}
        with patch("urllib.request.urlopen") as fetch:
            fetch.return_value.__enter__.return_value.read.return_value = json.dumps(result).encode()
            self.assertEqual(openrouter_json("plan", contracts.PLAN, "instructions", {}), plan)
            request = fetch.call_args.args[0]
            body = json.loads(request.data)
            self.assertEqual(request.full_url, "https://openrouter.ai/api/v1/chat/completions")
            self.assertTrue(body["response_format"]["json_schema"]["strict"])
            self.assertTrue(body["provider"]["require_parameters"])

    def test_openrouter_rejects_incomplete_refusal_and_malformed_output(self):
        for result in [{"choices":[]}, {"choices":[{"finish_reason":"length", "message":{"content":"{}"}}]},
                       {"choices":[{"finish_reason":"stop", "message":{"refusal":"No"}}]},
                       {"choices":[{"finish_reason":"stop", "message":{"content":"not json"}}]}]:
            with self.subTest(result=result), self.assertRaises(CircuitError):
                parse_chat_response(result, contracts.PLAN)

    def test_topology_schema_and_mapping(self):
        plan, _ = fixture("button_led")
        design = {"seriesPath": ["button_1", "resistor_1", "led_1"], "startRow": 8, "column": "C"}
        provider = CircuitProvider()
        with patch.object(provider, "generate_json", return_value=design) as generate:
            draft = provider.layout(request_for(), plan)
            schema = generate.call_args.args[1]
            schema_check(design, schema)
            validate_layout(plan, draft, validate_request(request_for()))
            self.assertEqual(len(draft["components"]), 4)
            design["seriesPath"] = ["led_1"] * 3
            with self.assertRaises(CircuitError):
                provider.layout(request_for(), plan)

    def test_placement_permutations_and_board_boundaries(self):
        from itertools import permutations
        from circuit.placement import place_series
        for name in ["led", "button_led"]:
            plan, _ = fixture(name)
            ids = [c["id"] for c in plan["components"] if c["type"] != "power_supply"]
            for path in permutations(ids):
                for column in "ABCDGHIJ":
                    for start in [4, 40]:
                        with self.subTest(circuit=name, path=path, column=column, start=start):
                            draft = place_series(plan, {"seriesPath": path, "column": column, "startRow": start})
                            validate_layout(plan, draft, validate_request(request_for(name)))

    def response(self, plan):
        return {"status":"completed", "output":[{"type":"message", "content":[{"type":"output_text", "text":json.dumps(plan)}]}]}

    def test_completed_response(self):
        plan, _ = fixture("led")
        self.assertEqual(parse_response(self.response(plan), contracts.PLAN), plan)

    def test_incomplete_refusal_bad_json_and_extra_fields(self):
        plan, _ = fixture("led")
        variants = [({"status":"incomplete"}, "INCOMPLETE_GENERATION"),
                    ({"status":"completed", "output":[{"type":"message", "content":[{"type":"refusal"}]}]}, "GENERATION_REFUSED"),
                    ({"status":"completed", "output":[]}, "INVALID_PROVIDER_RESPONSE"),
                    (self.response({**plan,"extra":True}), "INVALID_SCHEMA")]
        for value, code in variants:
            with self.subTest(code=code), self.assertRaises(CircuitError) as caught:
                parse_response(value, contracts.PLAN)
            self.assertEqual(caught.exception.code, code)

    @patch.dict(os.environ, {"OPENAI_API_KEY":"test-placeholder"})
    def test_responses_wire_format_and_strict_schema(self):
        plan, _ = fixture("led")
        with patch("urllib.request.urlopen") as fetch:
            fetch.return_value.__enter__.return_value.read.return_value = json.dumps(self.response(plan)).encode()
            self.assertEqual(responses_json("plan", contracts.PLAN, "instructions", {}), plan)
            request = fetch.call_args.args[0]
            body = json.loads(request.data)
            self.assertEqual(request.full_url, "https://api.openai.com/v1/responses")
            self.assertTrue(body["text"]["format"]["strict"])
            self.assertFalse(body["store"])
            self.assertEqual(body["text"]["format"]["schema"], contracts.PLAN)

    @patch.dict(os.environ, {"OPENAI_API_KEY":""})
    def test_missing_key_is_explicit(self):
        with self.assertRaises(CircuitError) as caught:
            responses_json("plan", contracts.PLAN, "instructions", {})
        self.assertEqual(caught.exception.status, 503)


class StubProvider:
    def __init__(self):
        self.calls = []
        self.invalid = False

    def analyze(self, request):
        self.calls.append("parts")
        return fixture("button_led")[0]

    def layout(self, request, plan):
        self.calls.append("layout")
        draft = fixture("button_led")[1]
        if self.invalid:
            draft["wires"][0]["to"] = "H2"
        return draft


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.provider = StubProvider()
        self.service = CircuitService(self.tmp.name, self.provider)

    def test_two_stages_and_last_valid_survives_invalid_generation(self):
        result = self.service.generate(request_for())
        self.assertEqual(self.provider.calls, ["parts", "layout"])
        self.provider.invalid = True
        with self.assertRaises(CircuitError):
            self.service.generate(request_for())
        self.assertEqual(self.service.latest("demo-button-led"), result)
        self.assertEqual(CircuitService(self.tmp.name).latest("demo-button-led"), result)

    def test_bad_inventory_stops_before_layout(self):
        request = request_for()
        request["availableParts"][1]["quantity"] = 0
        with self.assertRaises(CircuitError):
            self.service.generate(request)
        self.assertEqual(self.provider.calls, ["parts"])

    def test_session_isolation_and_traversal(self):
        self.service.demo("led", request_for())
        for session in ["another-session", "../../escape"]:
            with self.assertRaises(CircuitError):
                self.service.latest(session)

    def test_http_end_to_end_and_failure_has_no_layout(self):
        server = create_server(port=0, service=self.service)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = "http://127.0.0.1:"+str(server.server_port)
        try:
            req = urllib.request.Request(base+"/api/circuits/generate", data=json.dumps(request_for()).encode(),headers={"Content-Type":"application/json"})
            with urllib.request.urlopen(req) as response:
                result = json.load(response)
            self.assertEqual(result["source"], "openai")
            with urllib.request.urlopen(base+"/api/sessions/demo-button-led/placement") as response:
                self.assertEqual(json.load(response), result)
            self.provider.invalid = True
            with self.assertRaises(urllib.error.HTTPError) as caught:
                urllib.request.urlopen(req)
            error = json.load(caught.exception)
            self.assertEqual(caught.exception.code, 422)
            self.assertEqual(list(error), ["error"])
            with urllib.request.urlopen(base+"/") as response:
                self.assertIn(b"Circuit Lab", response.read())
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()

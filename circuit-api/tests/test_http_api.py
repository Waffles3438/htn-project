"""Router and serverless entry behaviour shared by server.py and api/index.py."""
import io
import json
import os
import tempfile
import threading
import unittest
from unittest.mock import patch

from circuit import wsgi
from circuit.http_api import handle_api_request
from circuit.service import CircuitService
from test_circuit import StubProvider
from circuit.fixtures import request_for


class Headers:
    def __init__(self, values=None):
        self.values = values or {}

    def get(self, name, default=None):
        return self.values.get(name, default)


def call(method, path, service, body=b"", headers=None, allowed_origin=None, lock=None):
    payload = json.dumps(body).encode() if isinstance(body, (dict, list)) else body
    values = {"Content-Type": "application/json", "Content-Length": str(len(payload))}
    values.update(headers or {})
    return handle_api_request(method, path, Headers(values), lambda n: payload, service,
                              generation_lock=lock, allowed_origin=allowed_origin)


class RouterTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.service = CircuitService(self.tmp.name, StubProvider())

    def status(self, result):
        return result[0]

    def test_read_endpoints(self):
        with patch.dict(os.environ, {}, clear=True):
            status, health, _ = call("GET", "/api/health", self.service)
            self.assertEqual(status, 200)
            self.assertTrue(health["ok"])
            self.assertEqual(health["provider"], "openai")
            self.assertFalse(health["liveConfigured"])
            self.assertEqual(health["breadboardModel"], "demo_breadboard_v1")
        status, kit, _ = call("GET", "/api/kit", self.service)
        self.assertEqual(status, 200)
        self.assertTrue(any(p["type"] == "jumper_wire" for p in kit["availableParts"]))
        status, board, _ = call("GET", "/api/breadboards/demo_breadboard_v1", self.service)
        self.assertEqual(status, 200)
        self.assertEqual(board["id"], "BB1")
        self.assertEqual(len(board["holes"]), 830)
        status, schema, _ = call("GET", "/api/schema/placement", self.service)
        self.assertEqual(status, 200)
        self.assertIn("oneOf", schema)

    def test_unknown_and_malformed_routes(self):
        self.assertEqual(self.status(call("GET", "/api/nope", self.service)), 404)
        self.assertEqual(self.status(call("GET", "/api/sessions/x/other", self.service)), 404)
        self.assertEqual(self.status(call("PUT", "/api/circuits/generate", self.service)), 404)

    def test_session_latest_and_validation(self):
        self.service.demo("led", request_for())
        status, placement, _ = call("GET", "/api/sessions/demo-button-led/placement", self.service)
        self.assertEqual(status, 200)
        self.assertEqual(placement["version"], 3)
        self.assertEqual(self.status(call("GET", "/api/sessions/another-session/placement", self.service)), 404)
        self.assertEqual(self.status(call("GET", "/api/sessions/../../escape/placement", self.service)), 404)

    def test_prompt_only_generate_uses_default_kit(self):
        status, placement, _ = call("POST", "/api/circuits/generate", self.service,
                                    {"prompt": "Turn on a red LED"})
        self.assertEqual(status, 200)
        self.assertEqual(placement["version"], 3)
        self.assertEqual(placement["sessionId"], "android")
        self.assertEqual(placement["source"], "openai")

    def test_generate_with_full_request_and_demo(self):
        status, placement, _ = call("POST", "/api/circuits/generate", self.service, request_for())
        self.assertEqual(status, 200)
        self.assertEqual(placement["sessionId"], "demo-button-led")
        status, demo, _ = call("POST", "/api/circuits/demo/button_led", self.service, {})
        self.assertEqual(status, 200)
        self.assertEqual(demo["source"], "fixture")

    def test_failed_generation_returns_error_without_layout(self):
        self.service.provider.invalid = True
        status, payload, _ = call("POST", "/api/circuits/generate", self.service, request_for())
        self.assertEqual(status, 422)
        self.assertEqual(list(payload), ["error"])
        self.assertEqual(payload["error"]["code"], "POWER_SHORT")
        self.assertNotIn("circuit", payload)

    def test_bad_requests(self):
        self.assertEqual(self.status(call("POST", "/api/circuits/generate", self.service, b"{}")), 400)
        self.assertEqual(self.status(call("POST", "/api/circuits/generate", self.service, b"not json")), 400)
        self.assertEqual(self.status(call("POST", "/api/circuits/generate", self.service, b"")), 400)
        self.assertEqual(self.status(call("POST", "/api/circuits/generate", self.service, b"x" * 40000)), 400)
        self.assertEqual(self.status(call("POST", "/api/circuits/generate", self.service,
                                          request_for(), headers={"Content-Type": "text/html"})), 415)
        status, payload, _ = call("POST", "/api/circuits/generate", self.service, {"prompt": "ab"})
        self.assertEqual(status, 400)
        self.assertEqual(payload["error"]["code"], "INVALID_REQUEST")

    def test_generation_is_serialized_per_host(self):
        lock = threading.Lock()
        lock.acquire()
        self.addCleanup(lock.release)
        status, payload, _ = call("POST", "/api/circuits/generate", self.service, request_for(), lock=lock)
        self.assertEqual(status, 429)
        self.assertEqual(payload["error"]["code"], "BUSY")

    def test_cross_origin_requests(self):
        headers = {"Origin": "http://evil.example", "Host": "127.0.0.1:8000"}
        self.assertEqual(self.status(call("POST", "/api/circuits/generate", self.service,
                                          request_for(), headers=headers)), 403)
        trusted = "https://circuit-xr.example"
        headers = {"Origin": trusted, "Host": "127.0.0.1:8000"}
        status, _, extra = call("POST", "/api/circuits/generate", self.service,
                                request_for(), headers=headers, allowed_origin=trusted)
        self.assertEqual(status, 200)
        self.assertEqual(extra.get("Access-Control-Allow-Origin"), trusted)
        status, _, extra = call("OPTIONS", "/api/circuits/generate", self.service,
                                headers=headers, allowed_origin=trusted)
        self.assertEqual(status, 204)
        self.assertIn("Access-Control-Allow-Methods", extra)
        self.assertEqual(self.status(call("OPTIONS", "/api/circuits/generate", self.service, headers=headers)), 403)
        status, _, extra = call("GET", "/api/health", self.service)
        self.assertEqual(status, 200)
        self.assertNotIn("Access-Control-Allow-Origin", extra)


def run_wsgi(method, path, body=b"", headers=None):
    environ = {"REQUEST_METHOD": method, "PATH_INFO": path,
               "wsgi.input": io.BytesIO(body), "SERVER_NAME": "test", "SERVER_PORT": "80",
               "CONTENT_LENGTH": str(len(body))}
    for key, value in (headers or {}).items():
        if key.lower() in ("content-type", "content-length"):
            environ[key.lower().replace("-", "_").upper()] = value
        else:
            environ["HTTP_" + key.upper().replace("-", "_")] = value
    captured = {}

    def start_response(status, response_headers):
        captured["status"] = status
        captured["headers"] = dict(response_headers)

    data = b"".join(wsgi.app(environ, start_response))
    return captured, data


class WsgiTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.tmp = tmp
        self.addCleanup(tmp.cleanup)
        self.environ = patch.dict(os.environ, {"CIRCUIT_SESSION_DIR": tmp.name})
        self.environ.start()
        self.addCleanup(self.environ.stop)
        self.addCleanup(setattr, wsgi, "_service", None)
        wsgi._service = CircuitService(data_dir=tmp.name, provider=StubProvider())

    def test_health_and_generate_round_trip(self):
        captured, data = run_wsgi("GET", "/api/health")
        self.assertEqual(captured["status"], "200 OK")
        self.assertEqual(captured["headers"]["Content-Type"], "application/json; charset=utf-8")
        self.assertEqual(captured["headers"]["Cache-Control"], "no-store")
        self.assertTrue(json.loads(data)["ok"])
        body = json.dumps({"prompt": "Turn on a red LED"}).encode()
        captured, data = run_wsgi("POST", "/api/circuits/generate", body,
                                  {"Content-Type": "application/json"})
        self.assertEqual(captured["status"], "200 OK")
        placement = json.loads(data)
        self.assertEqual(placement["version"], 3)
        self.assertEqual(wsgi.get_service().latest("android")["title"], placement["title"])

    def test_errors_over_wsgi(self):
        captured, data = run_wsgi("POST", "/api/circuits/generate", b"not json",
                                  {"Content-Type": "application/json"})
        self.assertEqual(captured["status"], "400 Bad Request")
        self.assertEqual(json.loads(data)["error"]["code"], "INVALID_JSON")
        captured, data = run_wsgi("GET", "/api/nope")
        self.assertEqual(captured["status"], "404 Not Found")
        self.assertEqual(json.loads(data)["error"]["code"], "NOT_FOUND")


if __name__ == "__main__":
    unittest.main()

class StatelessHostTests(unittest.TestCase):
    def test_stateless_generation_returns_circuit_without_disk_or_polling(self):
        with tempfile.TemporaryDirectory() as directory:
            target = os.path.join(directory, "must-not-be-created")
            service = CircuitService(data_dir=target, provider=StubProvider(), persist=False)
            status, placement, _ = call("POST", "/api/circuits/generate", service, {"prompt": "Turn on a red LED"})
            self.assertEqual(status, 200)
            self.assertTrue(placement["validation"]["valid"])
            self.assertFalse(os.path.exists(target))
            self.assertEqual(call("GET", "/api/sessions/android/placement", service)[0], 404)

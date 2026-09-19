"""Small local-Wi-Fi demo server. Run: python server.py"""
import json
import os
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse
from circuit.board import MODEL, default_inventory, hole_map
from circuit.contracts import ROOT, PLACEMENT
from circuit.service import CircuitService
from circuit.provider import provider_settings
from circuit.validation import CircuitError


def load_env():
    path = ROOT / ".env"
    if path.is_file():
        for line in path.read_text().splitlines():
            if line.strip() and not line.lstrip().startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                os.environ.setdefault(key.strip(), value.strip().strip("\"'"))


def create_server(host="127.0.0.1", port=8000, service=None):
    service = service or CircuitService()
    # Serialize generation to keep session writes ordered and avoid accidental repeated API calls.
    generation_slot = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def reply(self, status, body, content_type="application/json; charset=utf-8"):
            encoded = json.dumps(body, allow_nan=False).encode() if not isinstance(body, bytes) else body
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'")
            self.end_headers()
            self.wfile.write(encoded)

        def error(self, error):
            self.reply(error.status, {"error": {"code": error.code, "message": error.message, "details": error.details}})

        def do_GET(self):
            path = urlparse(self.path).path
            try:
                static = {"/": ("index.html", "text/html"), "/app.js": ("app.js", "text/javascript"), "/style.css": ("style.css", "text/css")}
                if path in static:
                    name, kind = static[path]
                    return self.reply(200, (ROOT / "static" / name).read_bytes(), kind + "; charset=utf-8")
                if path == "/api/health":
                    return self.reply(200, {"ok": True, **provider_settings(), "breadboardModel": MODEL})
                if path == "/api/kit":
                    return self.reply(200, {"breadboardModel": MODEL, "availableParts": default_inventory()})
                if path == "/api/breadboards/" + MODEL:
                    return self.reply(200, hole_map())
                if path == "/api/schema/placement":
                    return self.reply(200, PLACEMENT)
                segments = path.strip("/").split("/")
                if len(segments) == 4 and segments[:2] == ["api", "sessions"] and segments[3] == "placement":
                    return self.reply(200, service.latest(segments[2]))
                raise CircuitError("NOT_FOUND", "Route not found.", 404)
            except CircuitError as error:
                self.error(error)

        def do_POST(self):
            acquired = False
            try:
                # Reject cross-origin form/API requests; this is a local same-origin UI.
                origin = self.headers.get("Origin")
                if origin and origin != "http://" + self.headers.get("Host", ""):
                    raise CircuitError("ORIGIN_REJECTED", "Use the control page on this server.", 403)
                if self.headers.get("Content-Type", "").split(";")[0] != "application/json":
                    raise CircuitError("CONTENT_TYPE", "Send application/json.", 415)
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                except ValueError:
                    raise CircuitError("INVALID_BODY", "Invalid content length.", 400)
                if not 0 < length <= 32768:
                    raise CircuitError("INVALID_BODY", "Send a JSON body of at most 32 KB.", 400)
                self.connection.settimeout(10)
                try:
                    request = json.loads(self.rfile.read(length), parse_constant=lambda x: (_ for _ in ()).throw(ValueError(x)))
                except (ValueError, UnicodeDecodeError, socket.timeout):
                    raise CircuitError("INVALID_JSON", "Request is not valid JSON.", 400)
                acquired = generation_slot.acquire(blocking=False)
                if not acquired:
                    raise CircuitError("BUSY", "A circuit is already being generated. Try again when it finishes.", 429)
                path = urlparse(self.path).path
                if path == "/api/circuits/generate":
                    result = service.generate(request)
                elif path == "/api/circuits/analyze":
                    result = service.analyze(request)
                elif path.startswith("/api/circuits/demo/"):
                    result = service.demo(path.rsplit("/", 1)[1], request)
                else:
                    raise CircuitError("NOT_FOUND", "Route not found.", 404)
                self.reply(200, result)
            except CircuitError as error:
                self.error(error)
            except (BrokenPipeError, ConnectionResetError):
                pass
            except Exception as error:
                print("Request failed: " + type(error).__name__, flush=True)
                self.error(CircuitError("INTERNAL_ERROR", "The request failed; no new layout is available.", 500))
            finally:
                if acquired:
                    generation_slot.release()

    return ThreadingHTTPServer((host, port), Handler)


if __name__ == "__main__":
    load_env()
    host, port = os.environ.get("HOST", "127.0.0.1"), int(os.environ.get("PORT", "8000"))
    server = create_server(host, port)
    print("Circuit API → http://%s:%s (Ctrl+C to stop)" % (host, port), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()

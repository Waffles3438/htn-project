"""Small local-Wi-Fi demo server. Run: python server.py"""
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse
from circuit.contracts import ROOT
from circuit.http_api import error_payload, handle_api_request
from circuit.service import CircuitService

MIME_TYPES = {
    ".html": "text/html", ".js": "text/javascript", ".css": "text/css",
    ".svg": "image/svg+xml", ".json": "application/json", ".map": "application/json",
    ".png": "image/png", ".ico": "image/x-icon", ".woff2": "font/woff2", ".txt": "text/plain",
}


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
        def serve_static(self, path):
            """Serve the built React app (web/dist) when present, else the legacy static/ page."""
            dist = ROOT / "web" / "dist"
            if (dist / "index.html").is_file():
                if path == "/":
                    self.reply(200, (dist / "index.html").read_bytes(), "text/html; charset=utf-8")
                    return True
                candidate = (dist / path.lstrip("/")).resolve()
                if candidate.is_file() and str(candidate).startswith(str(dist.resolve()) + os.sep):
                    kind = MIME_TYPES.get(candidate.suffix)
                    if kind:
                        charset = "; charset=utf-8" if kind.startswith("text/") or kind == "application/json" else ""
                        self.reply(200, candidate.read_bytes(), kind + charset)
                        return True
                return False
            static = {"/": ("index.html", "text/html"), "/app.js": ("app.js", "text/javascript"), "/style.css": ("style.css", "text/css")}
            if path in static:
                name, kind = static[path]
                self.reply(200, (ROOT / "static" / name).read_bytes(), kind + "; charset=utf-8")
                return True
            return False

        def reply(self, status, body, content_type="application/json; charset=utf-8", extra_headers=None):
            encoded = json.dumps(body, allow_nan=False).encode() if not isinstance(body, bytes) else body
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'")
            for key, value in (extra_headers or {}).items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(encoded)

        def error(self, error):
            self.reply(error.status, error_payload(error))

        def _allowed_origin(self):
            return os.environ.get("ALLOWED_ORIGIN") or None

        def do_GET(self):
            path = urlparse(self.path).path
            if self.serve_static(path):
                return None
            status, payload, extra = handle_api_request(
                self.command, path, self.headers, None, service,
                allowed_origin=self._allowed_origin())
            self.reply(status, payload, extra_headers=extra)

        def do_POST(self):
            self.connection.settimeout(10)
            try:
                status, payload, extra = handle_api_request(
                    self.command, urlparse(self.path).path, self.headers,
                    lambda length: self.rfile.read(length), service, generation_slot,
                    allowed_origin=self._allowed_origin())
                self.reply(status, payload, extra_headers=extra)
            except (BrokenPipeError, ConnectionResetError):
                pass

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

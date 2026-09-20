"""WSGI entry: Android consumes the response directly. Vercel never persists sessions."""
import json
import os
import threading
from http.client import responses as HTTP_REASONS
from urllib.parse import urlparse

from .http_api import handle_api_request
from .service import CircuitService

_lock = threading.Lock()
_service = None


def get_service():
    global _service
    if _service is None:
        _service = CircuitService(data_dir=os.environ.get("CIRCUIT_SESSION_DIR"), persist=not bool(os.environ.get("VERCEL")))
    return _service


class _EnvironHeaders:
    """Minimal .get mapping over WSGI environ so the shared router can read headers."""

    def __init__(self, environ):
        self.environ = environ

    def get(self, name, default=None):
        key = name.upper().replace("-", "_")
        if key in ("CONTENT_TYPE", "CONTENT_LENGTH"):
            return self.environ.get(key, default)
        return self.environ.get("HTTP_" + key, default)


def app(environ, start_response):
    method = (environ.get("REQUEST_METHOD") or "GET").upper()
    path = urlparse(environ.get("PATH_INFO") or "/").path
    status, payload, extra = handle_api_request(
        method, path, _EnvironHeaders(environ),
        lambda expected: environ["wsgi.input"].read(expected),
        get_service(), generation_lock=_lock,
        allowed_origin=os.environ.get("ALLOWED_ORIGIN") or None)
    body = [] if payload is None else [json.dumps(payload, allow_nan=False).encode()]
    headers = list(extra.items())
    if payload is not None:
        headers += [("Content-Type", "application/json; charset=utf-8"),
                    ("Cache-Control", "no-store"),
                    ("X-Content-Type-Options", "nosniff"),
                    ("Content-Length", str(len(body[0])))]
    start_response("%d %s" % (status, HTTP_REASONS.get(status, "OK")), headers)
    return body

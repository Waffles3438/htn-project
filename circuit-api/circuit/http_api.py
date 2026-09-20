"""API routing shared by the local demo server (server.py) and the serverless deployment (api/index.py).

One router, two hosts: server.py adds static files, persistent session storage and socket
timeouts; the serverless entry point is stateless. Payloads, error codes and status numbers
are identical on both hosts. The Android XR app and Unity consume the same responses.
"""
import json
from . import contracts
from .board import MODEL, default_inventory, hole_map
from .provider import provider_settings
from .validation import CircuitError

MAX_BODY_BYTES = 32768


def error_payload(error):
    return {"error": {"code": error.code, "message": error.message, "details": error.details}}


def internal_error_payload():
    return {"error": {"code": "INTERNAL_ERROR",
                      "message": "The request failed; no new layout is available.", "details": []}}


def _cors_headers(allowed_origin, origin):
    """Cross-origin browser debugging only; the Android app sends no Origin header."""
    if allowed_origin and origin and origin == allowed_origin:
        return {"Access-Control-Allow-Origin": allowed_origin, "Vary": "Origin"}
    return {}


def handle_api_request(method, path, headers, read_body, service, generation_lock=None, allowed_origin=None):
    """Route one request and return (status, payload, extra_headers). Never raises.

    headers: mapping with .get (HTTPMessage or WSGI environ wrapper). read_body(length) is
    called only for POST; transport errors while reading it are reported as INVALID_JSON,
    matching the historical server behaviour.
    """
    origin = headers.get("Origin")
    try:
        if method == "OPTIONS":
            if allowed_origin and origin == allowed_origin:
                return 204, None, {"Access-Control-Allow-Origin": allowed_origin, "Vary": "Origin",
                                   "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
                                   "Access-Control-Allow-Headers": "Content-Type"}
            raise CircuitError("ORIGIN_REJECTED", "Origin not allowed.", 403)
        if method == "GET":
            return _get(path, service, allowed_origin, origin)
        if method == "POST":
            return _post(path, headers, read_body, service, generation_lock, allowed_origin, origin)
        raise CircuitError("NOT_FOUND", "Route not found.", 404)
    except CircuitError as error:
        return error.status, error_payload(error), _cors_headers(allowed_origin, origin)
    except Exception:
        import traceback
        traceback.print_exc()
        return 500, internal_error_payload(), _cors_headers(allowed_origin, origin)


def _get(path, service, allowed_origin, origin):
    if path == "/api/health":
        return 200, {"ok": True, **provider_settings(), "breadboardModel": MODEL}, _cors_headers(allowed_origin, origin)
    if path == "/api/kit":
        return 200, {"breadboardModel": MODEL, "availableParts": default_inventory()}, _cors_headers(allowed_origin, origin)
    if path == "/api/breadboards/" + MODEL:
        return 200, hole_map(), _cors_headers(allowed_origin, origin)
    if path == "/api/schema/placement":
        return 200, contracts.PLACEMENT, _cors_headers(allowed_origin, origin)
    segments = path.strip("/").split("/")
    if len(segments) == 4 and segments[:2] == ["api", "sessions"] and segments[3] == "placement":
        return 200, service.latest(segments[2]), _cors_headers(allowed_origin, origin)
    raise CircuitError("NOT_FOUND", "Route not found.", 404)


def _post(path, headers, read_body, service, generation_lock, allowed_origin, origin):
    # Same-origin requests (local control page) stay allowed without configuration;
    # a deployment can open the API to one additional browser origin via ALLOWED_ORIGIN.
    if origin:
        same_host = origin == "http://" + (headers.get("Host") or "")
        if not (same_host or (allowed_origin and origin == allowed_origin)):
            raise CircuitError("ORIGIN_REJECTED", "Use the control page on this server.", 403)
    if headers.get("Content-Type", "").split(";")[0] != "application/json":
        raise CircuitError("CONTENT_TYPE", "Send application/json.", 415)
    try:
        length = int(headers.get("Content-Length", "0"))
    except ValueError:
        raise CircuitError("INVALID_BODY", "Invalid content length.", 400)
    if not 0 < length <= MAX_BODY_BYTES:
        raise CircuitError("INVALID_BODY", "Send a JSON body of at most 32 KB.", 400)
    try:
        body = read_body(length) if read_body else b""
        request = json.loads(body, parse_constant=lambda x: (_ for _ in ()).throw(ValueError(x)))
    except (ValueError, OSError):
        raise CircuitError("INVALID_JSON", "Request is not valid JSON.", 400)
    acquired = False
    if generation_lock is not None:
        acquired = generation_lock.acquire(blocking=False)
        if not acquired:
            raise CircuitError("BUSY", "A circuit is already being generated. Try again when it finishes.", 429)
    try:
        if path == "/api/circuits/generate":
            return 200, service.generate(request), _cors_headers(allowed_origin, origin)
        if path == "/api/circuits/analyze":
            return 200, service.analyze(request), _cors_headers(allowed_origin, origin)
        if path.startswith("/api/circuits/demo/"):
            return 200, service.demo(path.rsplit("/", 1)[1], request), _cors_headers(allowed_origin, origin)
        raise CircuitError("NOT_FOUND", "Route not found.", 404)
    finally:
        if acquired:
            generation_lock.release()

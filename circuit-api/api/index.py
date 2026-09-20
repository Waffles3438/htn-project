"""Vercel Python entry point (https://vercel.com/docs/functions/runtimes/python/api-directory).

Exposes the shared router as a WSGI app; Vercel Python preserves the original request path.
"""
import os
import sys

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from circuit.wsgi import app  # noqa: E402

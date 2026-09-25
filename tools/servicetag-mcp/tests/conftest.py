"""A stand-in for the phone: a stdlib HTTP server on 127.0.0.1, and no device anywhere.

`http.server` is in the standard library, so the fake API costs no dependency, and it speaks the
same HTTP/1.1 the app's own hand-rolled listener speaks. Every request it receives is recorded, so
a test asserts on the method, the path, the headers and the body the tool actually sent.
"""

from __future__ import annotations

import json
import threading
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from servicetag_mcp import client as client_module
from servicetag_mcp import server as server_module


STATUS_1_4: dict = {
    "appVersion": "1.4.0",
    "apiVersion": 1,
    "schemaVersion": 8,
    "backupFormatVersion": 8,
    "counts": {},
}
"""What the fake answers `GET /v1/status` with unless a test says otherwise: a ServiceTag 1.4.0 app.
Every write tool reads `schemaVersion` once per pairing and refuses below 8, and that read is
recorded like any other request."""


@dataclass
class Recorded:
    method: str
    path: str
    headers: dict[str, str]
    body: bytes


@dataclass
class FakeApi:
    url: str
    requests: list[Recorded] = field(default_factory=list)
    replies: dict[tuple[str, str], tuple[int, bytes]] = field(default_factory=dict)

    def reply(self, method: str, path: str, status: int, body: object) -> None:
        """What to answer for one (method, path). Anything unset answers 200 `{}`."""
        raw = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.replies[(method, path)] = (status, raw)

    def last(self) -> Recorded:
        assert self.requests, "the tool sent no request at all"
        return self.requests[-1]


@pytest.fixture
def api(monkeypatch: pytest.MonkeyPatch):
    state: dict[str, FakeApi] = {}

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args: object) -> None:  # keep pytest output clean
            return

        def _handle(self) -> None:
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else b""
            fake = state["api"]
            fake.requests.append(
                Recorded(self.command, self.path, dict(self.headers.items()), body)
            )
            status, payload = fake.replies.get((self.command, self.path), (200, b"{}"))
            self.send_response(status)
            if payload:
                self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            if payload:
                self.wfile.write(payload)

        do_GET = _handle
        do_POST = _handle
        do_PATCH = _handle
        do_DELETE = _handle

    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    fake = FakeApi(url=f"http://127.0.0.1:{httpd.server_address[1]}")
    fake.reply("GET", "/v1/status", 200, STATUS_1_4)
    state["api"] = fake
    # `serve_forever`'s default `poll_interval` is 0.5s, and `shutdown()` blocks for up to one
    # poll before returning — across ~50 fixture teardowns that is most of the suite's wall time
    # for zero extra proof. A tighter poll costs nothing this fixture asserts on.
    thread = threading.Thread(target=httpd.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True)
    thread.start()

    # An explicit base URL is the documented signal that someone else owns the transport, so the
    # client never shells out to adb. `monkeypatch` also guarantees no serial leaks in from the
    # developer's own environment.
    monkeypatch.setenv(client_module.BASE_URL_ENV, fake.url)
    monkeypatch.delenv(client_module.SERIAL_ENV, raising=False)
    server_module.device = client_module.Device.from_env()

    try:
        yield fake
    finally:
        httpd.shutdown()
        httpd.server_close()


@pytest.fixture
def paired(api: FakeApi):
    """The fake API, with the tools already holding a pairing code."""
    server_module.pair("ABCD2345")
    return api

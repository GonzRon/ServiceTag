"""The HTTP side: the token, the error mapping, and the one rule that keeps adb out of the tests.

These drive `Device.request()`/`Device.ensure_forward()` directly, not through a tool function:
`server.py`'s tools now convert every one of `client.py`'s exceptions into a `ToolError` at the
boundary (review finding 2), so what belongs here is `client.py`'s own mapping, independent of that
conversion. `tests/test_sdk_boundary.py` covers the boundary itself, through `MCPServer.call_tool`.
"""

from __future__ import annotations

import subprocess

import httpx
import pytest

from servicetag_mcp import server as server_module
from servicetag_mcp.client import ApiError, Device, NotPaired

_FAKE_SERIAL = "R58N90FAKE1"


def test_a_request_carries_the_bearer_token(paired) -> None:
    server_module.device.request("GET", "/v1/status")
    sent = paired.last()
    assert sent.method == "GET"
    assert sent.path == "/v1/status"
    assert sent.headers["Authorization"] == "Bearer ABCD2345"


def test_an_unpaired_client_refuses_to_call_and_says_where_the_code_is(api) -> None:
    with pytest.raises(NotPaired) as raised:
        server_module.device.request("GET", "/v1/status")
    assert "Developer API" in str(raised.value)
    assert api.requests == [], "an unpaired client must not reach the phone at all"


def test_a_401_is_reported_as_a_stale_pairing(paired) -> None:
    paired.reply("GET", "/v1/status", 401, b"")
    with pytest.raises(NotPaired) as raised:
        server_module.device.request("GET", "/v1/status")
    assert "every time the screen opens" in str(raised.value)


def test_an_error_body_becomes_an_ApiError_with_its_problems(paired) -> None:
    paired.reply(
        "POST",
        "/v1/assets",
        422,
        {"error": {"code": "asset_validation", "message": "the asset was refused",
                   "problems": ["NameRequired"]}},
    )
    with pytest.raises(ApiError) as raised:
        server_module.device.request(
            "POST", "/v1/assets", json_body={"name": "  "}, content_type="application/json"
        )
    assert raised.value.status == 422
    assert raised.value.code == "asset_validation"
    assert raised.value.problems == ["NameRequired"]


def test_an_error_with_no_json_body_still_raises_something_readable(paired) -> None:
    paired.reply("GET", "/v1/status", 500, b"not json")
    with pytest.raises(ApiError) as raised:
        server_module.device.request("GET", "/v1/status")
    assert raised.value.status == 500
    assert "not json" in raised.value.message


def test_a_413_with_an_empty_body_gets_a_plain_message_not_a_blank_one(paired) -> None:
    """R2: the app's cap refusal has a zero-byte body by design (`HttpWire.kt`); before this fix
    the message was the literal empty string, so the delivered text read as `413 unknown: `."""
    paired.reply("POST", "/v1/assets", 413, b"")
    with pytest.raises(ApiError) as raised:
        server_module.device.request(
            "POST", "/v1/assets", json_body={"name": "x"}, content_type="application/json"
        )
    assert raised.value.status == 413
    assert raised.value.message == "the payload is larger than the API accepts"


def test_an_empty_body_on_another_status_names_just_the_status(paired) -> None:
    paired.reply("GET", "/v1/status", 409, b"")
    with pytest.raises(ApiError) as raised:
        server_module.device.request("GET", "/v1/status")
    assert raised.value.status == 409
    assert raised.value.message == "the phone answered 409 with no body"


def test_a_204_is_an_empty_result(paired) -> None:
    paired.reply("DELETE", "/v1/events/e1", 204, b"")
    assert server_module.device.request("DELETE", "/v1/events/e1") == {}


def test_an_explicit_base_url_means_no_adb_forward(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("SERVICETAG_API_BASE_URL", "http://127.0.0.1:9")
    monkeypatch.setenv("SERVICETAG_ADB_SERIAL", "whatever")
    device = Device.from_env()
    assert device.forwarded is True
    assert device.owns_forward is False
    # Would raise if it tried to run anything: there is no adb on this path.
    monkeypatch.setattr(
        "subprocess.run", lambda *a, **k: pytest.fail("adb must not run with an explicit base URL")
    )
    device.ensure_forward()


def test_no_serial_and_no_base_url_is_a_clear_refusal(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("SERVICETAG_API_BASE_URL", raising=False)
    monkeypatch.delenv("SERVICETAG_ADB_SERIAL", raising=False)
    device = Device.from_env()
    assert device.base_url == "http://127.0.0.1:17337"
    with pytest.raises(RuntimeError) as raised:
        device.ensure_forward()
    assert "SERVICETAG_ADB_SERIAL" in str(raised.value)


# --- finding 9: the forward's happy path, its once-only caching, its invalidation, and the
# serial-free wrapping of a CalledProcessError / TimeoutExpired -----------------------------------


def _adb_device(monkeypatch: pytest.MonkeyPatch) -> Device:
    monkeypatch.delenv("SERVICETAG_API_BASE_URL", raising=False)
    monkeypatch.setenv("SERVICETAG_ADB_SERIAL", _FAKE_SERIAL)
    return Device.from_env()


def _assert_serial_free(exc: BaseException) -> None:
    chain: list[str] = []
    current: BaseException | None = exc
    while current is not None:
        chain.append(str(current))
        chain.append(repr(current))
        current = current.__cause__
    assert not any(_FAKE_SERIAL in text for text in chain), chain


def test_the_forward_runs_once_with_the_right_argv_and_is_cached(monkeypatch: pytest.MonkeyPatch) -> None:
    device = _adb_device(monkeypatch)
    calls: list[list[str]] = []

    def fake_run(argv, **kwargs):
        calls.append(argv)
        return subprocess.CompletedProcess(argv, 0, stdout="", stderr="")

    monkeypatch.setattr(subprocess, "run", fake_run)
    device.ensure_forward()
    device.ensure_forward()  # cached: must not run adb a second time
    assert calls == [["adb", "-s", _FAKE_SERIAL, "forward", "tcp:17337", "tcp:17337"]]


def test_a_failed_forward_does_not_leak_the_serial(monkeypatch: pytest.MonkeyPatch) -> None:
    device = _adb_device(monkeypatch)

    def fake_run(argv, **kwargs):
        raise subprocess.CalledProcessError(1, argv, output="", stderr="")

    monkeypatch.setattr(subprocess, "run", fake_run)
    with pytest.raises(RuntimeError) as raised:
        device.ensure_forward()
    _assert_serial_free(raised.value)


def test_a_forward_that_hangs_is_a_serial_free_timeout(monkeypatch: pytest.MonkeyPatch) -> None:
    device = _adb_device(monkeypatch)

    def fake_run(argv, **kwargs):
        raise subprocess.TimeoutExpired(cmd=argv, timeout=10)

    monkeypatch.setattr(subprocess, "run", fake_run)
    with pytest.raises(RuntimeError) as raised:
        device.ensure_forward()
    _assert_serial_free(raised.value)


def test_a_failed_forward_is_retried_on_the_next_call(monkeypatch: pytest.MonkeyPatch) -> None:
    device = _adb_device(monkeypatch)
    attempts = {"n": 0}

    def fake_run(argv, **kwargs):
        attempts["n"] += 1
        if attempts["n"] == 1:
            raise subprocess.CalledProcessError(1, argv)
        return subprocess.CompletedProcess(argv, 0, stdout="", stderr="")

    monkeypatch.setattr(subprocess, "run", fake_run)
    with pytest.raises(RuntimeError):
        device.ensure_forward()
    device.ensure_forward()
    assert attempts["n"] == 2


# --- finding 5 & 7: a dropped forward is re-established and the request retried once; a refused
# connection this client does not own a forward for gives a clear message, not a raw transport
# error ---------------------------------------------------------------------------------------


def test_a_dropped_forward_is_re_established_and_the_request_retried(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    device = _adb_device(monkeypatch)
    device.token = "ABCD2345"
    forward_calls: list[list[str]] = []

    def fake_run(argv, **kwargs):
        forward_calls.append(argv)
        return subprocess.CompletedProcess(argv, 0, stdout="", stderr="")

    monkeypatch.setattr(subprocess, "run", fake_run)

    attempts = {"n": 0}

    def fake_request(method, url, **kwargs):
        attempts["n"] += 1
        request = httpx.Request(method, url)
        if attempts["n"] == 1:
            raise httpx.ConnectError("refused", request=request)
        return httpx.Response(200, request=request, json={"ok": True})

    monkeypatch.setattr(httpx, "request", fake_request)

    result = device.request("GET", "/v1/status")
    assert result == {"ok": True}
    assert attempts["n"] == 2
    assert len(forward_calls) == 2, "the forward must be re-run once before the retry"


def test_a_closed_port_gives_a_clear_message_not_a_raw_transport_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Port 1 is only privileged to *bind*, not to *connect* to, so this reaches a real refused
    # socket on loopback with no device and no adb involved. An explicit base URL means
    # `owns_forward` is False, so there is no forward of this client's own to retry.
    monkeypatch.setenv("SERVICETAG_API_BASE_URL", "http://127.0.0.1:1")
    monkeypatch.delenv("SERVICETAG_ADB_SERIAL", raising=False)
    device = Device.from_env()
    device.token = "ABCD2345"
    with pytest.raises(RuntimeError) as raised:
        device.request("GET", "/v1/status")
    assert "Developer API" in str(raised.value)


def test_a_read_timeout_gives_a_clear_message_not_a_raw_transport_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("SERVICETAG_API_BASE_URL", "http://127.0.0.1:9")
    monkeypatch.delenv("SERVICETAG_ADB_SERIAL", raising=False)
    device = Device.from_env()
    device.token = "ABCD2345"

    def fake_request(method, url, **kwargs):
        raise httpx.ReadTimeout("timed out", request=httpx.Request(method, url))

    monkeypatch.setattr(httpx, "request", fake_request)
    with pytest.raises(RuntimeError) as raised:
        device.request("GET", "/v1/status")
    assert "did not answer in time" in str(raised.value)

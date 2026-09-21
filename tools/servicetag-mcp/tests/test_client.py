"""The HTTP side: the token, the error mapping, and the one rule that keeps adb out of the tests."""

from __future__ import annotations

import pytest

from servicetag_mcp import server as server_module
from servicetag_mcp.client import ApiError, Device, NotPaired


def test_a_request_carries_the_bearer_token(paired) -> None:
    server_module.status()
    sent = paired.last()
    assert sent.method == "GET"
    assert sent.path == "/v1/status"
    assert sent.headers["Authorization"] == "Bearer ABCD2345"


def test_an_unpaired_client_refuses_to_call_and_says_where_the_code_is(api) -> None:
    with pytest.raises(NotPaired) as raised:
        server_module.status()
    assert "Developer API" in str(raised.value)
    assert api.requests == [], "an unpaired client must not reach the phone at all"


def test_a_401_is_reported_as_a_stale_pairing(paired) -> None:
    paired.reply("GET", "/v1/status", 401, b"")
    with pytest.raises(NotPaired) as raised:
        server_module.status()
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
        server_module.create_asset(name="  ")
    assert raised.value.status == 422
    assert raised.value.code == "asset_validation"
    assert raised.value.problems == ["NameRequired"]


def test_an_error_with_no_json_body_still_raises_something_readable(paired) -> None:
    paired.reply("GET", "/v1/status", 500, b"not json")
    with pytest.raises(ApiError) as raised:
        server_module.status()
    assert raised.value.status == 500


def test_a_204_is_an_empty_result(paired) -> None:
    paired.reply("DELETE", "/v1/events/e1", 204, b"")
    assert server_module.delete_event(event_id="e1") == {}


def test_an_explicit_base_url_means_no_adb_forward(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("SERVICETAG_API_BASE_URL", "http://127.0.0.1:9")
    monkeypatch.setenv("SERVICETAG_ADB_SERIAL", "whatever")
    device = Device.from_env()
    assert device.forwarded is True
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

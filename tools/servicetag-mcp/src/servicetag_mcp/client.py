"""One forward, one bearer token, one HTTP client against this phone's own loopback address.

Nothing here knows what a tool is: `server.py` owns the MCP surface and this owns the transport,
which is what lets the whole test suite point at a stdlib HTTP server and never touch a device.
"""

from __future__ import annotations

import json
import subprocess
from dataclasses import dataclass
from typing import Any

import httpx

DEFAULT_PORT = 17337
"""The port the app listens on, and the port this forwards to. Fixed by the app, not negotiated."""

BASE_URL_ENV = "SERVICETAG_API_BASE_URL"
"""Set this and the client talks to it directly and **never runs adb** — tests, or your own forward."""

SERIAL_ENV = "SERVICETAG_ADB_SERIAL"
"""The device serial `adb forward` is aimed at. From the environment or the MCP config, never a file."""

ADB_ENV = "SERVICETAG_ADB"
"""Where `adb` is, if it is not on PATH."""

MAX_IMPORT_BYTES = 4 * 1024 * 1024
"""The same ceiling `ApiRouter.kt`'s `MAX_IMPORT_BYTES` enforces on `/v1/import-merge/*`. Checked
here too so an over-cap archive is refused before it is read into memory and sent, rather than
arriving at the phone as a 413 with a body-less, near-unreadable `ApiError`."""

_TIMEOUT = 30.0
"""Every call's budget but the two import ones, which pass their own (finding 12: a full-phone
merge plans and applies inside one Room transaction and can outrun 30 seconds)."""

_CONNECTION_REFUSED = (
    "the phone is not answering on the forwarded port — open Settings > Utilities > Developer API "
    "on the phone and leave that screen open, then try again"
)
"""The listener's lifetime is the Developer API screen's (README), so this is the single most common
failure in normal use — leaving the screen, locking the phone or switching apps all stop it."""

_READ_TIMED_OUT = "the phone did not answer in time; try again"


class ApiError(RuntimeError):
    """The phone answered, and the answer was a refusal."""

    def __init__(self, status: int, code: str, message: str, problems: list[str]) -> None:
        super().__init__(f"{status} {code}: {message}")
        self.status = status
        self.code = code
        self.message = message
        self.problems = problems


class NotPaired(RuntimeError):
    """No pairing code, or one the phone no longer accepts."""


@dataclass
class Device:
    base_url: str
    serial: str | None
    adb: str
    token: str | None = None
    forwarded: bool = False
    owns_forward: bool = False
    """True when this `Device` is the one that runs `adb forward` — i.e. no `SERVICETAG_API_BASE_URL`
    override. Only then does a dropped forward get re-established (fix 5): someone else's forward, or
    the test fixture's fake server, is not this client's to repair."""

    @classmethod
    def from_env(cls) -> Device:
        import os

        override = os.environ.get(BASE_URL_ENV)
        return cls(
            base_url=override or f"http://127.0.0.1:{DEFAULT_PORT}",
            serial=os.environ.get(SERIAL_ENV),
            adb=os.environ.get(ADB_ENV, "adb"),
            # An explicit base URL means someone else owns the transport. Marking it forwarded is
            # what keeps `adb` out of the test suite entirely.
            forwarded=bool(override),
            owns_forward=not bool(override),
        )

    def ensure_forward(self) -> None:
        """`adb forward tcp:17337 tcp:17337`, once per session, lazily.

        Lazily because an editor starts this server whether or not a phone is plugged in, and a
        module that shells out at import time is a server that fails to load on a laptop.
        """
        if self.forwarded:
            return
        if not self.serial:
            raise RuntimeError(
                f"set {SERIAL_ENV} to the device serial adb should forward to "
                f"(or {BASE_URL_ENV} if you set up the forward yourself)"
            )
        try:
            subprocess.run(
                [
                    self.adb,
                    "-s",
                    self.serial,
                    "forward",
                    f"tcp:{DEFAULT_PORT}",
                    f"tcp:{DEFAULT_PORT}",
                ],
                check=True,
                capture_output=True,
                text=True,
                timeout=10,
            )
        except subprocess.TimeoutExpired:
            # `TimeoutExpired.__str__` embeds the argv, including `-s <serial>`, exactly like
            # `CalledProcessError` does below — the same `from None` severing applies (finding 6).
            raise RuntimeError(
                "adb forward did not finish within 10s: check the device is connected and authorised"
            ) from None
        except subprocess.CalledProcessError:
            # `CalledProcessError.__str__` embeds the whole argv, including `-s <serial>`, so
            # letting it escape would print the device serial in the MCP's error text. Nothing
            # about the device belongs in an error a model reads back.
            raise RuntimeError(
                "adb forward failed: check the device is connected and authorised"
            ) from None
        except FileNotFoundError:
            raise RuntimeError(
                f"adb was not found; put it on PATH or set {ADB_ENV}"
            ) from None
        self.forwarded = True

    def request(
        self,
        method: str,
        path: str,
        *,
        json_body: Any | None = None,
        content: bytes | None = None,
        content_type: str | None = None,
        report_statuses: tuple[int, ...] = (),
        timeout: httpx.Timeout | float | None = None,
    ) -> Any:
        """One call. [report_statuses] names the statuses whose body **may be data, not an error**.

        The API deliberately answers `POST /v1/import-merge/apply` with a **409 carrying the merge
        report** — the same shape a 200 carries — because "what stopped you?" is the only question a
        caller has at that point and the refusal already holds the deterministic conflict list. So
        `import_merge` passes `report_statuses=(409,)` and gets the report back. But four other 409
        rows an apply can raise (`archive_newer_format`, `store_unavailable`, `asset_cycle`, and the
        merge failures as defence in depth) answer with the ordinary `{"error": {...}}` envelope, not
        a report — so a status in [report_statuses] is only trusted as data when its body actually
        looks like one (a JSON object with no `"error"` key); otherwise it falls through to the
        ordinary error mapping below (fix 3).
        """
        if self.token is None:
            raise NotPaired(
                "call the pair tool with the code on the phone's Developer API screen first"
            )
        self.ensure_forward()
        headers = {"Authorization": f"Bearer {self.token}"}
        if content_type:
            headers["Content-Type"] = content_type
        effective_timeout = _TIMEOUT if timeout is None else timeout

        def send() -> httpx.Response:
            return httpx.request(
                method,
                f"{self.base_url}{path}",
                headers=headers,
                json=json_body,
                content=content,
                timeout=effective_timeout,
            )

        try:
            response = send()
        except httpx.ConnectError:
            # The forward is a property of the running `adb` server, not of this process: a
            # re-plug, `adb kill-server`, or the daemon restarting all drop it silently while
            # `forwarded` stays True. Re-establish once and retry — safe for a write too, because a
            # connect failure means nothing was ever sent (fix 5).
            if self.owns_forward and self.serial:
                self.forwarded = False
                self.ensure_forward()
                try:
                    response = send()
                except httpx.ConnectError:
                    raise RuntimeError(_CONNECTION_REFUSED) from None
            else:
                raise RuntimeError(_CONNECTION_REFUSED) from None
        except httpx.ConnectTimeout:
            raise RuntimeError(_CONNECTION_REFUSED) from None
        except httpx.ReadTimeout:
            raise RuntimeError(_READ_TIMED_OUT) from None
        except httpx.TransportError as exc:
            raise RuntimeError(f"could not reach the phone: {exc.__class__.__name__}") from None

        if response.status_code == 401:
            # The app answers 401 with an empty body on purpose, so this is all it can mean.
            raise NotPaired(
                "the phone refused that pairing code — it is new every time the screen opens, "
                "so read it again and call pair"
            )
        if response.status_code in report_statuses:
            payload = _safe_json(response)
            if isinstance(payload, dict) and "error" not in payload:
                return payload
            # Not a report after all (fix 3): fall through to the ordinary error mapping.
        if response.status_code >= 400:
            code, message, problems = _detail(response)
            raise ApiError(response.status_code, code, message, problems)
        if response.status_code == 204 or not response.content:
            return {}
        return response.json()


def _safe_json(response: httpx.Response) -> Any | None:
    try:
        return response.json()
    except ValueError:
        return None


def _detail(response: httpx.Response) -> tuple[str, str, list[str]]:
    """The error envelope, or something honest when the body is not one.

    A few refusals — the app's 413 chief among them — answer with a **zero-byte body by design**
    (`HttpWire.kt`'s cap and framing refusals never echo anything back), so `response.text[:200]`
    would be `""` and the message would read as `413 unknown: ` — technically true and useless.
    (R2.) Give an empty body a plain sentence instead, naming the one status this tool's own cap
    check makes reachable by name and falling back to the status alone otherwise.
    """
    try:
        error = response.json()["error"]
        return (
            str(error.get("code", "unknown")),
            str(error.get("message", "")),
            [str(p) for p in error.get("problems", [])],
        )
    except (ValueError, KeyError, TypeError, json.JSONDecodeError):
        text = response.text[:200]
        if text:
            return ("unknown", text, [])
        if response.status_code == 413:
            return ("unknown", "the payload is larger than the API accepts", [])
        return ("unknown", f"the phone answered {response.status_code} with no body", [])

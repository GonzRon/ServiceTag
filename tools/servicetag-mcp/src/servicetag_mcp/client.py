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

_TIMEOUT = 30.0


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
            )
        except subprocess.CalledProcessError as failure:
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
    ) -> Any:
        """One call. [report_statuses] names the statuses whose body is **data, not an error**.

        The API deliberately answers `POST /v1/import-merge/apply` with a **409 carrying the merge
        report** — the same shape a 200 carries — because "what stopped you?" is the only question a
        caller has at that point and the refusal already holds the deterministic conflict list. So
        `import_merge` passes `report_statuses=(409,)` and gets the report; every other call leaves
        it empty and a 409 is an `ApiError` as usual.
        """
        if self.token is None:
            raise NotPaired(
                "call the pair tool with the code on the phone's Developer API screen first"
            )
        self.ensure_forward()
        headers = {"Authorization": f"Bearer {self.token}"}
        if content_type:
            headers["Content-Type"] = content_type
        response = httpx.request(
            method,
            f"{self.base_url}{path}",
            headers=headers,
            json=json_body,
            content=content,
            timeout=_TIMEOUT,
        )
        if response.status_code == 401:
            # The app answers 401 with an empty body on purpose, so this is all it can mean.
            raise NotPaired(
                "the phone refused that pairing code — it is new every time the screen opens, "
                "so read it again and call pair"
            )
        if response.status_code in report_statuses:
            return response.json()
        if response.status_code >= 400:
            code, message, problems = _detail(response)
            raise ApiError(response.status_code, code, message, problems)
        if response.status_code == 204 or not response.content:
            return {}
        return response.json()


def _detail(response: httpx.Response) -> tuple[str, str, list[str]]:
    """The error envelope, or something honest when the body is not one."""
    try:
        error = response.json()["error"]
        return (
            str(error.get("code", "unknown")),
            str(error.get("message", "")),
            [str(p) for p in error.get("problems", [])],
        )
    except (ValueError, KeyError, TypeError, json.JSONDecodeError):
        return ("unknown", response.text[:200], [])

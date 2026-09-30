"""#92's five attachment tools (C27–C30): `list_attachments`, `get_attachment`, `update_attachment`,
`add_attachment` and `materialize_reference`.

Every one needs a phone at schema 16 and refuses an older one by name with nothing sent but the pairing's one
`/v1/status` read; a pre-#92 app cannot be told apart by schema alone, so the router's unknown-route 404
(`not_found`) is `APP_ROUTE_MISSING`, while a `no_such_asset`, `NO_SUCH_ATTACHMENT` or `NO_SUCH_REFERENCE` 404
passes through as the phone said it.

The upload streams a local file with an explicit `Content-Length` and never a chunked frame; its operation key
defaults to the asset, the file's SHA-256 and its size only; the attachment's id is derived locally with the
golden-vector function over the phone's `installationId`, and the tool's own replay check is the server's
strict rule. The save as document takes ids only, reads the reference and the asset's attachments first, has a
720 s read budget and never retries on its own.
"""

from __future__ import annotations

import base64
import hashlib
import inspect
import json
import subprocess
import threading
import time
from pathlib import Path

import httpx
import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import client as client_module
from servicetag_mcp import server as server_module

GOLDEN_IDS = Path(__file__).resolve().parents[3] / "docs" / "api" / "attachment-operation-ids.json"
"""`tests` → `servicetag-mcp` → `tools` → the repository root."""

INSTALLATION = "5f0c2a9e-81d4-4b7a-9c3e-2d6f1a8b0e47"
ASSET = "7d3e9a10-4c2b-4f8e-a1d5-6b0c9e2f3a84"
LINK = "https://manuals.example.invalid/heater/manual.pdf?token=fixture-only"

STATUS_16: dict = {
    "appVersion": "1.5.0",
    "apiVersion": 1,
    "schemaVersion": 16,
    "backupFormatVersion": 16,
    "installationId": INSTALLATION,
    "counts": {},
}

NEW_TOOLS = ("list_attachments", "get_attachment", "update_attachment", "add_attachment", "materialize_reference")


def golden_ids() -> dict:
    return json.loads(GOLDEN_IDS.read_text(encoding="utf-8"))


def attachment_row(**overrides) -> dict:
    row = {
        "id": "att-1", "assetId": ASSET, "eventId": None, "kind": "DOCUMENT", "mode": "COPY",
        "displayName": "Example Water Heater manual", "mimeType": "application/pdf", "sizeBytes": 1,
        "sha256": "0" * 64, "storageProvider": "SAF", "storageLocator": "att-1.pdf", "capturedOn": None,
        "notes": "", "createdAt": 1, "updatedAt": 1, "role": None, "sourceUri": None,
        "sourceResolvedUri": None, "sourceRetrievedAt": None, "sourceName": None,
    }
    row.update(overrides)
    return row


def reference_row(**overrides) -> dict:
    row = {
        "id": "r1", "assetId": ASSET, "uri": LINK, "displayName": "Heater manual", "kind": "WEB_URL",
        "scheme": "https", "description": "", "createdAt": 1, "updatedAt": 1,
    }
    row.update(overrides)
    return row


def headers_of(recorded) -> dict[str, str]:
    return {name.lower(): value for name, value in recorded.headers.items()}


def decoded_metadata(recorded) -> dict:
    header = headers_of(recorded)["x-servicetag-attachment"]
    assert "=" not in header, "base64url, unpadded"
    assert all(c.isalnum() or c in "-_" for c in header), "base64url's alphabet only"
    return json.loads(base64.urlsafe_b64decode(header + "=" * (-len(header) % 4)).decode("utf-8"))


def paths(api) -> list[tuple[str, str]]:
    return [(r.method, r.path) for r in api.requests]


@pytest.fixture
def phone16(paired):
    paired.reply("GET", "/v1/status", 200, STATUS_16)
    paired.reply("GET", f"/v1/assets/{ASSET}/attachments", 200, {"attachments": [], "folder": "READY"})
    return paired


@pytest.fixture
def manual(tmp_path: Path) -> Path:
    """A fictional PDF a little over two 64 KiB chunks long."""
    file = tmp_path / "manual.pdf"
    file.write_bytes(b"%PDF-1.7 Example Water Heater\n" * 5000)
    return file


def digest_of(file: Path) -> tuple[str, int]:
    data = file.read_bytes()
    return hashlib.sha256(data).hexdigest(), len(data)


def default_key(file: Path) -> str:
    sha256, size = digest_of(file)
    return hashlib.sha256(f"{ASSET}\n{sha256}\n{size}".encode("utf-8")).hexdigest()


def derived(key: str) -> str:
    return server_module._attachment_operation_id(INSTALLATION, ASSET, key)


# --- row 34: the tool list -----------------------------------------------------------------------


def test_the_five_tools_are_registered_and_guarded() -> None:
    for name in NEW_TOOLS:
        assert name in server_module.TOOL_NAMES, name
        tool = server_module.mcp._tool_manager.get_tool(name)
        assert tool is not None and tool.parameters.get("additionalProperties") is False, name


def test_the_signatures_are_c27s() -> None:
    def names(tool) -> list[str]:
        return list(inspect.signature(tool).parameters)

    assert names(server_module.list_attachments) == ["asset_id"]
    assert names(server_module.get_attachment) == ["attachment_id"]
    assert names(server_module.update_attachment) == [
        "attachment_id", "display_name", "kind", "captured_on", "notes", "role", "clear_fields",
    ]
    assert names(server_module.add_attachment) == [
        "asset_id", "file_path", "display_name", "mime_type", "kind", "role", "captured_on", "notes",
        "operation_key",
    ]
    # R92-3: ids only, never a URL.
    assert names(server_module.materialize_reference) == [
        "asset_id", "reference_id", "display_name", "kind", "role", "notes",
    ]


# --- row 36: the gates ---------------------------------------------------------------------------


def test_every_new_tool_refuses_schema_15_with_nothing_sent(paired, manual) -> None:
    paired.reply("GET", "/v1/status", 200, dict(STATUS_16, schemaVersion=15))
    calls = (
        lambda: server_module.list_attachments(asset_id=ASSET),
        lambda: server_module.get_attachment(attachment_id="att-1"),
        lambda: server_module.update_attachment(attachment_id="att-1", notes="x"),
        lambda: server_module.add_attachment(asset_id=ASSET, file_path=str(manual)),
        lambda: server_module.materialize_reference(asset_id=ASSET, reference_id="r1"),
    )
    for call in calls:
        with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD") as raised:
            call()
        assert "16" in str(raised.value)
    assert paths(paired) == [("GET", "/v1/status")], "the pairing's one status read, and nothing else"


def test_route_missing_is_APP_ROUTE_MISSING_and_no_such_asset_passes_through(phone16) -> None:
    missing = {"error": {"code": "not_found", "message": "no such route"}}
    phone16.reply("GET", f"/v1/assets/{ASSET}/attachments", 404, missing)
    with pytest.raises(ToolError, match="APP_ROUTE_MISSING") as raised:
        server_module.list_attachments(asset_id=ASSET)
    assert "list_attachments" in str(raised.value)

    phone16.reply("GET", "/v1/attachments/att-1", 404, missing)
    with pytest.raises(ToolError, match="APP_ROUTE_MISSING"):
        server_module.get_attachment(attachment_id="att-1")

    phone16.reply(
        "GET", f"/v1/assets/{ASSET}/attachments", 404,
        {"error": {"code": "no_such_asset", "message": "no such asset"}},
    )
    with pytest.raises(ToolError) as raised:
        server_module.list_attachments(asset_id=ASSET)
    assert "404 no_such_asset" in str(raised.value)
    assert "APP_ROUTE_MISSING" not in str(raised.value)

    phone16.reply(
        "GET", "/v1/attachments/att-1", 404,
        {"error": {"code": "NO_SUCH_ATTACHMENT", "message": "no such attachment"}},
    )
    with pytest.raises(ToolError) as raised:
        server_module.get_attachment(attachment_id="att-1")
    assert "404 NO_SUCH_ATTACHMENT" in str(raised.value)
    assert "APP_ROUTE_MISSING" not in str(raised.value)


# --- the reads -----------------------------------------------------------------------------------


def test_the_reads_answer_as_sent_and_mark_the_provenance_sensitive(phone16) -> None:
    listing = {"attachments": [attachment_row()], "folder": "READY"}
    phone16.reply("GET", f"/v1/assets/{ASSET}/attachments", 200, listing)
    phone16.reply("GET", "/v1/attachments/att-1", 200, {"attachment": attachment_row()})
    assert server_module.list_attachments(asset_id=ASSET) == listing
    assert server_module.get_attachment(attachment_id="att-1") == {"attachment": attachment_row()}
    assert paths(phone16)[-2:] == [("GET", f"/v1/assets/{ASSET}/attachments"), ("GET", "/v1/attachments/att-1")]
    for tool in (server_module.list_attachments, server_module.get_attachment):
        doc = inspect.getdoc(tool)
        assert "sensitive" in doc
        for key in ("sourceUri", "sourceResolvedUri", "sourceRetrievedAt", "sourceName"):
            assert key in doc, key


def test_status_says_what_the_installation_id_is() -> None:
    doc = " ".join(inspect.getdoc(server_module.status).split())
    for words in ("installationId", "opaque", "random", "device-local", "not authentication material", "never in"):
        assert words in doc, words


# --- row 38: the overlay -------------------------------------------------------------------------


def test_update_attachment_overlays_and_clears_by_name(phone16) -> None:
    stored = attachment_row(role="USER_MANUAL", capturedOn="2026-01-02", notes="kept", kind="MANUAL")
    phone16.reply("GET", "/v1/attachments/att-1", 200, {"attachment": stored})

    server_module.update_attachment(attachment_id="att-1", display_name="Heater manual")
    patch = phone16.last()
    assert (patch.method, patch.path) == ("PATCH", "/v1/attachments/att-1")
    assert json.loads(patch.body) == {
        "displayName": "Heater manual", "kind": "MANUAL", "capturedOn": "2026-01-02", "notes": "kept",
        "role": "USER_MANUAL",
    }

    server_module.update_attachment(attachment_id="att-1", clear_fields=["role", "captured_on", "notes"])
    assert json.loads(phone16.last().body) == {
        "displayName": "Example Water Heater manual", "kind": "MANUAL", "capturedOn": None, "notes": "",
        "role": None,
    }

    with pytest.raises(ToolError, match="clear_fields"):
        server_module.update_attachment(attachment_id="att-1", clear_fields=["display_name"])
    with pytest.raises(ToolError, match="clear_fields"):
        server_module.update_attachment(attachment_id="att-1", role="SERVICE_MANUAL", clear_fields=["role"])


# --- row 35: the upload --------------------------------------------------------------------------


def test_the_python_id_derivation_equals_the_golden_vectors() -> None:
    golden = golden_ids()
    assert golden["prefix"] == server_module._OPERATION_ID_PREFIX
    for vector in golden["vectors"]:
        assert server_module._attachment_operation_id(
            vector["installationId"], vector["assetId"], vector["operationKey"]
        ) == vector["attachmentId"], vector


def test_the_python_kind_inference_equals_the_golden_cases() -> None:
    for case in golden_ids()["kinds"]:
        assert server_module._infer_kind(case["contentType"]) == case["kind"], case


def test_an_upload_streams_the_file_with_content_length_and_the_header(phone16, manual) -> None:
    sha256, size = digest_of(manual)
    key = default_key(manual)
    phone16.reply("GET", f"/v1/attachments/{derived(key)}", 404, {"error": {"code": "NO_SUCH_ATTACHMENT"}})
    created = attachment_row(id=derived(key), sha256=sha256, sizeBytes=size, displayName="manual.pdf")
    phone16.reply("POST", f"/v1/assets/{ASSET}/attachments", 201, {"attachment": created})

    result = server_module.add_attachment(asset_id=ASSET, file_path=str(manual))

    assert result == {"decision": "CREATED", "attachment": created}
    assert paths(phone16) == [
        ("GET", "/v1/status"),
        ("GET", f"/v1/assets/{ASSET}/attachments"),
        ("GET", f"/v1/attachments/{derived(key)}"),
        ("POST", f"/v1/assets/{ASSET}/attachments"),
    ]
    post = phone16.last()
    headers = headers_of(post)
    assert headers["content-length"] == str(size)
    assert "transfer-encoding" not in headers
    assert headers["content-type"] == "application/pdf"
    assert post.body == manual.read_bytes()
    assert decoded_metadata(post) == {
        "operationKey": key, "displayName": "manual.pdf", "sha256": sha256, "kind": "DOCUMENT",
    }


def test_the_upload_body_is_an_iterator_of_bounded_chunks_never_the_whole_file(
    phone16, manual, monkeypatch: pytest.MonkeyPatch
) -> None:
    key = default_key(manual)
    phone16.reply("GET", f"/v1/attachments/{derived(key)}", 404, {"error": {"code": "NO_SUCH_ATTACHMENT"}})
    phone16.reply("POST", f"/v1/assets/{ASSET}/attachments", 201, {"attachment": attachment_row()})
    real = httpx.request
    seen: list[object] = []

    def spy(method, url, **kwargs):
        if method == "POST":
            seen.append(kwargs.get("content"))
        return real(method, url, **kwargs)

    monkeypatch.setattr(httpx, "request", spy)
    monkeypatch.setattr(Path, "read_bytes", lambda self: pytest.fail("the upload read a file whole"))
    server_module.add_attachment(asset_id=ASSET, file_path=str(manual))
    assert len(seen) == 1 and not isinstance(seen[0], (bytes, bytearray))


def test_the_default_key_is_the_asset_the_digest_and_the_size_only(phone16, manual) -> None:
    key = default_key(manual)
    phone16.reply("GET", f"/v1/attachments/{derived(key)}", 404, {"error": {"code": "NO_SUCH_ATTACHMENT"}})
    phone16.reply("POST", f"/v1/assets/{ASSET}/attachments", 201, {"attachment": attachment_row()})

    server_module.add_attachment(asset_id=ASSET, file_path=str(manual))
    server_module.add_attachment(
        asset_id=ASSET, file_path=str(manual), display_name="Other name", role="USER_MANUAL", kind="MANUAL",
    )
    looked_up = [path for method, path in paths(phone16) if path.startswith("/v1/attachments/")]
    assert looked_up == [f"/v1/attachments/{derived(key)}"] * 2, "a changed name, kind or role is the same key"
    posts = [r for r in phone16.requests if r.method == "POST"]
    assert [decoded_metadata(r)["operationKey"] for r in posts] == [key, key]


def test_the_id_is_derived_from_the_status_installation_id(paired, manual) -> None:
    other = "c41b7e02-9d3a-4e6f-8b15-0a2c7d9e4f61"
    paired.reply("GET", "/v1/status", 200, dict(STATUS_16, installationId=other))
    paired.reply("GET", f"/v1/assets/{ASSET}/attachments", 200, {"attachments": [], "folder": "READY"})
    key = default_key(manual)
    expected = server_module._attachment_operation_id(other, ASSET, key)
    paired.reply("GET", f"/v1/attachments/{expected}", 404, {"error": {"code": "NO_SUCH_ATTACHMENT"}})
    paired.reply("POST", f"/v1/assets/{ASSET}/attachments", 201, {"attachment": attachment_row()})
    server_module.add_attachment(asset_id=ASSET, file_path=str(manual))
    assert ("GET", f"/v1/attachments/{expected}") in paths(paired)
    assert expected != derived(key)


def test_a_matching_row_is_REPLAYED_with_no_post(phone16, manual) -> None:
    sha256, size = digest_of(manual)
    key = default_key(manual)
    row = attachment_row(id=derived(key), sha256=sha256, sizeBytes=size, displayName="manual.pdf",
                         notes="changed on the phone", capturedOn="2026-02-03")
    phone16.reply("GET", f"/v1/attachments/{derived(key)}", 200, {"attachment": row})
    result = server_module.add_attachment(asset_id=ASSET, file_path=str(manual), display_name="  manual.pdf ",
                                          notes="not compared", captured_on="2026-09-30")
    assert result == {"decision": "REPLAYED", "attachment": row}
    assert all(r.method == "GET" for r in phone16.requests)


@pytest.mark.parametrize(
    "stored",
    [
        {"displayName": "renamed on the phone"},
        {"role": "USER_MANUAL"},
        {"sha256": "1" * 64},
        {"sizeBytes": 2},
        {"assetId": "another-asset"},
        {"eventId": "e1"},
    ],
)
def test_a_differing_row_is_OPERATION_KEY_REUSED_locally_with_no_post(phone16, manual, stored) -> None:
    sha256, size = digest_of(manual)
    key = default_key(manual)
    row = attachment_row(id=derived(key), sha256=sha256, sizeBytes=size, displayName="manual.pdf")
    row.update(stored)
    phone16.reply("GET", f"/v1/attachments/{derived(key)}", 200, {"attachment": row})
    with pytest.raises(ToolError, match="OPERATION_KEY_REUSED") as raised:
        server_module.add_attachment(asset_id=ASSET, file_path=str(manual))
    assert "update_attachment" in str(raised.value)
    assert derived(key) in str(raised.value)
    assert all(r.method == "GET" for r in phone16.requests)


def test_a_row_patched_from_DOCUMENT_to_MANUAL_is_REUSED_on_a_rerun_with_no_kind(phone16, manual) -> None:
    """R92-7 strict: the kind is resolved the server's way (a PDF is DOCUMENT) and compared with the row as it
    stands now, so the original call re-run after the row's kind was changed is a conflict, not a replay."""
    sha256, size = digest_of(manual)
    key = default_key(manual)
    row = attachment_row(id=derived(key), sha256=sha256, sizeBytes=size, displayName="manual.pdf", kind="MANUAL")
    phone16.reply("GET", f"/v1/attachments/{derived(key)}", 200, {"attachment": row})
    with pytest.raises(ToolError, match="OPERATION_KEY_REUSED"):
        server_module.add_attachment(asset_id=ASSET, file_path=str(manual), kind=None)
    assert all(r.method == "GET" for r in phone16.requests)


def test_a_new_operation_key_with_the_same_file_posts(phone16, manual) -> None:
    sha256, size = digest_of(manual)
    old = attachment_row(id=derived(default_key(manual)), sha256=sha256, sizeBytes=size, displayName="manual.pdf")
    phone16.reply("GET", f"/v1/attachments/{derived(default_key(manual))}", 200, {"attachment": old})
    phone16.reply("GET", f"/v1/attachments/{derived('second-copy')}", 404, {"error": {"code": "NO_SUCH_ATTACHMENT"}})
    phone16.reply("POST", f"/v1/assets/{ASSET}/attachments", 201, {"attachment": attachment_row()})
    result = server_module.add_attachment(asset_id=ASSET, file_path=str(manual), operation_key="second-copy")
    assert result["decision"] == "CREATED"
    assert decoded_metadata(phone16.last())["operationKey"] == "second-copy"


def test_a_server_200_is_REPLAYED_and_a_server_409_is_reported(phone16, manual) -> None:
    key = default_key(manual)
    phone16.reply("GET", f"/v1/attachments/{derived(key)}", 404, {"error": {"code": "NO_SUCH_ATTACHMENT"}})
    phone16.reply("POST", f"/v1/assets/{ASSET}/attachments", 200, {"attachment": attachment_row()})
    assert server_module.add_attachment(asset_id=ASSET, file_path=str(manual))["decision"] == "REPLAYED"

    phone16.reply("POST", f"/v1/assets/{ASSET}/attachments", 409, {"error": {
        "code": "OPERATION_KEY_REUSED", "message": "this operation key was used for a different upload",
        "field": "operationKey", "problems": ["OperationKeyReused(attachmentId=att-9)"],
    }})
    with pytest.raises(ToolError, match="409 OPERATION_KEY_REUSED") as raised:
        server_module.add_attachment(asset_id=ASSET, file_path=str(manual))
    assert "att-9" in str(raised.value) and "update_attachment" in str(raised.value)


def test_the_kind_is_resolved_and_always_sent_and_a_role_only_when_given(phone16, tmp_path: Path) -> None:
    photo = tmp_path / "label.JPG"
    photo.write_bytes(b"\xff\xd8\xff fixture")
    key = hashlib.sha256(f"{ASSET}\n{hashlib.sha256(photo.read_bytes()).hexdigest()}\n{photo.stat().st_size}"
                         .encode()).hexdigest()
    phone16.reply("GET", f"/v1/attachments/{derived(key)}", 404, {"error": {"code": "NO_SUCH_ATTACHMENT"}})
    phone16.reply("POST", f"/v1/assets/{ASSET}/attachments", 201, {"attachment": attachment_row()})

    server_module.add_attachment(asset_id=ASSET, file_path=str(photo))
    first = decoded_metadata(phone16.last())
    assert first["kind"] == "PHOTO" and "role" not in first
    assert headers_of(phone16.last())["content-type"] == "image/jpeg"

    server_module.add_attachment(asset_id=ASSET, file_path=str(photo), mime_type="Application/Octet-Stream; x=1",
                                 role="PURCHASE_INVOICE_OR_RECEIPT", captured_on="2026-09-01", notes="n")
    second = decoded_metadata(phone16.last())
    assert second["kind"] == "OTHER"
    assert second["role"] == "PURCHASE_INVOICE_OR_RECEIPT"
    assert (second["capturedOn"], second["notes"]) == ("2026-09-01", "n")
    assert headers_of(phone16.last())["content-type"] == "application/octet-stream"


def _no_open(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(Path, "open", lambda self, *a, **k: pytest.fail(f"{self.name} was opened"))


@pytest.mark.parametrize(
    ("status", "listing", "words"),
    [
        (200, {"attachments": [], "folder": "NOT_CONFIGURED"}, "ATTACHMENT_STORE_NOT_CONFIGURED"),
        (200, {"attachments": [], "folder": "ACCESS_LOST"}, "store_unavailable"),
        (404, {"error": {"code": "no_such_asset", "message": "no such asset"}}, "404 no_such_asset"),
    ],
)
def test_a_missing_folder_or_asset_is_refused_before_the_file_is_opened(
    phone16, manual, monkeypatch: pytest.MonkeyPatch, status, listing, words
) -> None:
    phone16.reply("GET", f"/v1/assets/{ASSET}/attachments", status, listing)
    _no_open(monkeypatch)
    with pytest.raises(ToolError, match=words):
        server_module.add_attachment(asset_id=ASSET, file_path=str(manual))
    assert paths(phone16) == [("GET", "/v1/status"), ("GET", f"/v1/assets/{ASSET}/attachments")]


def test_a_file_over_256_MiB_is_refused_before_it_is_opened(paired, tmp_path: Path, monkeypatch) -> None:
    big = tmp_path / "big.bin"
    with big.open("wb") as handle:
        handle.truncate(client_module.MAX_ATTACHMENT_BYTES + 1)
    _no_open(monkeypatch)
    with pytest.raises(ToolError, match="256 MiB"):
        server_module.add_attachment(asset_id=ASSET, file_path=str(big))
    assert paired.requests == []
    assert client_module.MAX_ATTACHMENT_BYTES == 268_435_456


def test_the_file_is_reopened_on_the_connect_error_retry(phone16, manual, monkeypatch: pytest.MonkeyPatch) -> None:
    key = default_key(manual)
    phone16.reply("GET", f"/v1/attachments/{derived(key)}", 404, {"error": {"code": "NO_SUCH_ATTACHMENT"}})
    phone16.reply("POST", f"/v1/assets/{ASSET}/attachments", 201, {"attachment": attachment_row()})
    device = server_module.device
    device.owns_forward, device.serial = True, "fixture-serial"
    monkeypatch.setattr(subprocess, "run", lambda argv, **kw: subprocess.CompletedProcess(argv, 0, "", ""))
    real = httpx.request
    attempts = {"post": 0}

    def flaky(method, url, **kwargs):
        if method == "POST":
            attempts["post"] += 1
            if attempts["post"] == 1:
                b"".join(kwargs["content"])  # the first attempt consumes its body, then fails to connect
                raise httpx.ConnectError("refused", request=httpx.Request(method, url))
        return real(method, url, **kwargs)

    monkeypatch.setattr(httpx, "request", flaky)
    assert server_module.add_attachment(asset_id=ASSET, file_path=str(manual))["decision"] == "CREATED"
    assert attempts["post"] == 2
    assert phone16.last().body == manual.read_bytes(), "the retry's body is the file, not a spent iterator"


def test_the_upload_budget_is_300_seconds_to_read_and_write(phone16, manual, monkeypatch) -> None:
    key = default_key(manual)
    phone16.reply("GET", f"/v1/attachments/{derived(key)}", 404, {"error": {"code": "NO_SUCH_ATTACHMENT"}})
    phone16.reply("POST", f"/v1/assets/{ASSET}/attachments", 201, {"attachment": attachment_row()})
    real = httpx.request
    budgets: list[object] = []

    def spy(method, url, **kwargs):
        if method == "POST":
            budgets.append(kwargs["timeout"])
        return real(method, url, **kwargs)

    monkeypatch.setattr(httpx, "request", spy)
    server_module.add_attachment(asset_id=ASSET, file_path=str(manual))
    assert budgets == [httpx.Timeout(30.0, read=300.0, write=300.0)]


def test_one_call_is_in_flight_at_a_time(paired, monkeypatch: pytest.MonkeyPatch) -> None:
    """B2's review: while a save as document runs, the phone answers nothing else; the client never has two
    calls open at once."""
    state = {"now": 0, "most": 0}
    guard = threading.Lock()

    def slow(method, url, **kwargs):
        with guard:
            state["now"] += 1
            state["most"] = max(state["most"], state["now"])
        time.sleep(0.05)
        with guard:
            state["now"] -= 1
        return httpx.Response(200, request=httpx.Request(method, url), json={})

    monkeypatch.setattr(httpx, "request", slow)
    threads = [threading.Thread(target=server_module.device.request, args=("GET", "/v1/assets")) for _ in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert state["most"] == 1


# --- row 37: save as document ----------------------------------------------------------------------


@pytest.fixture
def with_reference(phone16):
    phone16.reply("GET", f"/v1/assets/{ASSET}/references", 200, {"references": [reference_row()]})
    return phone16


def test_a_row_saved_from_the_link_is_IDENTICAL_with_no_post(with_reference) -> None:
    saved = attachment_row(id="att-7", sourceUri=LINK)
    with_reference.reply("GET", f"/v1/assets/{ASSET}/attachments", 200, {"attachments": [saved], "folder": "READY"})
    result = server_module.materialize_reference(asset_id=ASSET, reference_id="r1")
    assert result["decision"] == "IDENTICAL"
    assert result["attachment"] == saved
    assert result["reference"] == {"id": "r1", "displayName": "Heater manual", "host": "manuals.example.invalid"}
    assert paths(with_reference) == [
        ("GET", "/v1/status"),
        ("GET", f"/v1/assets/{ASSET}/references"),
        ("GET", f"/v1/assets/{ASSET}/attachments"),
    ]


def test_a_missing_reference_is_NO_SUCH_REFERENCE_with_no_post(with_reference) -> None:
    with pytest.raises(ToolError, match="NO_SUCH_REFERENCE"):
        server_module.materialize_reference(asset_id=ASSET, reference_id="r404")
    assert all(r.method == "GET" for r in with_reference.requests)


def test_a_save_posts_the_given_keys_and_echoes_host_type_and_size(with_reference, monkeypatch) -> None:
    saved = attachment_row(id="att-8", sourceUri=LINK, mimeType="application/pdf", sizeBytes=4096)
    with_reference.reply("POST", "/v1/references/r1/materialize", 201, {"attachment": saved})
    real = httpx.request
    budgets: list[object] = []

    def spy(method, url, **kwargs):
        if method == "POST":
            budgets.append(kwargs["timeout"])
        return real(method, url, **kwargs)

    monkeypatch.setattr(httpx, "request", spy)
    result = server_module.materialize_reference(asset_id=ASSET, reference_id="r1", role="USER_MANUAL")
    assert budgets == [httpx.Timeout(30.0, read=720.0)]
    post = with_reference.last()
    assert (post.method, post.path) == ("POST", "/v1/references/r1/materialize")
    assert json.loads(post.body) == {"role": "USER_MANUAL"}
    assert result["decision"] == "CREATED"
    assert (result["host"], result["mimeType"], result["sizeBytes"]) == ("manuals.example.invalid", "application/pdf", 4096)
    assert result["reference"]["host"] == "manuals.example.invalid"
    assert LINK not in json.dumps(result["reference"]), "the host only, never the full link"

    server_module.materialize_reference(asset_id=ASSET, reference_id="r1")
    assert json.loads(with_reference.last().body) == {}


def test_already_held_is_IDENTICAL_naming_the_row(with_reference) -> None:
    with_reference.reply("POST", "/v1/references/r1/materialize", 409, {"error": {
        "code": "ATTACHMENT_ALREADY_HELD", "message": "this asset already holds these bytes",
        "problems": ["AlreadyHave(attachmentId=att-3)"],
    }})
    result = server_module.materialize_reference(asset_id=ASSET, reference_id="r1")
    assert result["decision"] == "IDENTICAL"
    assert result["attachmentId"] == "att-3"


def test_a_502_is_the_error_and_never_retried(with_reference) -> None:
    with_reference.reply("POST", "/v1/references/r1/materialize", 502, {"error": {
        "code": "FETCH_TIMED_OUT", "message": "the download was refused", "problems": ["TimedOut"],
    }})
    with pytest.raises(ToolError, match="502 FETCH_TIMED_OUT"):
        server_module.materialize_reference(asset_id=ASSET, reference_id="r1")
    assert [r.method for r in with_reference.requests].count("POST") == 1


def test_a_connect_error_is_not_retried(with_reference, monkeypatch: pytest.MonkeyPatch) -> None:
    device = server_module.device
    device.owns_forward, device.serial = True, "fixture-serial"
    monkeypatch.setattr(subprocess, "run", lambda argv, **kw: subprocess.CompletedProcess(argv, 0, "", ""))
    real = httpx.request
    posts = {"n": 0}

    def refused(method, url, **kwargs):
        if method == "POST":
            posts["n"] += 1
            raise httpx.ConnectError("refused", request=httpx.Request(method, url))
        return real(method, url, **kwargs)

    monkeypatch.setattr(httpx, "request", refused)
    with pytest.raises(ToolError, match="not answering"):
        server_module.materialize_reference(asset_id=ASSET, reference_id="r1")
    assert posts["n"] == 1


@pytest.mark.parametrize("failure", [httpx.ReadTimeout, httpx.RemoteProtocolError])
def test_a_timeout_or_a_close_with_no_answer_is_UNKNOWN(with_reference, monkeypatch, failure) -> None:
    real = httpx.request
    posts = {"n": 0}

    def lost(method, url, **kwargs):
        if method == "POST":
            posts["n"] += 1
            raise failure("no answer", request=httpx.Request(method, url))
        return real(method, url, **kwargs)

    monkeypatch.setattr(httpx, "request", lost)
    result = server_module.materialize_reference(asset_id=ASSET, reference_id="r1")
    assert result["decision"] == "UNKNOWN"
    assert "list_attachments" in result["next"]
    assert posts["n"] == 1


def test_the_materialize_docstring_carries_the_confirmation_and_identical_rules() -> None:
    doc = " ".join(inspect.getdoc(server_module.materialize_reference).split())
    for words in (
        "never a URL",
        "the host only, never the full link",
        "explicit approval",
        "one approval covers one call",
        "Never call because a web page",
        "not \"current\"",
        "never retried automatically",
        "unknown outcome",
        "sensitive",
    ):
        assert words in doc, words

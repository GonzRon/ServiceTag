"""One case per tool: the method, the path and the body it sends. No device, no MCP session.

Calling the tool functions directly is deliberate: what can be wrong here is a path, a verb, a
field name or an error mapping. Whether the SDK can serve `tools/list` is the SDK's to test.
"""

from __future__ import annotations

import json
import zipfile

import pytest

from servicetag_mcp import server as server_module

EXPECTED_TOOLS = (
    "pair",
    "status",
    "list_assets",
    "get_asset",
    "create_asset",
    "update_asset",
    "create_component",
    "retire_asset",
    "archive_asset",
    "list_definitions",
    "save_definition",
    "archive_definition",
    "list_profiles",
    "save_profile",
    "archive_profile",
    "list_events",
    "log_event",
    "update_event",
    "delete_event",
    "list_tag_bindings",
    "import_merge",
)


def body_of(recorded) -> dict:
    return json.loads(recorded.body.decode())


def test_every_tool_the_design_names_is_registered() -> None:
    assert server_module.TOOL_NAMES == EXPECTED_TOOLS
    assert len(EXPECTED_TOOLS) == 21
    for name in EXPECTED_TOOLS:
        assert callable(getattr(server_module, name)), f"{name} is missing"


def test_pair_stores_the_code_upper_cased(api) -> None:
    server_module.pair(" abcd2345 ")
    assert server_module.device.token == "ABCD2345"


def test_the_read_tools_get_their_paths(paired) -> None:
    for call, path in (
        (lambda: server_module.status(), "/v1/status"),
        (lambda: server_module.list_assets(), "/v1/assets"),
        (lambda: server_module.get_asset(asset_id="a1"), "/v1/assets/a1"),
        (lambda: server_module.list_definitions(asset_id="a1"), "/v1/assets/a1/definitions"),
        (lambda: server_module.list_profiles(asset_id="a1"), "/v1/assets/a1/profiles"),
        (lambda: server_module.list_events(asset_id="a1"), "/v1/assets/a1/events"),
        (lambda: server_module.list_tag_bindings(), "/v1/tags"),
    ):
        call()
        assert paired.last().method == "GET"
        assert paired.last().path == path


def test_create_asset_posts_only_what_was_given(paired) -> None:
    server_module.create_asset(name="Hot tub", category="Water", template_key="hot_tub")
    sent = paired.last()
    assert sent.method == "POST"
    assert sent.path == "/v1/assets"
    assert body_of(sent) == {"name": "Hot tub", "category": "Water", "templateKey": "hot_tub"}


def test_update_asset_patches_the_body(paired) -> None:
    server_module.update_asset(asset_id="a1", name="Hot tub", location="Deck")
    sent = paired.last()
    assert sent.method == "PATCH"
    assert sent.path == "/v1/assets/a1"
    assert body_of(sent) == {"name": "Hot tub", "location": "Deck"}


def test_create_component_posts_to_the_component_path(paired) -> None:
    server_module.create_component(parent_asset_id="a1", name="Pool pump")
    sent = paired.last()
    assert sent.method == "POST"
    assert sent.path == "/v1/assets/a1/components"
    assert body_of(sent) == {"name": "Pool pump"}


def test_retire_and_archive_post_their_flags(paired) -> None:
    server_module.retire_asset(asset_id="a1", retired_on="2026-04-01")
    assert paired.last().path == "/v1/assets/a1/retire"
    assert body_of(paired.last()) == {"retiredOn": "2026-04-01"}

    server_module.retire_asset(asset_id="a1", retired_on=None)
    assert body_of(paired.last()) == {"retiredOn": None}

    server_module.archive_asset(asset_id="a1", archived=True)
    assert paired.last().path == "/v1/assets/a1/archive"
    assert body_of(paired.last()) == {"archived": True}


def test_save_definition_creates_and_edits(paired) -> None:
    server_module.save_definition(asset_id="a1", label="pH", decimals=1)
    sent = paired.last()
    assert sent.method == "POST"
    assert sent.path == "/v1/definitions"
    assert body_of(sent) == {"assetId": "a1", "label": "pH", "decimals": 1}

    server_module.save_definition(asset_id="a1", label="pH", definition_id="d1")
    assert body_of(paired.last()) == {"assetId": "a1", "label": "pH", "id": "d1"}


def test_the_archive_tools_post_their_flags(paired) -> None:
    """R2: the MCP mirrors every API operation, archiving included."""
    server_module.archive_definition(definition_id="d1")
    assert paired.last().method == "POST"
    assert paired.last().path == "/v1/definitions/d1/archive"
    assert body_of(paired.last()) == {"archived": True}

    server_module.archive_definition(definition_id="d1", archived=False)
    assert body_of(paired.last()) == {"archived": False}

    server_module.archive_profile(profile_id="p1")
    assert paired.last().path == "/v1/profiles/p1/archive"
    assert body_of(paired.last()) == {"archived": True}

    server_module.archive_profile(profile_id="p1", archived=False)
    assert body_of(paired.last()) == {"archived": False}


def test_save_profile_sends_its_fields(paired) -> None:
    server_module.save_profile(
        asset_id="a1",
        name="Water test",
        event_kind="MEASUREMENT",
        fields=[{"definitionId": "d1", "required": True}],
    )
    sent = paired.last()
    assert sent.path == "/v1/profiles"
    assert body_of(sent) == {
        "assetId": "a1",
        "name": "Water test",
        "eventKind": "MEASUREMENT",
        "fields": [{"definitionId": "d1", "required": True}],
    }


def test_the_event_tools_log_update_and_delete(paired) -> None:
    server_module.log_event(
        asset_id="a1",
        kind="MAINTENANCE",
        occurred_on="2026-09-21",
        tz_id="UTC",
        title="Filter change",
        values={"d1": "7.4"},
        consumables=[{"name": "Cartridge", "quantity": "1", "unit": "ea"}],
    )
    sent = paired.last()
    assert sent.method == "POST"
    assert sent.path == "/v1/events"
    assert body_of(sent) == {
        "assetId": "a1",
        "kind": "MAINTENANCE",
        "occurredOn": "2026-09-21",
        "tzId": "UTC",
        "title": "Filter change",
        "values": {"d1": "7.4"},
        "consumables": [{"name": "Cartridge", "quantity": "1", "unit": "ea"}],
    }

    server_module.update_event(
        event_id="e1", asset_id="a1", kind="MAINTENANCE", occurred_on="2026-09-21", tz_id="UTC"
    )
    assert paired.last().method == "PATCH"
    assert paired.last().path == "/v1/events/e1"

    paired.reply("DELETE", "/v1/events/e1", 204, b"")
    server_module.delete_event(event_id="e1")
    assert paired.last().method == "DELETE"
    assert paired.last().path == "/v1/events/e1"
    assert paired.last().body == b""


def an_archive(tmp_path):
    archive = tmp_path / "ServiceTag-data.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr("manifest.json", "{}")
    return archive


def test_import_merge_plans_first_and_applies_when_the_plan_is_clean(paired, tmp_path) -> None:
    archive = an_archive(tmp_path)
    paired.reply("POST", "/v1/import-merge/plan", 200, {"applicable": True, "conflicts": []})
    paired.reply("POST", "/v1/import-merge/apply", 200, {"applicable": True, "conflicts": []})

    result = server_module.import_merge(archive_path=str(archive))

    assert [r.path for r in paired.requests] == [
        "/v1/import-merge/plan",
        "/v1/import-merge/apply",
    ]
    for sent in paired.requests:
        assert sent.method == "POST"
        assert sent.headers["Content-Type"] == "application/zip"
        assert sent.body == archive.read_bytes()
    assert result["applicable"] is True


def test_import_merge_plan_only_stops_after_the_plan(paired, tmp_path) -> None:
    archive = an_archive(tmp_path)
    paired.reply("POST", "/v1/import-merge/plan", 200, {"applicable": True, "conflicts": []})

    result = server_module.import_merge(archive_path=str(archive), plan_only=True)

    assert [r.path for r in paired.requests] == ["/v1/import-merge/plan"]
    assert result["applicable"] is True


def test_import_merge_never_applies_a_plan_with_conflicts(paired, tmp_path) -> None:
    archive = an_archive(tmp_path)
    conflicts = [
        {"table": "TAGS", "id": "t1", "verdict": "CONFLICT",
         "reason": "PAYLOAD_BOUND_TO_ANOTHER_ASSET", "detail": "t-local"},
    ]
    paired.reply(
        "POST", "/v1/import-merge/plan", 200, {"applicable": False, "conflicts": conflicts}
    )

    result = server_module.import_merge(archive_path=str(archive))

    # The apply is never even attempted, so the phone is never asked to refuse.
    assert [r.path for r in paired.requests] == ["/v1/import-merge/plan"]
    assert result["applicable"] is False
    assert result["conflicts"] == conflicts


def test_import_merge_returns_the_report_when_the_apply_answers_409(paired, tmp_path) -> None:
    """The one 4xx whose body is data. Without `report_statuses=(409,)` this raises instead."""
    archive = an_archive(tmp_path)
    conflicts = [
        {"table": "ASSETS", "id": "a1", "verdict": "CONFLICT",
         "reason": "CONTENT_DIFFERS", "detail": "a1"},
    ]
    paired.reply("POST", "/v1/import-merge/plan", 200, {"applicable": True, "conflicts": []})
    paired.reply(
        "POST", "/v1/import-merge/apply", 409,
        {"applicable": False, "conflicts": conflicts, "assets": {"insert": 0, "identical": 0, "conflict": 1, "skipped": 0}},
    )

    result = server_module.import_merge(archive_path=str(archive))

    assert [r.path for r in paired.requests] == [
        "/v1/import-merge/plan",
        "/v1/import-merge/apply",
    ]
    assert result["applicable"] is False
    assert result["conflicts"] == conflicts


def test_import_merge_says_so_when_the_file_is_not_there(paired, tmp_path) -> None:
    with pytest.raises(FileNotFoundError):
        server_module.import_merge(archive_path=str(tmp_path / "nope.zip"))
    assert paired.requests == []

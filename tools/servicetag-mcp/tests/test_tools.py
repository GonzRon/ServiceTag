"""One case per tool: the method, the path and the body it sends. No device, no MCP session.

Calling the tool functions directly is deliberate: what can be wrong here is a path, a verb, a
field name or an error mapping. Whether the SDK can serve `tools/list` is `tests/test_sdk_boundary.py`'s
business (review finding 2), and every refusal a tool can raise now arrives as a `ToolError`
(`_call` in `server.py`), not the bare `ApiError`/`NotPaired` `client.py` itself raises.
"""

from __future__ import annotations

import inspect
import json
import zipfile

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import client as client_module
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
    "list_groups",
    "get_group",
    "list_asset_groups",
    "create_group",
    "update_group",
    "archive_group",
    "list_schedules",
    "get_schedule",
    "create_schedule",
    "update_schedule",
    "pause_schedule",
    "archive_schedule",
    "postpone_schedule",
    "complete_schedule",
    "close_round",
    "list_closures",
    "list_due",
    "list_references",
    "add_reference",
    "update_reference",
    "get_season",
    "start_season",
    "end_season",
    "set_season_mode",
    "set_maintenance_break",
    "list_conditions",
    "record_condition",
    "get_health",
    "set_health_policy",
    "list_health_subjects",
    "create_health_subject",
    "update_health_subject",
    "archive_health_subject",
    "list_attention",
)


def body_of(recorded) -> dict:
    return json.loads(recorded.body.decode())


def test_every_tool_the_design_names_is_registered() -> None:
    assert server_module.TOOL_NAMES == EXPECTED_TOOLS
    for name in EXPECTED_TOOLS:
        assert callable(getattr(server_module, name)), f"{name} is missing"


def test_tool_names_are_fifty_five() -> None:
    """Spec §9.4: fourteen new tools take the 1.3 server's forty-one to fifty-five, and `TOOL_NAMES`,
    the registered tools and the guard's `expected_count` all agree."""
    assert len(EXPECTED_TOOLS) == 55
    assert len(server_module.TOOL_NAMES) == 55
    registered = {tool.name for tool in server_module.mcp._tool_manager.list_tools()}
    assert registered == set(server_module.TOOL_NAMES)
    assert len(registered) == 55


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


# --- nits 13 & 14: an empty id must not silently fall through to the list; an id is quoted --------


def test_an_empty_id_refuses_instead_of_answering_with_the_list(paired) -> None:
    with pytest.raises(ToolError, match="asset_id must not be empty"):
        server_module.get_asset(asset_id="")
    assert paired.requests == []


def test_an_id_containing_a_slash_is_quoted_not_split(paired) -> None:
    server_module.get_asset(asset_id="a/1")
    assert paired.last().path == "/v1/assets/a%2F1"


# --- creates: unaffected by the overlay fix, still send only what was given ----------------------


def _asset_row(**overrides) -> dict:
    row = {
        "id": "a1",
        "name": "Hot tub",
        "description": "the deck one",
        "category": "Water",
        "notes": "needs a new cover",
        "status": "ACTIVE",
        "createdAt": 1,
        "updatedAt": 1,
        "templateKey": "hot_tub",
        "manufacturer": "Acme",
        "model": "HT-9",
        "serialNumber": "SN-1",
        "purchaseOn": "2024-05-01",
        "inServiceOn": "2024-05-15",
        "purchasePriceMinor": 450000,
        "currency": "USD",
        "vendor": "Pool Supply Co",
        "location": "Deck",
        "warrantyExpiresOn": "2026-05-01",
        "warrantyNotes": "parts only",
        "retiredOn": None,
        "parentAssetId": "sys1",
        "seasonStartMmdd": "05-01",
        "seasonEndMmdd": "09-30",
    }
    row.update(overrides)
    return row


def test_create_asset_posts_only_what_was_given(paired) -> None:
    server_module.create_asset(name="Hot tub", category="Water", template_key="hot_tub")
    sent = paired.last()
    assert sent.method == "POST"
    assert sent.path == "/v1/assets"
    assert body_of(sent) == {"name": "Hot tub", "category": "Water", "templateKey": "hot_tub"}


def test_create_asset_can_send_every_field_of_the_command(paired) -> None:
    """Finding 1: the fields `AssetCommandRequest` has that the original tool had no parameter for
    at all (purchase/warranty/season/vendor/currency)."""
    server_module.create_asset(
        name="Hot tub",
        purchase_on="2024-05-01",
        in_service_on="2024-05-15",
        purchase_price_minor=450000,
        currency="USD",
        vendor="Pool Supply Co",
        warranty_expires_on="2026-05-01",
        warranty_notes="parts only",
        season_start_mmdd="05-01",
        season_end_mmdd="09-30",
    )
    body = body_of(paired.last())
    assert body["purchaseOn"] == "2024-05-01"
    assert body["inServiceOn"] == "2024-05-15"
    assert body["purchasePriceMinor"] == 450000
    assert body["currency"] == "USD"
    assert body["vendor"] == "Pool Supply Co"
    assert body["warrantyExpiresOn"] == "2026-05-01"
    assert body["warrantyNotes"] == "parts only"
    assert body["seasonStartMmdd"] == "05-01"
    assert body["seasonEndMmdd"] == "09-30"


def test_create_component_posts_to_the_component_path(paired) -> None:
    server_module.create_component(parent_asset_id="a1", name="Pool pump")
    sent = paired.last()
    assert sent.method == "POST"
    assert sent.path == "/v1/assets/a1/components"
    assert body_of(sent) == {"name": "Pool pump"}


def test_create_component_can_also_send_the_fields_the_original_tool_could_not(paired) -> None:
    server_module.create_component(
        parent_asset_id="a1", name="Pool pump", vendor="Pool Supply Co", currency="USD",
    )
    body = body_of(paired.last())
    assert body["vendor"] == "Pool Supply Co"
    assert body["currency"] == "USD"
    assert "parentAssetId" not in body, "the path names the parent, not a body field"


# --- finding 1: update_asset is a GET-then-overlay, never a silent full-replace ------------------


def test_update_asset_preserves_everything_it_was_not_told_to_change(paired) -> None:
    current = _asset_row()
    paired.reply("GET", "/v1/assets/a1", 200, {"asset": current})
    server_module.update_asset(asset_id="a1", name="Hot Tub Deluxe")
    sent = paired.last()
    assert sent.method == "PATCH"
    assert sent.path == "/v1/assets/a1"
    assert body_of(sent) == {
        "name": "Hot Tub Deluxe",
        "category": current["category"],
        "description": current["description"],
        "notes": current["notes"],
        "manufacturer": current["manufacturer"],
        "model": current["model"],
        "serialNumber": current["serialNumber"],
        "purchaseOn": current["purchaseOn"],
        "inServiceOn": current["inServiceOn"],
        "purchasePriceMinor": current["purchasePriceMinor"],
        "currency": current["currency"],
        "vendor": current["vendor"],
        "location": current["location"],
        "warrantyExpiresOn": current["warrantyExpiresOn"],
        "warrantyNotes": current["warrantyNotes"],
        "parentAssetId": current["parentAssetId"],
        "seasonStartMmdd": current["seasonStartMmdd"],
        "seasonEndMmdd": current["seasonEndMmdd"],
        # 1.4: every key of the asset command goes back, the ignored-on-edit `templateKey` included.
        "templateKey": current["templateKey"],
    }


def test_update_asset_a_component_keeps_its_parent_through_a_rename_and_a_category_change(
    paired,
) -> None:
    """A component (`parentAssetId` set) must not be silently promoted to top level by an edit
    that never mentions its parent — proof #3."""
    current = _asset_row(parentAssetId="system-1")
    paired.reply("GET", "/v1/assets/a1", 200, {"asset": current})
    server_module.update_asset(asset_id="a1", name="Pool pump v2", category="Water", vendor="")
    body = body_of(paired.last())
    assert body["parentAssetId"] == "system-1"
    assert body["name"] == "Pool pump v2"
    assert body["category"] == "Water"
    assert body["vendor"] == ""
    for key in (
        "description", "notes", "manufacturer", "model", "serialNumber", "purchaseOn",
        "inServiceOn", "purchasePriceMinor", "currency", "location", "warrantyExpiresOn",
        "warrantyNotes", "seasonStartMmdd", "seasonEndMmdd",
    ):
        assert body[key] == current[key], key


def test_update_asset_an_explicit_null_preserves_just_like_omitted(paired) -> None:
    """Owner ruling: omitted and explicit `null` must be the same thing, because a strict-function-
    calling client sends `null` for every optional argument it was not given."""
    current = _asset_row()
    paired.reply("GET", "/v1/assets/a1", 200, {"asset": current})
    server_module.update_asset(asset_id="a1", name="Hot Tub Deluxe", category=None, vendor=None)
    body = body_of(paired.last())
    assert body["name"] == "Hot Tub Deluxe"
    assert body["category"] == current["category"]
    assert body["vendor"] == current["vendor"]


def test_update_asset_a_supplied_empty_string_is_just_a_value(paired) -> None:
    current = _asset_row()
    paired.reply("GET", "/v1/assets/a1", 200, {"asset": current})
    server_module.update_asset(asset_id="a1", notes="")
    body = body_of(paired.last())
    assert body["notes"] == ""
    assert body["name"] == current["name"]


def test_update_asset_clear_fields_clears_exactly_one_nullable_field(paired) -> None:
    current = _asset_row()
    paired.reply("GET", "/v1/assets/a1", 200, {"asset": current})
    server_module.update_asset(asset_id="a1", clear_fields=["currency"])
    body = body_of(paired.last())
    assert body["currency"] is None
    for key in (
        "name", "category", "description", "notes", "manufacturer", "model", "serialNumber",
        "purchaseOn", "inServiceOn", "purchasePriceMinor", "vendor", "location",
        "warrantyExpiresOn", "warrantyNotes", "parentAssetId", "seasonStartMmdd", "seasonEndMmdd",
    ):
        assert body[key] == current[key], key


def test_update_asset_clear_fields_clears_a_text_field_to_empty_string(paired) -> None:
    """`vendor` is a text field: `clear_fields` sends `""`, not `null` — the app has no null state
    for it. Equivalent to passing `vendor=""` directly; `clear_fields` is the name-based spelling."""
    current = _asset_row()
    paired.reply("GET", "/v1/assets/a1", 200, {"asset": current})
    server_module.update_asset(asset_id="a1", clear_fields=["vendor"])
    body = body_of(paired.last())
    assert body["vendor"] == ""
    assert body["name"] == current["name"]


def test_update_asset_clear_fields_clears_the_parent(paired) -> None:
    current = _asset_row()
    paired.reply("GET", "/v1/assets/a1", 200, {"asset": current})
    server_module.update_asset(asset_id="a1", clear_fields=["parent_asset_id"])
    assert body_of(paired.last())["parentAssetId"] is None


def test_update_asset_clear_fields_refuses_a_field_that_cannot_be_cleared(paired) -> None:
    with pytest.raises(ToolError, match="cannot be cleared"):
        server_module.update_asset(asset_id="a1", clear_fields=["name"])
    assert paired.requests == []


def test_update_asset_clear_fields_refuses_an_unknown_name(paired) -> None:
    with pytest.raises(ToolError, match="cannot be cleared"):
        server_module.update_asset(asset_id="a1", clear_fields=["not_a_field"])
    assert paired.requests == []


def test_update_asset_clear_fields_refuses_a_field_also_given_a_value(paired) -> None:
    with pytest.raises(ToolError, match="also given a value"):
        server_module.update_asset(asset_id="a1", vendor="Acme", clear_fields=["vendor"])
    assert paired.requests == []


def test_retire_and_archive_post_their_flags(paired) -> None:
    server_module.retire_asset(asset_id="a1", retired_on="2026-04-01")
    assert paired.last().path == "/v1/assets/a1/retire"
    assert body_of(paired.last()) == {"retiredOn": "2026-04-01"}

    server_module.archive_asset(asset_id="a1", archived=True)
    assert paired.last().path == "/v1/assets/a1/archive"
    assert body_of(paired.last()) == {"archived": True}


def test_retire_asset_has_no_default_and_is_monotonic(paired) -> None:
    """`retire_asset` retires only; the MCP server exposes no un-retire in 1.1.0."""
    param = inspect.signature(server_module.retire_asset).parameters["retired_on"]
    assert param.default is inspect.Parameter.empty

    for bad in (None, ""):
        with pytest.raises(ToolError, match="retired_on is required"):
            server_module.retire_asset(asset_id="a1", retired_on=bad)
    assert paired.requests == []


# --- finding 11: save_definition and save_profile overlay on edit; update_event does not, and --
# says so by requiring every field ------------------------------------------------------------


def _definition_row(**overrides) -> dict:
    row = {
        "id": "d1",
        "assetId": "a1",
        "key": "ph",
        "label": "pH",
        "unit": "",
        "valueType": "NUMBER",
        "decimals": 1,
        "rangeLow": 7.2,
        "rangeHigh": 7.8,
        "isMeter": False,
        "sortOrder": 0,
        "archivedAt": None,
        "createdAt": 1,
        "updatedAt": 1,
        "kind": "ENTERED",
        "formula": None,
        "sourceAId": None,
        "sourceBId": None,
    }
    row.update(overrides)
    return row


def test_save_definition_create_sends_only_what_was_given(paired) -> None:
    server_module.save_definition(asset_id="a1", label="pH", decimals=1)
    sent = paired.last()
    assert sent.method == "POST"
    assert sent.path == "/v1/definitions"
    assert body_of(sent) == {"assetId": "a1", "label": "pH", "decimals": 1}


def test_save_definition_create_without_a_label_is_a_clear_refusal(paired) -> None:
    with pytest.raises(ToolError, match="label is required"):
        server_module.save_definition(asset_id="a1")
    assert paired.requests == []


def test_save_definition_edit_preserves_everything_it_was_not_told_to_change(paired) -> None:
    current = _definition_row()
    paired.reply("GET", "/v1/assets/a1/definitions", 200, {"definitions": [current]})
    server_module.save_definition(asset_id="a1", definition_id="d1", label="pH (calibrated)")
    sent = paired.last()
    assert sent.method == "POST"
    assert sent.path == "/v1/definitions"
    assert body_of(sent) == {
        "id": "d1",
        "assetId": "a1",
        "key": current["key"],
        "label": "pH (calibrated)",
        "unit": current["unit"],
        "kind": current["kind"],
        "valueType": current["valueType"],
        "decimals": current["decimals"],
        "rangeLow": current["rangeLow"],
        "rangeHigh": current["rangeHigh"],
        "isMeter": current["isMeter"],
        "formula": current["formula"],
        "sourceAId": current["sourceAId"],
        "sourceBId": current["sourceBId"],
    }


def test_save_definition_edit_explicit_null_preserves_like_omitted(paired) -> None:
    current = _definition_row()
    paired.reply("GET", "/v1/assets/a1/definitions", 200, {"definitions": [current]})
    server_module.save_definition(asset_id="a1", definition_id="d1", label="pH (calibrated)", unit=None)
    body = body_of(paired.last())
    assert body["label"] == "pH (calibrated)"
    assert body["unit"] == current["unit"]


def test_save_definition_clear_fields_clears_a_nullable_field(paired) -> None:
    current = _definition_row()
    paired.reply("GET", "/v1/assets/a1/definitions", 200, {"definitions": [current]})
    server_module.save_definition(asset_id="a1", definition_id="d1", clear_fields=["range_low"])
    body = body_of(paired.last())
    assert body["rangeLow"] is None
    assert body["rangeHigh"] == current["rangeHigh"]


def test_save_definition_clear_fields_refuses_an_unknown_name(paired) -> None:
    paired.reply("GET", "/v1/assets/a1/definitions", 200, {"definitions": [_definition_row()]})
    with pytest.raises(ToolError, match="cannot be cleared"):
        server_module.save_definition(asset_id="a1", definition_id="d1", clear_fields=["label"])
    assert paired.requests == []


def test_save_definition_clear_fields_refuses_on_a_create(paired) -> None:
    with pytest.raises(ToolError, match="clear_fields only applies to editing"):
        server_module.save_definition(asset_id="a1", label="pH", clear_fields=["range_low"])
    assert paired.requests == []


def test_save_definition_edit_of_an_id_not_on_the_asset_is_a_clear_refusal(paired) -> None:
    paired.reply("GET", "/v1/assets/a1/definitions", 200, {"definitions": [_definition_row()]})
    with pytest.raises(ToolError, match="no definition_id"):
        server_module.save_definition(asset_id="a1", definition_id="nope", label="x")


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


def _profile_row(**overrides) -> dict:
    row = {
        "id": "p1",
        "assetId": "a1",
        "name": "Water test",
        "eventKind": "MEASUREMENT",
        "defaultTitle": "",
        "templateKey": None,
        "sortOrder": 0,
        "archivedAt": None,
        "createdAt": 1,
        "updatedAt": 1,
        "fields": [{"id": "pf1", "definitionId": "d1", "required": True, "sortOrder": 0}],
        "consumables": [
            {"id": "c1", "name": "Test strips", "defaultQuantity": 1.0, "unit": "ea", "sortOrder": 0},
        ],
    }
    row.update(overrides)
    return row


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


def test_save_profile_create_without_name_or_event_kind_is_a_clear_refusal(paired) -> None:
    with pytest.raises(ToolError, match="event_kind are required"):
        server_module.save_profile(asset_id="a1", name="Water test")
    assert paired.requests == []


def test_save_profile_edit_preserves_fields_and_consumables_when_not_given(paired) -> None:
    current = _profile_row()
    paired.reply("GET", "/v1/assets/a1/profiles", 200, {"profiles": [current]})
    server_module.save_profile(asset_id="a1", profile_id="p1", name="Water test (weekly)")
    body = body_of(paired.last())
    assert body["name"] == "Water test (weekly)"
    assert body["eventKind"] == current["eventKind"]
    assert body["defaultTitle"] == current["defaultTitle"]
    assert body["fields"] == [{"definitionId": "d1", "required": True}]
    assert body["consumables"] == [
        {"id": "c1", "name": "Test strips", "defaultQuantity": 1.0, "unit": "ea"},
    ]


def test_save_profile_edit_can_replace_fields_when_given(paired) -> None:
    current = _profile_row()
    paired.reply("GET", "/v1/assets/a1/profiles", 200, {"profiles": [current]})
    server_module.save_profile(
        asset_id="a1", profile_id="p1", fields=[{"definitionId": "d2", "required": False}],
    )
    body = body_of(paired.last())
    assert body["fields"] == [{"definitionId": "d2", "required": False}]
    assert body["consumables"] == [
        {"id": "c1", "name": "Test strips", "defaultQuantity": 1.0, "unit": "ea"},
    ]


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

    # Finding 11: update_event cannot safely overlay `values`/`consumables` from `list_events`
    # (typed measurements, not the definition-id-to-text map this endpoint reads), so it has no
    # defaults at all — every field is passed explicitly, on purpose, every time.
    server_module.update_event(
        event_id="e1",
        asset_id="a1",
        kind="MAINTENANCE",
        occurred_on="2026-09-21",
        tz_id="UTC",
        title="Filter change",
        profile_id=None,
        occurred_time=None,
        notes="",
        values={"d1": "7.4"},
        consumables=[],
    )
    assert paired.last().method == "PATCH"
    assert paired.last().path == "/v1/events/e1"
    assert body_of(paired.last())["values"] == {"d1": "7.4"}

    paired.reply("DELETE", "/v1/events/e1", 204, b"")
    server_module.delete_event(event_id="e1")
    assert paired.last().method == "DELETE"
    assert paired.last().path == "/v1/events/e1"
    assert paired.last().body == b""


def test_update_event_has_no_default_at_all() -> None:
    """Finding 11's fallback: where a read cannot be safely overlaid, every field is required."""
    params = inspect.signature(server_module.update_event).parameters
    assert all(p.default is inspect.Parameter.empty for p in params.values()), params


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

    # The plan writes nothing, so it is asked of any app; the apply is a write, so the schema check
    # reads `/v1/status` between the two (1.4).
    assert [r.path for r in paired.requests] == [
        "/v1/import-merge/plan",
        "/v1/status",
        "/v1/import-merge/apply",
    ]
    for sent in (r for r in paired.requests if r.path != "/v1/status"):
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
        "/v1/status",
        "/v1/import-merge/apply",
    ]
    assert result["applicable"] is False
    assert result["conflicts"] == conflicts


def test_import_merge_apply_409_with_an_error_envelope_raises_instead_of_returning_it(
    paired, tmp_path
) -> None:
    """Finding 3: `archive_newer_format`, `store_unavailable`, `asset_cycle` and the merge codes as
    defence in depth all answer a 409 with `{"error": {...}}`, not a report — that must still raise."""
    archive = an_archive(tmp_path)
    paired.reply("POST", "/v1/import-merge/plan", 200, {"applicable": True, "conflicts": []})
    paired.reply(
        "POST", "/v1/import-merge/apply", 409,
        {"error": {"code": "archive_newer_format",
                   "message": "that archive was written by a newer build", "problems": []}},
    )

    with pytest.raises(ToolError) as raised:
        server_module.import_merge(archive_path=str(archive))
    assert "archive_newer_format" in str(raised.value)


def test_import_merge_says_so_when_the_file_is_not_there(paired, tmp_path) -> None:
    with pytest.raises(ToolError, match="No such file"):
        server_module.import_merge(archive_path=str(tmp_path / "nope.zip"))
    assert paired.requests == []


def test_import_merge_refuses_an_archive_over_the_cap_before_reading_it(
    paired, tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Finding 4: the cap is checked before anything is read or sent."""
    archive = an_archive(tmp_path)
    monkeypatch.setattr(server_module, "MAX_IMPORT_BYTES", 4)
    with pytest.raises(ToolError) as raised:
        server_module.import_merge(archive_path=str(archive))
    assert "4 bytes" in str(raised.value)
    assert paired.requests == []


def test_max_import_bytes_is_four_mebibytes() -> None:
    """R7: the test above monkeypatches this constant to make the over-cap check cheap to trigger
    without a real 4 MiB fixture file, which proves the comparison but leaves the real value
    unasserted anywhere. This pins it, against `ApiRouter.kt`'s own `MAX_IMPORT_BYTES`."""
    assert client_module.MAX_IMPORT_BYTES == 4 * 1024 * 1024

"""The row-mapping test matrix from the task brief. `_rich_source()` is one fixture exercising
every DTO and value shape (NUMBER/TEXT/BOOLEAN, DERIVED, a parent/child asset pair, a profile with
fields and consumables, three events -- one with a profile and every value type, one bare, one
with an overriding `tzId`) so each test only needs to touch the one thing it is proving.
"""

from __future__ import annotations

import calendar
import copy
from datetime import datetime, timezone
from typing import Any

from servicetag_bundle.ids import namespace_of, row_id
from servicetag_bundle.rows import build_rows, epoch_millis
from servicetag_bundle.source import parse_source

from conftest import _all_ids, _built, _ns, _rich_source

# ---- the DTO field sets, transcribed from BackupFormat.kt -------------------------------------

ASSET_FIELDS = {  # AssetDto, 24 fields
    "id", "name", "description", "category", "notes", "status", "createdAt", "updatedAt",
    "templateKey", "manufacturer", "model", "serialNumber", "purchaseOn", "inServiceOn",
    "purchasePriceMinor", "currency", "vendor", "location", "warrantyExpiresOn", "warrantyNotes",
    "retiredOn", "parentAssetId", "seasonStartMmdd", "seasonEndMmdd",
}
DEFINITION_FIELDS = {  # MeasurementDefinitionDto, 18 fields
    "id", "assetId", "key", "label", "unit", "valueType", "decimals", "rangeLow", "rangeHigh",
    "isMeter", "sortOrder", "archivedAt", "createdAt", "updatedAt", "kind", "formula",
    "sourceAId", "sourceBId",
}
PROFILE_FIELDS = {  # EventProfileDto, 12 fields
    "id", "assetId", "name", "eventKind", "defaultTitle", "templateKey", "sortOrder",
    "archivedAt", "createdAt", "updatedAt", "fields", "consumables",
}
PROFILE_FIELD_FIELDS = {"id", "definitionId", "required", "sortOrder"}  # ProfileFieldDto, 4
PROFILE_CONSUMABLE_FIELDS = {  # ProfileConsumableDto, 5 fields
    "id", "name", "defaultQuantity", "unit", "sortOrder",
}
EVENT_FIELDS = {  # AssetEventDto, 15 fields
    "id", "assetId", "kind", "title", "profileId", "occurredOn", "occurredTime", "tzId", "notes",
    "source", "sourceRef", "createdAt", "updatedAt", "measurements", "consumables",
}
MEASUREMENT_FIELDS = {"id", "definitionId", "valueNum", "valueText", "unit", "sortOrder"}  # 6
CONSUMABLE_USAGE_FIELDS = {"id", "name", "quantity", "unit", "sortOrder"}  # ConsumableUsageDto, 5


# ---- fixture --------------------------------------------------------------------------------
#
# `_rich_source`, `_built`, `_ns` and `_all_ids` live in `conftest.py` -- `test_ids.py`'s key-table
# test binds the id-derivation formula to a real build of this same fixture, so both suites share
# one copy rather than one importing the other's test module.

def _asset(built: dict[str, Any], key: str) -> dict[str, Any]:
    ns = _ns()
    return next(a for a in built["assets"] if a["id"] == row_id(ns, f"asset:{key}"))


def _event(built: dict[str, Any], asset_key: str, event_key: str) -> dict[str, Any]:
    ns = _ns()
    return next(
        e for e in built["assetEvents"] if e["id"] == row_id(ns, f"event:{asset_key}/{event_key}")
    )


def _expected_millis(dt: datetime) -> int:
    """An independent, integer-only epoch-millis computation for `asOf`."""
    return calendar.timegm(dt.utctimetuple()) * 1000 + dt.microsecond // 1000


# ---- determinism ------------------------------------------------------------------------------

def test_same_source_twice_yields_identical_rows():
    source = parse_source(_rich_source())
    assert build_rows(source) == build_rows(parse_source(_rich_source()))


def test_different_namespace_shares_no_id():
    a = build_rows(parse_source(_rich_source()))
    other = copy.deepcopy(_rich_source())
    other["namespace"] = "gadget-ranch"
    b = build_rows(parse_source(other))
    assert _all_ids(a) & _all_ids(b) == set()


def test_different_bundle_key_yields_identical_rows():
    a = build_rows(parse_source(_rich_source()))
    other = copy.deepcopy(_rich_source())
    other["bundleKey"] = "stage-b"
    b = build_rows(parse_source(other))
    assert a == b


def test_reordering_event_consumables_changes_no_id():
    source = copy.deepcopy(_rich_source())
    source["assets"][1]["events"][0]["consumables"] = [
        {"key": "filter", "name": "Filter", "quantity": 1, "unit": "pcs"},
        {"key": "wipes", "name": "Wipes", "quantity": 3, "unit": "pcs"},
    ]
    reordered = copy.deepcopy(source)
    reordered["assets"][1]["events"][0]["consumables"] = list(
        reversed(source["assets"][1]["events"][0]["consumables"])
    )

    a = _event(build_rows(parse_source(source)), "widget-mixer", "e1")
    b = _event(build_rows(parse_source(reordered)), "widget-mixer", "e1")
    a_ids = {c["name"]: c["id"] for c in a["consumables"]}
    b_ids = {c["name"]: c["id"] for c in b["consumables"]}
    assert a_ids == b_ids


def test_reordering_values_object_keys_changes_nothing():
    a = copy.deepcopy(_rich_source())
    a["assets"][1]["events"][0]["values"] = {
        "ph": 7.5, "temp": 68, "running": True, "notes_field": "Looks clean",
    }
    b = copy.deepcopy(_rich_source())
    b["assets"][1]["events"][0]["values"] = {
        "notes_field": "Looks clean", "running": True, "temp": 68, "ph": 7.5,
    }
    assert build_rows(parse_source(a)) == build_rows(parse_source(b))


# ---- DTO field sets --------------------------------------------------------------------------

def test_asset_rows_have_exactly_the_dto_fields():
    built = _built()
    for row in built["assets"]:
        assert set(row.keys()) == ASSET_FIELDS


def test_definition_rows_have_exactly_the_dto_fields():
    built = _built()
    for row in built["measurementDefinitions"]:
        assert set(row.keys()) == DEFINITION_FIELDS


def test_profile_rows_have_exactly_the_dto_fields():
    built = _built()
    for row in built["eventProfiles"]:
        assert set(row.keys()) == PROFILE_FIELDS
        for field_row in row["fields"]:
            assert set(field_row.keys()) == PROFILE_FIELD_FIELDS
        for consumable_row in row["consumables"]:
            assert set(consumable_row.keys()) == PROFILE_CONSUMABLE_FIELDS


def test_event_rows_have_exactly_the_dto_fields():
    built = _built()
    for row in built["assetEvents"]:
        assert set(row.keys()) == EVENT_FIELDS
        for measurement_row in row["measurements"]:
            assert set(measurement_row.keys()) == MEASUREMENT_FIELDS
        for consumable_row in row["consumables"]:
            assert set(consumable_row.keys()) == CONSUMABLE_USAGE_FIELDS


def test_empty_tables_are_empty():
    built = _built()
    assert built["nfcTags"] == []
    assert built["externalLinks"] == []
    assert built["attachments"] == []


# ---- typing -----------------------------------------------------------------------------------

# Each spec value is `(expected_type, nullable)`. `nullable` records whether the DTO field is
# ever legitimately `None` for that row -- a field marked `False` here that turns up `None` is a
# real regression (the id/timestamp/sortOrder fields are never optional), not a value the walk
# should silently pass over the way a genuinely-optional field (`rangeLow`, `defaultQuantity`,
# `valueNum` for a TEXT measurement) is allowed to.
_NUMERIC_SPEC: dict[str, dict[str, tuple[type, bool]]] = {
    "assets": {
        "createdAt": (int, False), "updatedAt": (int, False),
        "purchasePriceMinor": (int, True),
    },
    "measurementDefinitions": {
        "createdAt": (int, False), "updatedAt": (int, False),
        "decimals": (int, False), "sortOrder": (int, False),
        "rangeLow": (float, True), "rangeHigh": (float, True),
    },
    "eventProfiles": {
        "createdAt": (int, False), "updatedAt": (int, False), "sortOrder": (int, False),
    },
    "assetEvents": {"createdAt": (int, False), "updatedAt": (int, False)},
}
_NESTED_NUMERIC_SPEC: dict[str, dict[str, dict[str, tuple[type, bool]]]] = {
    "eventProfiles": {
        "fields": {"sortOrder": (int, False)},
        "consumables": {"sortOrder": (int, False), "defaultQuantity": (float, True)},
    },
    "assetEvents": {
        "measurements": {"valueNum": (float, True), "sortOrder": (int, False)},
        "consumables": {"quantity": (float, False), "sortOrder": (int, False)},
    },
}


def _assert_typed(row: dict[str, Any], spec: dict[str, tuple[type, bool]]) -> None:
    for field_name, (expected, nullable) in spec.items():
        value = row.get(field_name)
        if value is None:
            assert nullable, f"{field_name} is None, but this DTO field is never null"
            continue
        assert type(value) is expected, f"{field_name} is {type(value)}, expected {expected}"


def test_every_integral_and_double_field_is_typed_correctly():
    built = _built()
    for table, spec in _NUMERIC_SPEC.items():
        for row in built[table]:
            _assert_typed(row, spec)
    for table, nested in _NESTED_NUMERIC_SPEC.items():
        for row in built[table]:
            for nested_key, nested_spec in nested.items():
                for nested_row in row[nested_key]:
                    _assert_typed(nested_row, nested_spec)


def test_negative_zero_is_normalised_to_positive_zero():
    source = copy.deepcopy(_rich_source())
    source["assets"][1]["definitions"][0]["rangeLow"] = -0.0
    built = build_rows(parse_source(source))
    definition = next(d for d in built["measurementDefinitions"] if d["key"] == "ph")
    assert definition["rangeLow"] == 0.0
    assert str(definition["rangeLow"]) == "0.0"  # -0.0 stringifies as "-0.0"; must not


# ---- value shapes ----------------------------------------------------------------------------

def test_number_value_lands_in_valueNum_with_definitions_unit():
    event = _event(_built(), "widget-mixer", "e1")
    ph = next(m for m in event["measurements"] if m["definitionId"] == row_id(_ns(), "definition:widget-mixer/ph"))
    assert ph["valueNum"] == 7.5
    assert ph["valueText"] is None
    assert ph["unit"] == "pH"


def test_text_value_lands_in_valueText():
    event = _event(_built(), "widget-mixer", "e1")
    notes = next(m for m in event["measurements"] if m["definitionId"] == row_id(_ns(), "definition:widget-mixer/notes_field"))
    assert notes["valueText"] == "Looks clean"
    assert notes["valueNum"] is None


def test_boolean_true_lands_as_valueNum_one():
    event = _event(_built(), "widget-mixer", "e1")
    running = next(m for m in event["measurements"] if m["definitionId"] == row_id(_ns(), "definition:widget-mixer/running"))
    assert running["valueNum"] == 1.0
    assert running["valueText"] is None


def test_boolean_false_lands_as_valueNum_zero():
    event = _event(_built(), "widget-mixer", "e3")
    running = next(m for m in event["measurements"] if m["definitionId"] == row_id(_ns(), "definition:widget-mixer/running"))
    assert running["valueNum"] == 0.0
    assert running["valueText"] is None


def test_measurement_sort_order_equals_its_definitions_sort_order():
    event = _event(_built(), "widget-mixer", "e1")
    ph = next(m for m in event["measurements"] if m["definitionId"] == row_id(_ns(), "definition:widget-mixer/ph"))
    temp = next(m for m in event["measurements"] if m["definitionId"] == row_id(_ns(), "definition:widget-mixer/temp"))
    assert ph["sortOrder"] == 0
    assert temp["sortOrder"] == 1


# ---- events -------------------------------------------------------------------------------------

def test_event_source_and_source_ref():
    event = _event(_built(), "widget-mixer", "e1")
    assert event["source"] == "IMPORT"
    assert event["sourceRef"] == "widget-mixer/e1"


def test_event_occurred_time_is_always_null():
    built = _built()
    for row in built["assetEvents"]:
        assert row["occurredTime"] is None


def test_event_tz_id_defaults_to_source_tz():
    event = _event(_built(), "widget-mixer", "e2")
    assert event["tzId"] == "America/Denver"


def test_event_tz_id_uses_its_own_override():
    event = _event(_built(), "widget-mixer", "e3")
    assert event["tzId"] == "Europe/Berlin"


def test_event_profile_id_resolves_to_the_profiles_id():
    event = _event(_built(), "widget-mixer", "e1")
    assert event["profileId"] == row_id(_ns(), "profile:widget-mixer/water-test")


def test_event_profile_id_is_null_without_a_profile():
    event = _event(_built(), "widget-mixer", "e2")
    assert event["profileId"] is None
    assert event["measurements"] == []
    assert event["consumables"] == []


# ---- assets, order, status, timestamps, sortOrder --------------------------------------------

def test_children_are_emitted_after_parents():
    built = _built()
    keys_in_order = []
    ns = _ns()
    id_to_key = {row_id(ns, f"asset:{k}"): k for k in ("widget-fan", "widget-mixer")}
    for row in built["assets"]:
        keys_in_order.append(id_to_key[row["id"]])
    assert keys_in_order == ["widget-mixer", "widget-fan"]


def test_parent_asset_id_resolves_to_the_parents_id():
    built = _built()
    fan = _asset(built, "widget-fan")
    mixer = _asset(built, "widget-mixer")
    assert fan["parentAssetId"] == mixer["id"]
    assert mixer["parentAssetId"] is None


def test_asset_status_is_always_active():
    built = _built()
    for row in built["assets"]:
        assert row["status"] == "ACTIVE"


def test_timestamps_equal_as_of_millis_everywhere():
    source = parse_source(_rich_source())
    expected = _expected_millis(source.asOf)
    built = build_rows(source)

    def _check(rows: list[dict[str, Any]]) -> None:
        for row in rows:
            if "createdAt" in row:
                assert row["createdAt"] == expected
            if "updatedAt" in row:
                assert row["updatedAt"] == expected

    _check(built["assets"])
    _check(built["measurementDefinitions"])
    _check(built["eventProfiles"])
    _check(built["assetEvents"])


def test_epoch_millis_truncates_sub_millisecond_precision_toward_the_lower_millisecond():
    """`asOf` is free to carry microseconds (`datetime.fromisoformat` accepts them); the docstring
    promises truncation toward the lower millisecond, not rounding -- pin both with a value whose
    truncated and rounded results would differ (999999us truncates to 999ms, would round to
    1000ms/carry a second)."""
    dt = datetime(2026, 9, 21, 0, 0, 0, 999999, tzinfo=timezone.utc)
    assert epoch_millis(dt) == _expected_millis(dt)
    assert epoch_millis(dt) % 1000 == 999


def test_sort_order_is_the_position_in_the_source_array():
    built = _built()
    profile = next(p for p in built["eventProfiles"] if p["name"] == "Water Test")
    assert [f["sortOrder"] for f in profile["fields"]] == [0, 1]
    assert [c["sortOrder"] for c in profile["consumables"]] == [0]

    event = _event(built, "widget-mixer", "e1")
    assert [c["sortOrder"] for c in event["consumables"]] == [0]

    definitions = sorted(built["measurementDefinitions"], key=lambda d: d["sortOrder"])
    assert [d["key"] for d in definitions] == ["ph", "temp", "notes_field", "running", "wear_pct"]


# ---- declared null invariants and the DERIVED mapping -----------------------------------------
#
# The field-set tests above prove these keys exist on every row; they never look at the values, so
# e.g. `archivedAt = as_of_millis` (every definition silently archived) would still pass them.

def test_asset_template_key_and_retired_on_are_always_null():
    built = _built()
    for row in built["assets"]:
        assert row["templateKey"] is None
        assert row["retiredOn"] is None


def test_definition_archived_at_is_always_null():
    built = _built()
    for row in built["measurementDefinitions"]:
        assert row["archivedAt"] is None


def test_profile_archived_at_and_template_key_are_always_null():
    built = _built()
    for row in built["eventProfiles"]:
        assert row["archivedAt"] is None
        assert row["templateKey"] is None


def test_derived_definition_carries_its_formula_and_resolved_sources():
    built = _built()
    ns = _ns()
    wear_pct = next(d for d in built["measurementDefinitions"] if d["key"] == "wear_pct")
    assert wear_pct["kind"] == "DERIVED"
    assert wear_pct["formula"] == "PERCENT_DROP"
    assert wear_pct["sourceAId"] == row_id(ns, "definition:widget-mixer/ph")
    assert wear_pct["sourceBId"] == row_id(ns, "definition:widget-mixer/temp")


def test_entered_definitions_carry_no_derived_fields():
    built = _built()
    for row in built["measurementDefinitions"]:
        if row["kind"] == "ENTERED":
            assert row["formula"] is None
            assert row["sourceAId"] is None
            assert row["sourceBId"] is None

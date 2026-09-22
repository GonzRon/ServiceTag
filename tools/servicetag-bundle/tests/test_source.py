"""The source-model test matrix from the task brief: one test per row. Every rejection asserts
the exact `SourceError.path` alongside the rejection itself, since that path is what a later task
(and a human fixing their private source) actually needs.
"""

from __future__ import annotations

from datetime import datetime, timezone

import pytest

from conftest import minimal_source, mutate, source_with
from servicetag_bundle.source import SourceError, parse_source


def assert_rejects(source: dict, expected_path: str) -> None:
    with pytest.raises(SourceError) as excinfo:
        parse_source(source)
    assert excinfo.value.path == expected_path


# ---- accepts the minimal valid source; defaults applied ---------------------------------------

def test_accepts_minimal_source_with_defaults():
    source = source_with(
        definitions=[{"key": "ph", "label": "pH", "valueType": "NUMBER"}],
        profiles=[{"key": "water-test", "name": "Water Test", "eventKind": "MEASUREMENT"}],
        events=[{"key": "e1", "kind": "NOTE", "occurredOn": "2026-09-21", "title": "Checked in"}],
    )
    result = parse_source(source)

    asset = result.assets[0]
    assert asset.category == ""
    definition = asset.definitions[0]
    assert definition.decimals == 0
    assert definition.kind == "ENTERED"
    profile = asset.profiles[0]
    assert profile.defaultTitle == profile.name == "Water Test"
    event = asset.events[0]
    assert event.tzId == result.tzId == "America/Denver"


# ---- top-level rejections -----------------------------------------------------------------------

def test_rejects_wrong_format_version():
    assert_rejects(mutate(minimal_source(), "formatVersion", 2), "formatVersion")


def test_rejects_blank_namespace():
    assert_rejects(mutate(minimal_source(), "namespace", ""), "namespace")


def test_rejects_asOf_without_offset():
    assert_rejects(mutate(minimal_source(), "asOf", "2026-09-21T00:00:00"), "asOf")


def test_rejects_unknown_top_level_key():
    assert_rejects(mutate(minimal_source(), "bogus", True), "bogus")


def test_rejects_unknown_tz():
    assert_rejects(mutate(minimal_source(), "tzId", "Mars/OlympusMons"), "tzId")


def test_rejects_deferred_that_is_not_an_object():
    assert_rejects(mutate(minimal_source(), "deferred", ["not", "an", "object"]), "deferred")


# ---- asset-level rejections ----------------------------------------------------------------------

def test_rejects_asset_key_violating_pattern():
    assert_rejects(mutate(minimal_source(), "assets[0].key", "Not Valid!"), "assets[0].key")


def test_rejects_duplicate_asset_keys():
    source = minimal_source()
    source["assets"].append({"key": "widget-mixer", "name": "Widget Mixer Again"})
    assert_rejects(source, "assets[1].key")


def test_rejects_parent_naming_unknown_key():
    assert_rejects(source_with(parent="ghost-asset"), "assets[0].parent")


def test_rejects_parent_cycle():
    source = minimal_source()
    source["assets"] = [
        {"key": "widget-mixer", "name": "Widget Mixer", "parent": "widget-fan"},
        {"key": "widget-fan", "name": "Widget Fan", "parent": "widget-mixer"},
    ]
    assert_rejects(source, "assets[1].parent")


def test_rejects_price_without_currency():
    assert_rejects(source_with(purchasePriceMinor=500), "assets[0].currency")


def test_rejects_negative_price():
    assert_rejects(source_with(purchasePriceMinor=-100, currency="USD"),
                    "assets[0].purchasePriceMinor")


def test_rejects_malformed_date():
    assert_rejects(source_with(purchaseOn="2026-13-01"), "assets[0].purchaseOn")


def test_rejects_date_in_basic_format():
    assert_rejects(source_with(purchaseOn="20260921"), "assets[0].purchaseOn")


def test_rejects_date_in_week_format():
    assert_rejects(source_with(purchaseOn="2026-W38-1"), "assets[0].purchaseOn")


def test_rejects_season_with_one_bound_only():
    assert_rejects(source_with(seasonStartMmdd="05-01"), "assets[0].seasonEndMmdd")


def test_rejects_season_value_not_mmdd():
    assert_rejects(
        source_with(seasonStartMmdd="2026-05-01", seasonEndMmdd="09-01"),
        "assets[0].seasonStartMmdd",
    )


def test_rejects_season_bound_not_a_real_day():
    assert_rejects(
        source_with(seasonStartMmdd="02-30", seasonEndMmdd="03-01"),
        "assets[0].seasonStartMmdd",
    )


def test_rejects_currency_right_shape_not_in_allow_list():
    assert_rejects(
        source_with(purchasePriceMinor=100, currency="ZZZ"),
        "assets[0].currency",
    )


# ---- definition rejections -----------------------------------------------------------------------

def test_rejects_definition_missing_value_type():
    assert_rejects(
        source_with(definitions=[{"key": "ph", "label": "pH"}]),
        "assets[0].definitions[0].valueType",
    )


def test_rejects_definition_key_violating_pattern():
    assert_rejects(
        source_with(definitions=[{"key": "Bad-Key", "label": "Bad", "valueType": "NUMBER"}]),
        "assets[0].definitions[0].key",
    )


def test_rejects_duplicate_definition_keys():
    assert_rejects(
        source_with(definitions=[
            {"key": "ph", "label": "pH", "valueType": "NUMBER"},
            {"key": "ph", "label": "pH again", "valueType": "NUMBER"},
        ]),
        "assets[0].definitions[1].key",
    )


def test_rejects_rangeLow_on_text_definition():
    assert_rejects(
        source_with(definitions=[
            {"key": "note", "label": "Note", "valueType": "TEXT", "rangeLow": 1},
        ]),
        "assets[0].definitions[0].rangeLow",
    )


def test_rejects_rangeLow_greater_than_rangeHigh():
    assert_rejects(
        source_with(definitions=[
            {"key": "ph", "label": "pH", "valueType": "NUMBER", "rangeLow": 10, "rangeHigh": 5},
        ]),
        "assets[0].definitions[0].rangeHigh",
    )


def test_rejects_decimals_out_of_range():
    assert_rejects(
        source_with(definitions=[
            {"key": "ph", "label": "pH", "valueType": "NUMBER", "decimals": 5},
        ]),
        "assets[0].definitions[0].decimals",
    )


def test_rejects_derived_without_sources():
    assert_rejects(
        source_with(definitions=[
            {"key": "drop", "label": "Drop", "valueType": "NUMBER", "kind": "DERIVED"},
        ]),
        "assets[0].definitions[0].formula",
    )


def test_rejects_derived_source_is_derived():
    assert_rejects(
        source_with(definitions=[
            {"key": "raw1", "label": "Raw1", "valueType": "NUMBER"},
            {"key": "raw2", "label": "Raw2", "valueType": "NUMBER"},
            {"key": "mid", "label": "Mid", "valueType": "NUMBER", "kind": "DERIVED",
             "formula": "PERCENT_DROP", "sourceA": "raw1", "sourceB": "raw2"},
            {"key": "bad", "label": "Bad", "valueType": "NUMBER", "kind": "DERIVED",
             "formula": "PERCENT_DROP", "sourceA": "mid", "sourceB": "raw2"},
        ]),
        "assets[0].definitions[3].sourceA",
    )


def test_rejects_derived_source_not_number():
    assert_rejects(
        source_with(definitions=[
            {"key": "raw1", "label": "Raw1", "valueType": "TEXT"},
            {"key": "raw2", "label": "Raw2", "valueType": "NUMBER"},
            {"key": "drop", "label": "Drop", "valueType": "NUMBER", "kind": "DERIVED",
             "formula": "PERCENT_DROP", "sourceA": "raw1", "sourceB": "raw2"},
        ]),
        "assets[0].definitions[2].sourceA",
    )


def test_rejects_derived_source_of_another_asset():
    source = minimal_source()
    source["assets"] = [
        {
            "key": "widget-mixer", "name": "Widget Mixer",
            "definitions": [{"key": "raw", "label": "Raw", "valueType": "NUMBER"}],
        },
        {
            "key": "widget-fan", "name": "Widget Fan",
            "definitions": [
                {"key": "raw2", "label": "Raw2", "valueType": "NUMBER"},
                {"key": "drop", "label": "Drop", "valueType": "NUMBER", "kind": "DERIVED",
                 "formula": "PERCENT_DROP", "sourceA": "raw", "sourceB": "raw2"},
            ],
        },
    ]
    assert_rejects(source, "assets[1].definitions[1].sourceA")


def test_rejects_derived_definition_that_is_text():
    assert_rejects(
        source_with(definitions=[
            {"key": "raw1", "label": "Raw1", "valueType": "NUMBER"},
            {"key": "raw2", "label": "Raw2", "valueType": "NUMBER"},
            {"key": "drop", "label": "Drop", "valueType": "TEXT", "kind": "DERIVED",
             "formula": "PERCENT_DROP", "sourceA": "raw1", "sourceB": "raw2"},
        ]),
        "assets[0].definitions[2].valueType",
    )


def test_rejects_derived_definition_that_is_a_meter():
    assert_rejects(
        source_with(definitions=[
            {"key": "raw1", "label": "Raw1", "valueType": "NUMBER"},
            {"key": "raw2", "label": "Raw2", "valueType": "NUMBER"},
            {"key": "drop", "label": "Drop", "valueType": "NUMBER", "isMeter": True,
             "kind": "DERIVED", "formula": "PERCENT_DROP", "sourceA": "raw1", "sourceB": "raw2"},
        ]),
        "assets[0].definitions[2].isMeter",
    )


def test_rejects_derived_two_sources_the_same_key():
    assert_rejects(
        source_with(definitions=[
            {"key": "raw1", "label": "Raw1", "valueType": "NUMBER"},
            {"key": "drop", "label": "Drop", "valueType": "NUMBER", "kind": "DERIVED",
             "formula": "PERCENT_DROP", "sourceA": "raw1", "sourceB": "raw1"},
        ]),
        "assets[0].definitions[1].sourceB",
    )


def test_rejects_derived_source_that_is_a_meter():
    assert_rejects(
        source_with(definitions=[
            {"key": "raw1", "label": "Raw1", "valueType": "NUMBER", "isMeter": True},
            {"key": "raw2", "label": "Raw2", "valueType": "NUMBER"},
            {"key": "drop", "label": "Drop", "valueType": "NUMBER", "kind": "DERIVED",
             "formula": "PERCENT_DROP", "sourceA": "raw1", "sourceB": "raw2"},
        ]),
        "assets[0].definitions[2].sourceA",
    )


# ---- profile rejections --------------------------------------------------------------------------

def test_rejects_profile_field_naming_derived_definition():
    assert_rejects(
        source_with(
            definitions=[
                {"key": "raw1", "label": "Raw1", "valueType": "NUMBER"},
                {"key": "raw2", "label": "Raw2", "valueType": "NUMBER"},
                {"key": "drop", "label": "Drop", "valueType": "NUMBER", "kind": "DERIVED",
                 "formula": "PERCENT_DROP", "sourceA": "raw1", "sourceB": "raw2"},
            ],
            profiles=[{
                "key": "water-test", "name": "Water Test", "eventKind": "MEASUREMENT",
                "fields": [{"definition": "drop"}],
            }],
        ),
        "assets[0].profiles[0].fields[0].definition",
    )


def test_rejects_profile_field_naming_other_asset_definition():
    source = minimal_source()
    source["assets"] = [
        {
            "key": "widget-mixer", "name": "Widget Mixer",
            "definitions": [{"key": "ph", "label": "pH", "valueType": "NUMBER"}],
            "profiles": [{
                "key": "water-test", "name": "Water Test", "eventKind": "MEASUREMENT",
                "fields": [{"definition": "salinity"}],
            }],
        },
        {
            "key": "widget-fan", "name": "Widget Fan",
            "definitions": [{"key": "salinity", "label": "Salinity", "valueType": "NUMBER"}],
        },
    ]
    assert_rejects(source, "assets[0].profiles[0].fields[0].definition")


def test_rejects_unknown_event_kind():
    assert_rejects(
        source_with(profiles=[
            {"key": "water-test", "name": "Water Test", "eventKind": "BOGUS_KIND"},
        ]),
        "assets[0].profiles[0].eventKind",
    )


def test_rejects_duplicate_profile_keys():
    assert_rejects(
        source_with(profiles=[
            {"key": "water-test", "name": "Water Test", "eventKind": "MEASUREMENT"},
            {"key": "water-test", "name": "Water Test Again", "eventKind": "MEASUREMENT"},
        ]),
        "assets[0].profiles[1].key",
    )


def test_rejects_duplicate_consumable_keys_within_a_profile():
    assert_rejects(
        source_with(profiles=[{
            "key": "water-test", "name": "Water Test", "eventKind": "MEASUREMENT",
            "consumables": [
                {"key": "chlorine", "name": "Chlorine Tablet"},
                {"key": "chlorine", "name": "Chlorine Tablet Again"},
            ],
        }]),
        "assets[0].profiles[0].consumables[1].key",
    )


def test_rejects_profile_listing_one_definition_twice():
    assert_rejects(
        source_with(
            definitions=[{"key": "ph", "label": "pH", "valueType": "NUMBER"}],
            profiles=[{
                "key": "water-test", "name": "Water Test", "eventKind": "MEASUREMENT",
                "fields": [{"definition": "ph"}, {"definition": "ph"}],
            }],
        ),
        "assets[0].profiles[0].fields[1].definition",
    )


# ---- event rejections -----------------------------------------------------------------------------

def test_rejects_event_profile_of_another_asset():
    source = minimal_source()
    source["assets"] = [
        {
            "key": "widget-mixer", "name": "Widget Mixer",
            "events": [{
                "key": "e1", "kind": "NOTE", "occurredOn": "2026-09-21", "title": "Checked",
                "profile": "water-test",
            }],
        },
        {
            "key": "widget-fan", "name": "Widget Fan",
            "profiles": [{"key": "water-test", "name": "Water Test", "eventKind": "MEASUREMENT"}],
        },
    ]
    assert_rejects(source, "assets[0].events[0].profile")


def test_rejects_event_value_for_derived_definition():
    assert_rejects(
        source_with(
            definitions=[
                {"key": "raw1", "label": "Raw1", "valueType": "NUMBER"},
                {"key": "raw2", "label": "Raw2", "valueType": "NUMBER"},
                {"key": "drop", "label": "Drop", "valueType": "NUMBER", "kind": "DERIVED",
                 "formula": "PERCENT_DROP", "sourceA": "raw1", "sourceB": "raw2"},
            ],
            events=[{
                "key": "e1", "kind": "MEASUREMENT", "occurredOn": "2026-09-21", "title": "Check",
                "values": {"drop": 12.5},
            }],
        ),
        "assets[0].events[0].values.drop",
    )


def test_rejects_number_value_given_as_a_string():
    assert_rejects(
        source_with(
            definitions=[{"key": "ph", "label": "pH", "valueType": "NUMBER"}],
            events=[{
                "key": "e1", "kind": "MEASUREMENT", "occurredOn": "2026-09-21", "title": "Check",
                "values": {"ph": "7.2"},
            }],
        ),
        "assets[0].events[0].values.ph",
    )


def test_rejects_boolean_value_given_as_a_number():
    assert_rejects(
        source_with(
            definitions=[{"key": "leak", "label": "Leak", "valueType": "BOOLEAN"}],
            events=[{
                "key": "e1", "kind": "INSPECTION", "occurredOn": "2026-09-21", "title": "Check",
                "values": {"leak": 1},
            }],
        ),
        "assets[0].events[0].values.leak",
    )


def test_rejects_blank_text_event_value():
    assert_rejects(
        source_with(
            definitions=[{"key": "note", "label": "Note", "valueType": "TEXT"}],
            events=[{
                "key": "e1", "kind": "NOTE", "occurredOn": "2026-09-21", "title": "Check",
                "values": {"note": "   "},
            }],
        ),
        "assets[0].events[0].values.note",
    )


def test_rejects_duplicate_event_keys():
    assert_rejects(
        source_with(events=[
            {"key": "e1", "kind": "NOTE", "occurredOn": "2026-09-21", "title": "First"},
            {"key": "e1", "kind": "NOTE", "occurredOn": "2026-09-22", "title": "Second"},
        ]),
        "assets[0].events[1].key",
    )


def test_rejects_duplicate_consumable_keys_within_an_event():
    assert_rejects(
        source_with(events=[{
            "key": "e1", "kind": "TREATMENT", "occurredOn": "2026-09-21", "title": "Treated",
            "consumables": [
                {"key": "chlorine", "name": "Chlorine Tablet", "quantity": 1},
                {"key": "chlorine", "name": "Chlorine Tablet Again", "quantity": 2},
            ],
        }]),
        "assets[0].events[0].consumables[1].key",
    )


# ---- unknown keys are rejected everywhere ----------------------------------------------------

@pytest.mark.parametrize(
    ("source", "expected_path"),
    [
        pytest.param(mutate(minimal_source(), "bogus", True), "bogus", id="top-level"),
        pytest.param(mutate(minimal_source(), "assets[0].bogus", True), "assets[0].bogus",
                     id="asset"),
        pytest.param(
            source_with(definitions=[{"key": "ph", "label": "pH", "bogus": True}]),
            "assets[0].definitions[0].bogus", id="definition",
        ),
        pytest.param(
            source_with(profiles=[{"key": "water-test", "name": "Water Test",
                                    "eventKind": "MEASUREMENT", "bogus": True}]),
            "assets[0].profiles[0].bogus", id="profile",
        ),
        pytest.param(
            source_with(
                definitions=[{"key": "ph", "label": "pH", "valueType": "NUMBER"}],
                profiles=[{"key": "water-test", "name": "Water Test", "eventKind": "MEASUREMENT",
                           "fields": [{"definition": "ph", "bogus": True}]}],
            ),
            "assets[0].profiles[0].fields[0].bogus", id="profile-field",
        ),
        pytest.param(
            source_with(profiles=[{
                "key": "water-test", "name": "Water Test", "eventKind": "MEASUREMENT",
                "consumables": [{"key": "chlorine", "name": "Chlorine", "bogus": True}],
            }]),
            "assets[0].profiles[0].consumables[0].bogus", id="profile-consumable",
        ),
        pytest.param(
            source_with(events=[{"key": "e1", "kind": "NOTE", "occurredOn": "2026-09-21",
                                  "title": "Checked", "bogus": True}]),
            "assets[0].events[0].bogus", id="event",
        ),
        pytest.param(
            source_with(events=[{
                "key": "e1", "kind": "NOTE", "occurredOn": "2026-09-21", "title": "Checked",
                "consumables": [{"key": "c1", "name": "Chlorine", "quantity": 1, "bogus": True}],
            }]),
            "assets[0].events[0].consumables[0].bogus", id="consumable-use",
        ),
    ],
)
def test_rejects_unknown_key_in_any_object(source, expected_path):
    assert_rejects(source, expected_path)


# ---- order and accepted edge cases ---------------------------------------------------------------

def test_accepts_child_before_parent_order():
    source = minimal_source()
    source["assets"] = [
        {"key": "widget-fan", "name": "Widget Fan", "parent": "widget-mixer"},
        {"key": "widget-mixer", "name": "Widget Mixer"},
    ]
    result = parse_source(source)
    assert [a.key for a in result.assets] == ["widget-fan", "widget-mixer"]
    assert result.assets[0].parent == "widget-mixer"


def test_accepts_asOf_with_non_z_offset_and_converts():
    source = mutate(minimal_source(), "asOf", "2026-09-21T05:00:00+05:00")
    result = parse_source(source)
    assert result.asOf == datetime(2026, 9, 21, 0, 0, 0, tzinfo=timezone.utc)


def test_accepts_deferred_arbitrary_shape_and_reports():
    deferred = {"grouping": "seasonal", "unsupported": [1, 2, {"nested": True}]}
    source = mutate(minimal_source(), "deferred", deferred)
    result = parse_source(source)
    assert result.deferred == deferred

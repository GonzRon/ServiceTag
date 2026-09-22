"""The id-derivation test matrix from the task brief: one test per row kind, each recomputing the
documented `uuid5` derivation independently with `uuid.uuid5` rather than calling back into
`ids.py` for its own answer.
"""

from __future__ import annotations

import uuid

from servicetag_bundle.ids import namespace_of, row_id
from servicetag_bundle.source import parse_source

from conftest import _all_ids, _built, _ns, minimal_source


def _independent_ns(namespace: str) -> uuid.UUID:
    """The documented namespace derivation, computed without calling `ids.namespace_of`."""
    return uuid.uuid5(uuid.NAMESPACE_URL, "servicetag-bundle:" + namespace)


def test_namespace_of_matches_documented_derivation():
    source = parse_source(minimal_source())
    assert namespace_of(source) == _independent_ns("widget-farm")


def test_row_id_matches_documented_derivation_for_backup_set():
    ns = _independent_ns("widget-farm")
    assert row_id(ns, "set:stage-a") == str(uuid.uuid5(ns, "set:stage-a"))


def test_row_id_matches_documented_derivation_for_asset():
    ns = _independent_ns("widget-farm")
    assert row_id(ns, "asset:widget-mixer") == str(uuid.uuid5(ns, "asset:widget-mixer"))


def test_row_id_matches_documented_derivation_for_definition():
    ns = _independent_ns("widget-farm")
    key = "definition:widget-mixer/ph"
    assert row_id(ns, key) == str(uuid.uuid5(ns, key))


def test_row_id_matches_documented_derivation_for_profile():
    ns = _independent_ns("widget-farm")
    key = "profile:widget-mixer/water-test"
    assert row_id(ns, key) == str(uuid.uuid5(ns, key))


def test_row_id_matches_documented_derivation_for_profile_field():
    ns = _independent_ns("widget-farm")
    key = "profile-field:widget-mixer/water-test/ph"
    assert row_id(ns, key) == str(uuid.uuid5(ns, key))


def test_row_id_matches_documented_derivation_for_profile_consumable():
    ns = _independent_ns("widget-farm")
    key = "profile-consumable:widget-mixer/water-test/filter"
    assert row_id(ns, key) == str(uuid.uuid5(ns, key))


def test_row_id_matches_documented_derivation_for_event():
    ns = _independent_ns("widget-farm")
    key = "event:widget-mixer/e1"
    assert row_id(ns, key) == str(uuid.uuid5(ns, key))


def test_row_id_matches_documented_derivation_for_measurement():
    ns = _independent_ns("widget-farm")
    key = "measurement:widget-mixer/e1/ph"
    assert row_id(ns, key) == str(uuid.uuid5(ns, key))


def test_row_id_matches_documented_derivation_for_consumable_usage():
    ns = _independent_ns("widget-farm")
    key = "consumable-usage:widget-mixer/e1/filter"
    assert row_id(ns, key) == str(uuid.uuid5(ns, key))


def test_row_id_is_lowercase_hyphenated():
    ns = _independent_ns("widget-farm")
    result = row_id(ns, "asset:widget-mixer")
    assert result == result.lower()
    assert result.count("-") == 4


def test_different_namespace_yields_a_different_id_for_the_same_key():
    a = row_id(_independent_ns("widget-farm"), "asset:widget-mixer")
    b = row_id(_independent_ns("gadget-ranch"), "asset:widget-mixer")
    assert a != b


# ---- the key table bound to a real build ------------------------------------------------------
#
# The tests above prove the *formula*: a literal key in, a `uuid5` out. None of them look at a row
# `build_rows` actually emits, so a key format change in `rows.py` (`measurement:` -> `m:`, or
# dropping `<assetKey>` from a key) would leave every test above green. This test closes that gap:
# every id-bearing value anywhere in `test_rows._rich_source()`'s built rows must be exactly the
# `row_id` set of the plan's key table transcribed against that fixture's own keys -- no extra id,
# none missing.

def test_every_row_id_matches_the_documented_key_table():
    keys = (
        [f"asset:{k}" for k in ("widget-mixer", "widget-fan")]
        + [f"definition:widget-mixer/{k}"
           for k in ("ph", "temp", "notes_field", "running", "wear_pct")]
        + ["profile:widget-mixer/water-test"]
        + [f"profile-field:widget-mixer/water-test/{k}" for k in ("ph", "temp")]
        + ["profile-consumable:widget-mixer/water-test/filter"]
        + [f"event:widget-mixer/{k}" for k in ("e1", "e2", "e3")]
        + [f"measurement:widget-mixer/e1/{k}"
           for k in ("ph", "temp", "notes_field", "running")]
        + ["measurement:widget-mixer/e3/running",
           "consumable-usage:widget-mixer/e1/filter"]
    )
    assert _all_ids(_built()) == {row_id(_ns(), k) for k in keys}

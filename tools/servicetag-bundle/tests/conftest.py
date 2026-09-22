"""Shared fixtures for servicetag-bundle tests. Every fixture uses fictional nouns and a made-up
brand only — nothing here, or anywhere else in this repository, is the owner's private inventory.
"""

from __future__ import annotations

import copy
import uuid
from typing import Any

import pytest

from servicetag_bundle.rows import build_rows
from servicetag_bundle.source import parse_source


def minimal_source() -> dict[str, Any]:
    """A fresh minimal valid source dict."""
    return {
        "formatVersion": 1,
        "namespace": "widget-farm",
        "bundleKey": "stage-a",
        "asOf": "2026-09-21T00:00:00Z",
        "tzId": "America/Denver",
        "assets": [
            {
                "key": "widget-mixer",
                "name": "Widget Mixer 3000",
            },
        ],
    }


def source_with(**asset_overrides: Any) -> dict[str, Any]:
    """A minimal source whose single asset carries the given extra/overridden fields."""
    source = minimal_source()
    source["assets"][0].update(asset_overrides)
    return source


def _tokenize(path: str) -> list[Any]:
    tokens: list[Any] = []
    for part in path.split("."):
        while "[" in part:
            name, rest = part.split("[", 1)
            idx, rest = rest.split("]", 1)
            if name:
                tokens.append(name)
            tokens.append(int(idx))
            part = rest
        if part:
            tokens.append(part)
    return tokens


def mutate(source: dict[str, Any], path: str, value: Any) -> dict[str, Any]:
    """Deep-copies `source` and sets the dotted/bracketed `path` (e.g. `"assets[0].key"`) to
    `value`. Pass `mutate.DELETE` as `value` to remove the key instead of setting it."""
    result = copy.deepcopy(source)
    node = result
    tokens = _tokenize(path)
    for tok in tokens[:-1]:
        node = node[tok]
    last = tokens[-1]
    if value is mutate.DELETE:
        del node[last]
    else:
        node[last] = value
    return result


mutate.DELETE = object()


@pytest.fixture
def source_dict() -> dict[str, Any]:
    return minimal_source()


def rich_source() -> dict[str, Any]:
    """One asset with two definitions, a profile carrying a field and a consumable, and one event
    carrying a measurement and a consumable usage -- enough for every manifest count to be
    non-zero, including the nested tallies (`profileFields`, `profileConsumables`, `measurements`,
    `consumableUsages`). Shared by `test_archive.py` and `test_cli.py`, so it lives here rather
    than being imported test-module-to-test-module."""
    return {
        "formatVersion": 1,
        "namespace": "widget-farm",
        "bundleKey": "stage-a",
        "asOf": "2026-09-21T00:00:00Z",
        "tzId": "America/Denver",
        "assets": [
            {
                "key": "widget-mixer",
                "name": "Widget Mixer 3000",
                "definitions": [
                    {"key": "ph", "label": "pH", "valueType": "NUMBER"},
                    {"key": "temp", "label": "Temperature", "valueType": "NUMBER"},
                ],
                "profiles": [
                    {
                        "key": "water-test",
                        "name": "Water Test",
                        "eventKind": "MEASUREMENT",
                        "fields": [{"definition": "ph", "required": True}],
                        "consumables": [
                            {"key": "filter", "name": "Filter", "defaultQuantity": 2, "unit": "pcs"},
                        ],
                    },
                ],
                "events": [
                    {
                        "key": "e1",
                        "kind": "MEASUREMENT",
                        "occurredOn": "2026-09-20",
                        "title": "Morning check",
                        "profile": "water-test",
                        "values": {"ph": 7.5},
                        "consumables": [
                            {"key": "filter", "name": "Filter", "quantity": 1, "unit": "pcs"},
                        ],
                    },
                ],
            },
        ],
    }


# ---- the row-mapping fixture, shared by test_rows.py and test_ids.py --------------------------
#
# `test_ids.py`'s key-table test binds the id-derivation formula to a real build of this fixture,
# so it needs the same source, the same built rows and the same namespace `test_rows.py` uses.
# Kept here rather than imported test-module-to-test-module.

def _rich_source() -> dict[str, Any]:
    return {
        "formatVersion": 1,
        "namespace": "widget-farm",
        "bundleKey": "stage-a",
        "asOf": "2026-09-21T00:00:00Z",
        "tzId": "America/Denver",
        "assets": [
            {
                # listed before its parent -- proves the topological sort is real, not a pass
                # over the array.
                "key": "widget-fan",
                "name": "Widget Fan",
                "parent": "widget-mixer",
            },
            {
                "key": "widget-mixer",
                "name": "Widget Mixer 3000",
                "manufacturer": "Acme Gadgets",
                "purchasePriceMinor": 12345,
                "currency": "USD",
                "definitions": [
                    {"key": "ph", "label": "pH", "valueType": "NUMBER", "unit": "pH",
                     "rangeLow": 0, "rangeHigh": 14},
                    {"key": "temp", "label": "Temperature", "valueType": "NUMBER", "unit": "F"},
                    {"key": "notes_field", "label": "Notes", "valueType": "TEXT"},
                    {"key": "running", "label": "Running", "valueType": "BOOLEAN"},
                    {"key": "wear_pct", "label": "Wear", "valueType": "NUMBER", "kind": "DERIVED",
                     "formula": "PERCENT_DROP", "sourceA": "ph", "sourceB": "temp"},
                ],
                "profiles": [
                    {
                        "key": "water-test", "name": "Water Test", "eventKind": "MEASUREMENT",
                        "fields": [
                            {"definition": "ph", "required": True},
                            {"definition": "temp"},
                        ],
                        "consumables": [
                            {"key": "filter", "name": "Filter", "defaultQuantity": 2,
                             "unit": "pcs"},
                        ],
                    },
                ],
                "events": [
                    {
                        "key": "e1", "kind": "MEASUREMENT", "occurredOn": "2026-09-20",
                        "title": "Morning check", "profile": "water-test",
                        "values": {
                            "ph": 7.5, "temp": 68, "running": True,
                            "notes_field": "Looks clean",
                        },
                        "consumables": [
                            {"key": "filter", "name": "Filter", "quantity": 1, "unit": "pcs"},
                        ],
                    },
                    {
                        "key": "e2", "kind": "NOTE", "occurredOn": "2026-09-19",
                        "title": "Second check",
                    },
                    {
                        "key": "e3", "kind": "CUSTOM", "occurredOn": "2026-09-18",
                        "title": "TZ check", "tzId": "Europe/Berlin",
                        "values": {"running": False},
                    },
                ],
            },
        ],
    }


def _built(source_dict: dict[str, Any] | None = None) -> dict[str, list[dict[str, Any]]]:
    return build_rows(parse_source(source_dict if source_dict is not None else _rich_source()))


_ID_FIELD_NAMES = {
    "id", "assetId", "parentAssetId", "definitionId", "sourceAId", "sourceBId", "profileId",
}


def _all_ids(node: Any) -> set[str]:
    """Every value of a row-id field (never `tzId`, which is a timezone name, not an id), walked
    recursively."""
    found: set[str] = set()
    if isinstance(node, dict):
        for key, value in node.items():
            if key in _ID_FIELD_NAMES and isinstance(value, str):
                found.add(value)
            found |= _all_ids(value)
    elif isinstance(node, list):
        for item in node:
            found |= _all_ids(item)
    return found


def _ns() -> uuid.UUID:
    return uuid.uuid5(uuid.NAMESPACE_URL, "servicetag-bundle:widget-farm")

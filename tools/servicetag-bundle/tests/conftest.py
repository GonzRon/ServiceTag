"""Shared fixtures for servicetag-bundle tests. Every fixture uses fictional nouns and a made-up
brand only — nothing here, or anywhere else in this repository, is the owner's private inventory.
"""

from __future__ import annotations

import copy
from typing import Any

import pytest


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

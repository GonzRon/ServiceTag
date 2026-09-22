"""Deterministic row ids. Every id in the archive is a UUIDv5 derived from the source's declared
`namespace` and a stable string key -- never from the clock, the environment or randomness -- so
the same source bytes always produce the same ids (the id-derivation contract in the plan).
"""

from __future__ import annotations

import uuid

from .source import Source

_SEED_PREFIX = "servicetag-bundle:"


def namespace_of(source: Source) -> uuid.UUID:
    """The bundle's own UUIDv5 namespace, seeded from the source's declared `namespace` string:
    `uuid5(uuid.NAMESPACE_URL, "servicetag-bundle:" + namespace)`."""
    return uuid.uuid5(uuid.NAMESPACE_URL, _SEED_PREFIX + source.namespace)


def row_id(ns: uuid.UUID, key: str) -> str:
    """A row's id: `uuid5(ns, key)`, rendered lowercase hyphenated (`str(uuid.UUID)`'s own form)."""
    return str(uuid.uuid5(ns, key))

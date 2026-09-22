"""The validated source model: a private normalized inventory as a JSON document, turned into a
tree of frozen dataclasses. Every rejection mirrors a rule the app's own decoder
(`core/.../backup/BackupCodec.kt` and the model files it calls into) would apply once the archive
this source eventually builds is imported — this is the one gate, so a later task never has to
re-validate what already passed here.

No clock, no environment, no filesystem access beyond the one path `load_source` is given. The
dataclasses and the public entry points (`parse_source`, `load_source`) live here; the validation
rules themselves live in `validate.py` (imported lazily below, to keep the two modules from
importing each other at load time).
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

VALUE_TYPES = ("NUMBER", "TEXT", "BOOLEAN")
DEFINITION_KINDS = ("ENTERED", "DERIVED")
DERIVED_FORMULAS = ("PERCENT_DROP",)
EVENT_KINDS = (
    "MAINTENANCE", "INSPECTION", "MEASUREMENT", "TREATMENT", "INCIDENT",
    "REPLACEMENT", "SEASON_START", "SEASON_END", "NOTE", "CUSTOM",
)


class SourceError(ValueError):
    """A source document failed validation, or (raised by `load_source` itself) could not even be
    read or parsed. `path` is the JSON path of the offending value, dotted-and-bracketed like
    `assets[3].definitions[1].key` (`""` for the document itself) -- except when the failure is in
    reading or parsing the file rather than in its contents, in which case `path` is the source
    file's own filesystem path instead."""

    def __init__(self, path: str, message: str) -> None:
        super().__init__(f"{path}: {message}" if path else message)
        self.path = path
        self.message = message


# ---- the model --------------------------------------------------------------------------------

@dataclass(frozen=True)
class ProfileField:
    definition: str
    required: bool = False


@dataclass(frozen=True)
class ProfileConsumable:
    key: str
    name: str
    defaultQuantity: float | None = None
    unit: str = ""


@dataclass(frozen=True)
class Profile:
    key: str
    name: str
    eventKind: str
    defaultTitle: str
    fields: tuple[ProfileField, ...] = ()
    consumables: tuple[ProfileConsumable, ...] = ()


@dataclass(frozen=True)
class ConsumableUse:
    key: str
    name: str
    quantity: float
    unit: str = ""


@dataclass(frozen=True)
class Event:
    key: str
    kind: str
    occurredOn: str
    title: str
    notes: str = ""
    profile: str | None = None
    tzId: str = ""
    values: dict[str, Any] = field(default_factory=dict)
    consumables: tuple[ConsumableUse, ...] = ()


@dataclass(frozen=True)
class Definition:
    key: str
    label: str
    unit: str = ""
    valueType: str = "NUMBER"
    decimals: int = 0
    rangeLow: float | None = None
    rangeHigh: float | None = None
    isMeter: bool = False
    kind: str = "ENTERED"
    formula: str | None = None
    sourceA: str | None = None
    sourceB: str | None = None


@dataclass(frozen=True)
class Asset:
    key: str
    name: str
    category: str = ""
    description: str = ""
    notes: str = ""
    manufacturer: str = ""
    model: str = ""
    serialNumber: str = ""
    vendor: str = ""
    location: str = ""
    warrantyNotes: str = ""
    purchaseOn: str | None = None
    inServiceOn: str | None = None
    warrantyExpiresOn: str | None = None
    purchasePriceMinor: int | None = None
    currency: str | None = None
    seasonStartMmdd: str | None = None
    seasonEndMmdd: str | None = None
    parent: str | None = None
    definitions: tuple[Definition, ...] = ()
    profiles: tuple[Profile, ...] = ()
    events: tuple[Event, ...] = ()


@dataclass(frozen=True)
class Source:
    formatVersion: int
    namespace: str
    bundleKey: str
    asOf: datetime
    tzId: str
    deferred: Any = None
    assets: tuple[Asset, ...] = ()


# ---- public entry points ------------------------------------------------------------------------

def parse_source(obj: dict) -> Source:
    """Validate a decoded JSON document and return its `Source` tree, or raise `SourceError`."""
    from . import validate  # local: validate.py imports the model from this module

    return validate.parse(obj)


def load_source(path: Path) -> Source:
    """Read and validate a source document from `path`. A missing/unreadable file or malformed
    JSON is reported the same way a validation failure is -- as a `SourceError` naming `path` --
    so a caller (the CLI) needs only one `except SourceError` to report every way this can fail,
    never a bare traceback."""
    path = Path(path)
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as e:
        raise SourceError(str(path), e.strerror or str(e)) from e
    try:
        obj = json.loads(text)
    except json.JSONDecodeError as e:
        raise SourceError(str(path), f"not valid JSON: {e}") from e
    return parse_source(obj)

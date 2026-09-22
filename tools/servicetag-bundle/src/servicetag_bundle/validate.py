"""The rules `source.parse_source` runs. Split out of `source.py` because the model itself
(the dataclasses) is small and stable, while this file is where the shape of the app's decoder
(`core/.../backup/BackupCodec.kt` and the model files it calls into) actually gets encoded.
"""

from __future__ import annotations

import re
import zoneinfo
from datetime import date, datetime, timezone
from typing import Any

from .currencies import CURRENCIES
from .source import (
    Asset,
    ConsumableUse,
    Definition,
    DEFINITION_KINDS,
    DERIVED_FORMULAS,
    Event,
    EVENT_KINDS,
    Profile,
    ProfileConsumable,
    ProfileField,
    Source,
    SourceError,
    VALUE_TYPES,
)

# ---- shapes shared with the Kotlin side ------------------------------------------------------

# Asset/profile/event keys: `^[a-z0-9][a-z0-9-]{0,63}$` (source format table).
_SLUG_KEY = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")

# Definition keys: `KEY_PATTERN` in core/.../usecase/DefinitionCommands.kt.
_DEFINITION_KEY = re.compile(r"^[a-z][a-z0-9_]{0,39}$")

# `YYYY-MM-DD`, anchored — never `date.fromisoformat`, which also accepts `20260921` and
# ISO week dates (`2026-W38-1`) that `core/.../usecase/AssetCommands.kt`'s `isIsoDate`
# (`LocalDate.parse`) rejects.
_ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

# `MM-DD`, anchored — core/.../model/Season.kt's `MMDD` regex.
_MMDD = re.compile(r"^\d{2}-\d{2}$")

# Shape only; membership in `CURRENCIES` is the real gate (core/.../model/Money.kt `isCode` is
# shape-only too, but `Money.fractionDigits` is what actually resolves a code).
_CURRENCY_SHAPE = re.compile(r"^[A-Z]{3}$")

_SOURCE_KEYS = {"formatVersion", "namespace", "bundleKey", "asOf", "tzId", "deferred", "assets"}
_ASSET_KEYS = {
    "key", "name", "category", "description", "notes", "manufacturer", "model", "serialNumber",
    "vendor", "location", "warrantyNotes", "purchaseOn", "inServiceOn", "warrantyExpiresOn",
    "purchasePriceMinor", "currency", "seasonStartMmdd", "seasonEndMmdd", "parent",
    "definitions", "profiles", "events",
}
_DEFINITION_KEYS = {
    "key", "label", "unit", "valueType", "decimals", "rangeLow", "rangeHigh", "isMeter", "kind",
    "formula", "sourceA", "sourceB",
}
_PROFILE_KEYS = {"key", "name", "eventKind", "defaultTitle", "fields", "consumables"}
_PROFILE_FIELD_KEYS = {"definition", "required"}
_PROFILE_CONSUMABLE_KEYS = {"key", "name", "defaultQuantity", "unit"}
_EVENT_KEYS = {
    "key", "kind", "occurredOn", "title", "notes", "profile", "tzId", "values", "consumables",
}
_CONSUMABLE_USE_KEYS = {"key", "name", "quantity", "unit"}


# ---- small parsing helpers ---------------------------------------------------------------------

def _join(path: str, key: str) -> str:
    return f"{path}.{key}" if path else key


def _is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _check_keys(obj: Any, allowed: set[str], required: set[str], path: str) -> None:
    if not isinstance(obj, dict):
        raise SourceError(path, "must be an object")
    unknown = set(obj) - allowed
    if unknown:
        raise SourceError(_join(path, sorted(unknown)[0]), "unknown key")
    missing = required - set(obj)
    if missing:
        raise SourceError(_join(path, sorted(missing)[0]), "missing required key")


def _require_str(obj: dict, key: str, path: str) -> str:
    value = obj.get(key)
    p = _join(path, key)
    if not isinstance(value, str):
        raise SourceError(p, "must be a string")
    if not value.strip():
        raise SourceError(p, "must not be blank")
    return value


def _optional_str(obj: dict, key: str, path: str, default: str = "") -> str:
    if obj.get(key) is None:
        return default
    value = obj[key]
    if not isinstance(value, str):
        raise SourceError(_join(path, key), "must be a string")
    return value


def _parse_calendar_date(value: Any, path: str) -> str:
    if not isinstance(value, str) or not _ISO_DATE.match(value):
        raise SourceError(path, "must be an ISO calendar date YYYY-MM-DD")
    year, month, day = int(value[0:4]), int(value[5:7]), int(value[8:10])
    try:
        date(year, month, day)
    except ValueError:
        raise SourceError(path, "must be a real calendar date") from None
    return value


def _parse_mmdd(value: Any, path: str) -> str:
    if not isinstance(value, str) or not _MMDD.match(value):
        raise SourceError(path, "must be MM-DD")
    month, day = int(value[0:2]), int(value[3:5])
    try:
        # 2000 is a leap year, so this mirrors java.time.MonthDay.of's year-independent check
        # (02-29 is always a valid MM-DD; only matching a season to a real year needs one).
        date(2000, month, day)
    except ValueError:
        raise SourceError(path, "must be a real calendar day") from None
    return value


def _parse_as_of(value: Any, path: str) -> datetime:
    if not isinstance(value, str):
        raise SourceError(path, "must be a string")
    text = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        raise SourceError(path, "must be an ISO-8601 instant") from None
    if parsed.tzinfo is None:
        raise SourceError(path, "must have an explicit offset")
    return parsed.astimezone(timezone.utc)


def _validate_tz(value: Any, path: str) -> str:
    if not isinstance(value, str) or value not in zoneinfo.available_timezones():
        raise SourceError(path, "must be a known IANA timezone id")
    return value


def _validate_currency(value: Any, path: str) -> str:
    if not isinstance(value, str) or not _CURRENCY_SHAPE.match(value):
        raise SourceError(path, "must be a 3-letter currency code")
    if value not in CURRENCIES:
        raise SourceError(path, "must be a currency in the tool's allow-list")
    return value


def _validate_unique(items: list, key_attr: str, path_prefix: str, label: str) -> None:
    seen: set[str] = set()
    for i, item in enumerate(items):
        k = getattr(item, key_attr)
        if k in seen:
            raise SourceError(_join(f"{path_prefix}[{i}]", key_attr), f"duplicate {label} key")
        seen.add(k)


# ---- definitions --------------------------------------------------------------------------------

def _parse_definition(obj: Any, asset_path: str, index: int) -> Definition:
    p = f"{asset_path}.definitions[{index}]"
    _check_keys(obj, _DEFINITION_KEYS, {"key", "label", "valueType"}, p)

    key = _require_str(obj, "key", p)
    if not _DEFINITION_KEY.match(key):
        raise SourceError(_join(p, "key"), "must match ^[a-z][a-z0-9_]{0,39}$")
    label = _require_str(obj, "label", p)
    unit = _optional_str(obj, "unit", p)

    value_type = obj.get("valueType")
    if value_type not in VALUE_TYPES:
        raise SourceError(_join(p, "valueType"), "must be NUMBER, TEXT or BOOLEAN")

    decimals = obj.get("decimals", 0)
    if not isinstance(decimals, int) or isinstance(decimals, bool) or not (0 <= decimals <= 4):
        raise SourceError(_join(p, "decimals"), "must be an integer 0-4")

    range_low = obj.get("rangeLow")
    range_high = obj.get("rangeHigh")
    if range_low is not None or range_high is not None:
        if value_type != "NUMBER":
            bad = "rangeLow" if range_low is not None else "rangeHigh"
            raise SourceError(_join(p, bad), "rangeLow/rangeHigh only apply to a NUMBER definition")
        for name, v in (("rangeLow", range_low), ("rangeHigh", range_high)):
            if v is not None and not _is_number(v):
                raise SourceError(_join(p, name), "must be a number")
        if range_low is not None and range_high is not None and range_low > range_high:
            raise SourceError(_join(p, "rangeHigh"), "rangeLow must be <= rangeHigh")

    is_meter = obj.get("isMeter", False)
    if not isinstance(is_meter, bool):
        raise SourceError(_join(p, "isMeter"), "must be a boolean")

    kind = obj.get("kind", "ENTERED")
    if kind not in DEFINITION_KINDS:
        raise SourceError(_join(p, "kind"), "must be ENTERED or DERIVED")

    formula = obj.get("formula")
    source_a = obj.get("sourceA")
    source_b = obj.get("sourceB")

    if kind == "DERIVED":
        if value_type != "NUMBER":
            raise SourceError(_join(p, "valueType"), "a DERIVED definition must be NUMBER")
        if is_meter:
            raise SourceError(_join(p, "isMeter"), "a DERIVED definition cannot be a meter")
        missing = next((n for n, v in (("formula", formula), ("sourceA", source_a),
                                        ("sourceB", source_b)) if v is None), None)
        if missing is not None:
            raise SourceError(_join(p, missing),
                               "a DERIVED definition needs formula, sourceA and sourceB")
        if formula not in DERIVED_FORMULAS:
            raise SourceError(_join(p, "formula"), "unknown formula")
        for name, v in (("sourceA", source_a), ("sourceB", source_b)):
            if not isinstance(v, str):
                raise SourceError(_join(p, name), "must be a definition key")
        if source_a == source_b:
            raise SourceError(_join(p, "sourceB"), "sourceA and sourceB must be different")
    elif formula is not None or source_a is not None or source_b is not None:
        raise SourceError(p, "only a DERIVED definition may carry formula/sourceA/sourceB")

    return Definition(
        key=key, label=label, unit=unit, valueType=value_type, decimals=decimals,
        rangeLow=range_low, rangeHigh=range_high, isMeter=is_meter, kind=kind,
        formula=formula, sourceA=source_a, sourceB=source_b,
    )


def _validate_definitions(definitions: list[Definition], asset_path: str) -> dict[str, Definition]:
    _validate_unique(definitions, "key", f"{asset_path}.definitions", "definition")
    by_key = {d.key: d for d in definitions}
    for i, d in enumerate(definitions):
        if d.kind != "DERIVED":
            continue
        p = f"{asset_path}.definitions[{i}]"
        for attr, source_key in (("sourceA", d.sourceA), ("sourceB", d.sourceB)):
            source = by_key.get(source_key)
            reason = None
            if source is None:
                reason = "must reference a definition of the same asset"
            elif source.kind != "ENTERED":
                reason = "must reference an ENTERED definition"
            elif source.valueType != "NUMBER":
                reason = "must reference a NUMBER definition"
            elif source.isMeter:
                reason = "must not reference a meter definition"
            if reason is not None:
                raise SourceError(_join(p, attr), reason)
    return by_key


# ---- profiles -----------------------------------------------------------------------------------

def _parse_profile_field(obj: Any, profile_path: str, index: int,
                          definitions_by_key: dict[str, Definition],
                          seen: set[str]) -> ProfileField:
    p = f"{profile_path}.fields[{index}]"
    _check_keys(obj, _PROFILE_FIELD_KEYS, {"definition"}, p)
    definition_key = _require_str(obj, "definition", p)
    definition = definitions_by_key.get(definition_key)
    if definition is None or definition.kind != "ENTERED":
        raise SourceError(_join(p, "definition"),
                           "must reference an ENTERED definition of the same asset")
    if definition_key in seen:
        raise SourceError(_join(p, "definition"), "definition listed twice in this profile")
    seen.add(definition_key)
    required = obj.get("required", False)
    if not isinstance(required, bool):
        raise SourceError(_join(p, "required"), "must be a boolean")
    return ProfileField(definition=definition_key, required=required)


def _parse_profile_consumable(obj: Any, profile_path: str, index: int) -> ProfileConsumable:
    p = f"{profile_path}.consumables[{index}]"
    _check_keys(obj, _PROFILE_CONSUMABLE_KEYS, {"key", "name"}, p)
    key = _require_str(obj, "key", p)
    name = _require_str(obj, "name", p)
    default_qty = obj.get("defaultQuantity")
    if default_qty is not None and not _is_number(default_qty):
        raise SourceError(_join(p, "defaultQuantity"), "must be a number or null")
    unit = _optional_str(obj, "unit", p)
    return ProfileConsumable(key=key, name=name, defaultQuantity=default_qty, unit=unit)


def _parse_profile(obj: Any, asset_path: str, index: int,
                    definitions_by_key: dict[str, Definition]) -> Profile:
    p = f"{asset_path}.profiles[{index}]"
    _check_keys(obj, _PROFILE_KEYS, {"key", "name", "eventKind"}, p)

    key = _require_str(obj, "key", p)
    if not _SLUG_KEY.match(key):
        raise SourceError(_join(p, "key"), "must match ^[a-z0-9][a-z0-9-]{0,63}$")
    name = _require_str(obj, "name", p)

    event_kind = obj.get("eventKind")
    if event_kind not in EVENT_KINDS:
        raise SourceError(_join(p, "eventKind"), "must be a known event kind")
    default_title = _optional_str(obj, "defaultTitle", p, default=name)

    fields_raw = obj.get("fields", [])
    if not isinstance(fields_raw, list):
        raise SourceError(_join(p, "fields"), "must be an array")
    seen_definitions: set[str] = set()
    fields = tuple(
        _parse_profile_field(f, p, i, definitions_by_key, seen_definitions)
        for i, f in enumerate(fields_raw)
    )

    consumables_raw = obj.get("consumables", [])
    if not isinstance(consumables_raw, list):
        raise SourceError(_join(p, "consumables"), "must be an array")
    consumables = tuple(_parse_profile_consumable(c, p, i) for i, c in enumerate(consumables_raw))
    _validate_unique(list(consumables), "key", f"{p}.consumables", "consumable")

    return Profile(key=key, name=name, eventKind=event_kind, defaultTitle=default_title,
                    fields=fields, consumables=consumables)


# ---- events ---------------------------------------------------------------------------------

def _parse_consumable_use(obj: Any, event_path: str, index: int) -> ConsumableUse:
    p = f"{event_path}.consumables[{index}]"
    _check_keys(obj, _CONSUMABLE_USE_KEYS, {"key", "name", "quantity"}, p)
    key = _require_str(obj, "key", p)
    name = _require_str(obj, "name", p)
    quantity = obj.get("quantity")
    if not _is_number(quantity):
        raise SourceError(_join(p, "quantity"), "must be a number")
    unit = _optional_str(obj, "unit", p)
    return ConsumableUse(key=key, name=name, quantity=quantity, unit=unit)


def _parse_event_value(value_key: str, value: Any, event_path: str,
                        definitions_by_key: dict[str, Definition]) -> Any:
    p = f"{event_path}.values.{value_key}"
    definition = definitions_by_key.get(value_key)
    if definition is None or definition.kind != "ENTERED":
        raise SourceError(p, "must reference an ENTERED definition of the same asset")
    if definition.valueType == "NUMBER":
        if not _is_number(value):
            raise SourceError(p, "must be a number")
    elif definition.valueType == "TEXT":
        if not isinstance(value, str) or not value.strip():
            raise SourceError(p, "must be a non-blank string")
    elif definition.valueType == "BOOLEAN":
        if not isinstance(value, bool):
            raise SourceError(p, "must be a boolean")
    return value


def _parse_event(obj: Any, asset_path: str, index: int, source_tz: str,
                  definitions_by_key: dict[str, Definition],
                  profiles_by_key: dict[str, Profile]) -> Event:
    p = f"{asset_path}.events[{index}]"
    _check_keys(obj, _EVENT_KEYS, {"key", "kind", "occurredOn", "title"}, p)

    key = _require_str(obj, "key", p)
    if not _SLUG_KEY.match(key):
        raise SourceError(_join(p, "key"), "must match ^[a-z0-9][a-z0-9-]{0,63}$")

    kind = obj.get("kind")
    if kind not in EVENT_KINDS:
        raise SourceError(_join(p, "kind"), "must be a known event kind")

    occurred_on = _parse_calendar_date(obj.get("occurredOn"), _join(p, "occurredOn"))
    title = _require_str(obj, "title", p)
    notes = _optional_str(obj, "notes", p)

    profile_key = obj.get("profile")
    if profile_key is not None:
        if not isinstance(profile_key, str) or profile_key not in profiles_by_key:
            raise SourceError(_join(p, "profile"), "must reference a profile of the same asset")

    tz_value = obj.get("tzId")
    tz_id = source_tz if tz_value is None else _validate_tz(tz_value, _join(p, "tzId"))

    values_raw = obj.get("values", {})
    if not isinstance(values_raw, dict):
        raise SourceError(_join(p, "values"), "must be an object")
    values = {
        value_key: _parse_event_value(value_key, value, p, definitions_by_key)
        for value_key, value in values_raw.items()
    }

    consumables_raw = obj.get("consumables", [])
    if not isinstance(consumables_raw, list):
        raise SourceError(_join(p, "consumables"), "must be an array")
    consumables = tuple(_parse_consumable_use(c, p, i) for i, c in enumerate(consumables_raw))
    _validate_unique(list(consumables), "key", f"{p}.consumables", "consumable")

    return Event(key=key, kind=kind, occurredOn=occurred_on, title=title, notes=notes,
                 profile=profile_key, tzId=tz_id, values=values, consumables=consumables)


# ---- assets ---------------------------------------------------------------------------------

def _parse_asset(obj: Any, index: int, source_tz: str) -> Asset:
    p = f"assets[{index}]"
    _check_keys(obj, _ASSET_KEYS, {"key", "name"}, p)

    key = _require_str(obj, "key", p)
    if not _SLUG_KEY.match(key):
        raise SourceError(_join(p, "key"), "must match ^[a-z0-9][a-z0-9-]{0,63}$")
    name = _require_str(obj, "name", p)

    category = _optional_str(obj, "category", p)
    description = _optional_str(obj, "description", p)
    notes = _optional_str(obj, "notes", p)
    manufacturer = _optional_str(obj, "manufacturer", p)
    model = _optional_str(obj, "model", p)
    serial_number = _optional_str(obj, "serialNumber", p)
    vendor = _optional_str(obj, "vendor", p)
    location = _optional_str(obj, "location", p)
    warranty_notes = _optional_str(obj, "warrantyNotes", p)

    purchase_on = obj.get("purchaseOn")
    if purchase_on is not None:
        purchase_on = _parse_calendar_date(purchase_on, _join(p, "purchaseOn"))
    in_service_on = obj.get("inServiceOn")
    if in_service_on is not None:
        in_service_on = _parse_calendar_date(in_service_on, _join(p, "inServiceOn"))
    warranty_expires_on = obj.get("warrantyExpiresOn")
    if warranty_expires_on is not None:
        warranty_expires_on = _parse_calendar_date(warranty_expires_on, _join(p, "warrantyExpiresOn"))

    price = obj.get("purchasePriceMinor")
    currency = obj.get("currency")
    if price is not None:
        if not isinstance(price, int) or isinstance(price, bool):
            raise SourceError(_join(p, "purchasePriceMinor"), "must be an integer")
        if price < 0:
            raise SourceError(_join(p, "purchasePriceMinor"), "must not be negative")
        if currency is None:
            raise SourceError(_join(p, "currency"), "required when purchasePriceMinor is present")
    if currency is not None:
        currency = _validate_currency(currency, _join(p, "currency"))

    season_start = obj.get("seasonStartMmdd")
    season_end = obj.get("seasonEndMmdd")
    if (season_start is None) != (season_end is None):
        bad = "seasonStartMmdd" if season_start is None else "seasonEndMmdd"
        raise SourceError(_join(p, bad), "seasonStartMmdd and seasonEndMmdd must both be set "
                                          "or both omitted")
    if season_start is not None:
        season_start = _parse_mmdd(season_start, _join(p, "seasonStartMmdd"))
    if season_end is not None:
        season_end = _parse_mmdd(season_end, _join(p, "seasonEndMmdd"))

    parent = obj.get("parent")
    if parent is not None and not isinstance(parent, str):
        raise SourceError(_join(p, "parent"), "must be a string")

    definitions_raw = obj.get("definitions", [])
    if not isinstance(definitions_raw, list):
        raise SourceError(_join(p, "definitions"), "must be an array")
    definitions = tuple(_parse_definition(d, p, i) for i, d in enumerate(definitions_raw))
    definitions_by_key = _validate_definitions(list(definitions), p)

    profiles_raw = obj.get("profiles", [])
    if not isinstance(profiles_raw, list):
        raise SourceError(_join(p, "profiles"), "must be an array")
    profiles = tuple(_parse_profile(pr, p, i, definitions_by_key) for i, pr in enumerate(profiles_raw))
    _validate_unique(list(profiles), "key", f"{p}.profiles", "profile")
    profiles_by_key = {pr.key: pr for pr in profiles}

    events_raw = obj.get("events", [])
    if not isinstance(events_raw, list):
        raise SourceError(_join(p, "events"), "must be an array")
    events = tuple(
        _parse_event(e, p, i, source_tz, definitions_by_key, profiles_by_key)
        for i, e in enumerate(events_raw)
    )
    _validate_unique(list(events), "key", f"{p}.events", "event")

    return Asset(
        key=key, name=name, category=category, description=description, notes=notes,
        manufacturer=manufacturer, model=model, serialNumber=serial_number, vendor=vendor,
        location=location, warrantyNotes=warranty_notes, purchaseOn=purchase_on,
        inServiceOn=in_service_on, warrantyExpiresOn=warranty_expires_on,
        purchasePriceMinor=price, currency=currency, seasonStartMmdd=season_start,
        seasonEndMmdd=season_end, parent=parent, definitions=definitions, profiles=profiles,
        events=events,
    )


def _validate_parents(assets: tuple[Asset, ...]) -> None:
    by_key = {a.key: a for a in assets}
    index_by_key = {a.key: i for i, a in enumerate(assets)}
    for i, a in enumerate(assets):
        if a.parent is not None and a.parent not in by_key:
            raise SourceError(f"assets[{i}].parent", "must reference a known asset key")

    UNVISITED, VISITING, DONE = 0, 1, 2
    state = {a.key: UNVISITED for a in assets}

    def visit(key: str) -> None:
        state[key] = VISITING
        parent = by_key[key].parent
        if parent is not None:
            if state[parent] == VISITING:
                raise SourceError(f"assets[{index_by_key[key]}].parent",
                                   "parent hierarchy has a cycle")
            if state[parent] == UNVISITED:
                visit(parent)
        state[key] = DONE

    for a in assets:
        if state[a.key] == UNVISITED:
            visit(a.key)


# ---- top level ------------------------------------------------------------------------------

def parse(obj: dict) -> Source:
    """`source.parse_source`'s implementation."""
    _check_keys(obj, _SOURCE_KEYS, {"formatVersion", "namespace", "bundleKey", "asOf", "tzId",
                                     "assets"}, "")

    format_version = obj.get("formatVersion")
    if format_version != 1:
        raise SourceError("formatVersion", "must be 1")

    namespace = _require_str(obj, "namespace", "")
    bundle_key = _require_str(obj, "bundleKey", "")
    tz_id = _validate_tz(obj.get("tzId"), "tzId")
    as_of = _parse_as_of(obj.get("asOf"), "asOf")
    deferred = obj.get("deferred")
    if deferred is not None and not isinstance(deferred, dict):
        raise SourceError("deferred", "must be an object")

    assets_raw = obj.get("assets")
    if not isinstance(assets_raw, list):
        raise SourceError("assets", "must be an array")
    assets = tuple(_parse_asset(a, i, tz_id) for i, a in enumerate(assets_raw))
    _validate_unique(list(assets), "key", "assets", "asset")
    _validate_parents(assets)

    return Source(formatVersion=format_version, namespace=namespace, bundleKey=bundle_key,
                  asOf=as_of, tzId=tz_id, deferred=deferred, assets=assets)

"""Maps a validated `Source` tree onto the rows of a format-5 `BackupData` (`data.json`'s tables),
as plain dicts -- exactly the field names and value shapes of the `@Serializable` DTOs in
`core/.../backup/BackupFormat.kt`. No clock, environment or filesystem access: every id comes from
`ids.row_id`, every timestamp from the source's `asOf`.
"""

from __future__ import annotations

from collections import defaultdict
from datetime import datetime, timezone
from typing import Any

from .ids import namespace_of, row_id
from .source import Asset, Event, Profile, Source

_EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


def epoch_millis(dt: datetime) -> int:
    """`dt` (already UTC, per `source._parse_as_of`) as epoch milliseconds -- integer arithmetic
    only, never via a float timestamp. Sub-millisecond precision is truncated toward the lower
    millisecond. Public: `archive.py`'s manifest `createdAt` must be the same number as every
    row's `createdAt`/`updatedAt`, so both share this one function rather than two copies that
    could drift apart."""
    delta = dt - _EPOCH
    return delta.days * 86_400_000 + delta.seconds * 1000 + delta.microseconds // 1000


def _num(value: Any) -> float:
    """A JSON number (possibly a Python `int`, since the source's own dataclasses don't force
    `float`) as a `float`, with `-0.0` normalised to `0.0`."""
    result = float(value)
    return 0.0 if result == 0.0 else result


def _topological_assets(assets: tuple[Asset, ...]) -> list[Asset]:
    """Assets in a stable topological order: every parent before its children, source order
    preserved among siblings (a source key may declare `parent` before or after the asset it
    names -- `test_accepts_child_before_parent_order` in Task 1 -- so this is a real topological
    sort, not just a pass over the array)."""
    by_parent: dict[str | None, list[Asset]] = defaultdict(list)
    for asset in assets:
        by_parent[asset.parent].append(asset)

    ordered: list[Asset] = []

    def visit(parent_key: str | None) -> None:
        for asset in by_parent.get(parent_key, []):
            ordered.append(asset)
            visit(asset.key)

    visit(None)
    return ordered


def build_rows(source: Source) -> dict[str, list[dict[str, Any]]]:
    """The `BackupData` dict: exactly the keys `assets`, `nfcTags`, `externalLinks`,
    `measurementDefinitions`, `eventProfiles`, `assetEvents`, `attachments`, each a list of plain
    dicts. Assets are emitted parents-first."""
    ns = namespace_of(source)
    as_of_millis = epoch_millis(source.asOf)
    ordered_assets = _topological_assets(source.assets)

    asset_ids = {asset.key: row_id(ns, f"asset:{asset.key}") for asset in source.assets}

    asset_rows: list[dict[str, Any]] = []
    definition_rows: list[dict[str, Any]] = []
    profile_rows: list[dict[str, Any]] = []
    event_rows: list[dict[str, Any]] = []

    for asset in ordered_assets:
        asset_rows.append(_asset_row(ns, asset, asset_ids, as_of_millis))

        for index, definition in enumerate(asset.definitions):
            definition_rows.append(
                _definition_row(ns, asset, definition, index, asset_ids, as_of_millis)
            )

        profile_ids = {
            profile.key: row_id(ns, f"profile:{asset.key}/{profile.key}")
            for profile in asset.profiles
        }
        for index, profile in enumerate(asset.profiles):
            profile_rows.append(
                _profile_row(ns, asset, profile, index, asset_ids, as_of_millis)
            )

        definition_sort_order = {d.key: i for i, d in enumerate(asset.definitions)}
        definitions_by_key = {d.key: d for d in asset.definitions}
        for event in asset.events:
            event_rows.append(
                _event_row(
                    ns, asset, event, asset_ids, profile_ids, definitions_by_key,
                    definition_sort_order, as_of_millis,
                )
            )

    return {
        "assets": asset_rows,
        "nfcTags": [],
        "externalLinks": [],
        "measurementDefinitions": definition_rows,
        "eventProfiles": profile_rows,
        "assetEvents": event_rows,
        "attachments": [],
    }


# ---- assets --------------------------------------------------------------------------------------

def _asset_row(
    ns: Any, asset: Asset, asset_ids: dict[str, str], as_of_millis: int
) -> dict[str, Any]:
    return {
        "id": asset_ids[asset.key],
        "name": asset.name,
        "description": asset.description,
        "category": asset.category,
        "notes": asset.notes,
        "status": "ACTIVE",
        "createdAt": as_of_millis,
        "updatedAt": as_of_millis,
        "templateKey": None,
        "manufacturer": asset.manufacturer,
        "model": asset.model,
        "serialNumber": asset.serialNumber,
        "purchaseOn": asset.purchaseOn,
        "inServiceOn": asset.inServiceOn,
        "purchasePriceMinor": (
            None if asset.purchasePriceMinor is None else int(asset.purchasePriceMinor)
        ),
        "currency": asset.currency,
        "vendor": asset.vendor,
        "location": asset.location,
        "warrantyExpiresOn": asset.warrantyExpiresOn,
        "warrantyNotes": asset.warrantyNotes,
        "retiredOn": None,
        "parentAssetId": None if asset.parent is None else asset_ids[asset.parent],
        "seasonStartMmdd": asset.seasonStartMmdd,
        "seasonEndMmdd": asset.seasonEndMmdd,
    }


# ---- definitions -----------------------------------------------------------------------------

def _definition_row(
    ns: Any, asset: Asset, definition: Any, index: int, asset_ids: dict[str, str],
    as_of_millis: int,
) -> dict[str, Any]:
    def _definition_id(key: str) -> str:
        return row_id(ns, f"definition:{asset.key}/{key}")

    return {
        "id": _definition_id(definition.key),
        "assetId": asset_ids[asset.key],
        "key": definition.key,
        "label": definition.label,
        "unit": definition.unit,
        "valueType": definition.valueType,
        "decimals": int(definition.decimals),
        "rangeLow": None if definition.rangeLow is None else _num(definition.rangeLow),
        "rangeHigh": None if definition.rangeHigh is None else _num(definition.rangeHigh),
        "isMeter": definition.isMeter,
        "sortOrder": index,
        "archivedAt": None,
        "createdAt": as_of_millis,
        "updatedAt": as_of_millis,
        "kind": definition.kind,
        "formula": definition.formula,
        "sourceAId": None if definition.sourceA is None else _definition_id(definition.sourceA),
        "sourceBId": None if definition.sourceB is None else _definition_id(definition.sourceB),
    }


# ---- profiles ------------------------------------------------------------------------------------

def _profile_row(
    ns: Any, asset: Asset, profile: Profile, index: int, asset_ids: dict[str, str],
    as_of_millis: int,
) -> dict[str, Any]:
    fields = [
        {
            "id": row_id(ns, f"profile-field:{asset.key}/{profile.key}/{pf.definition}"),
            "definitionId": row_id(ns, f"definition:{asset.key}/{pf.definition}"),
            "required": pf.required,
            "sortOrder": field_index,
        }
        for field_index, pf in enumerate(profile.fields)
    ]
    consumables = [
        {
            "id": row_id(ns, f"profile-consumable:{asset.key}/{profile.key}/{pc.key}"),
            "name": pc.name,
            "defaultQuantity": None if pc.defaultQuantity is None else _num(pc.defaultQuantity),
            "unit": pc.unit,
            "sortOrder": consumable_index,
        }
        for consumable_index, pc in enumerate(profile.consumables)
    ]
    return {
        "id": row_id(ns, f"profile:{asset.key}/{profile.key}"),
        "assetId": asset_ids[asset.key],
        "name": profile.name,
        "eventKind": profile.eventKind,
        "defaultTitle": profile.defaultTitle,
        "templateKey": None,
        "sortOrder": index,
        "archivedAt": None,
        "createdAt": as_of_millis,
        "updatedAt": as_of_millis,
        "fields": fields,
        "consumables": consumables,
    }


# ---- events --------------------------------------------------------------------------------------

def _measurement_row(
    ns: Any, asset: Asset, event: Event, definition_key: str, definitions_by_key: dict[str, Any],
    definition_sort_order: dict[str, int],
) -> dict[str, Any]:
    definition = definitions_by_key[definition_key]
    value = event.values[definition_key]
    if definition.valueType == "NUMBER":
        value_num: float | None = _num(value)
        value_text: str | None = None
    elif definition.valueType == "TEXT":
        value_num = None
        value_text = value
    else:  # BOOLEAN
        value_num = 1.0 if value else 0.0
        value_text = None
    return {
        "id": row_id(ns, f"measurement:{asset.key}/{event.key}/{definition_key}"),
        "definitionId": row_id(ns, f"definition:{asset.key}/{definition_key}"),
        "valueNum": value_num,
        "valueText": value_text,
        "unit": definition.unit,
        "sortOrder": definition_sort_order[definition_key],
    }


def _event_row(
    ns: Any, asset: Asset, event: Event, asset_ids: dict[str, str], profile_ids: dict[str, str],
    definitions_by_key: dict[str, Any], definition_sort_order: dict[str, int],
    as_of_millis: int,
) -> dict[str, Any]:
    # `event.values` is a mapping with no order of its own -- iterate the asset's own definition
    # order (which carries `sortOrder`) instead of the dict's insertion order, so a source that
    # reorders the keys of a `values` object produces an identical row.
    measurements = [
        _measurement_row(ns, asset, event, definition.key, definitions_by_key, definition_sort_order)
        for definition in asset.definitions
        if definition.key in event.values
    ]
    consumables = [
        {
            "id": row_id(ns, f"consumable-usage:{asset.key}/{event.key}/{c.key}"),
            "name": c.name,
            "quantity": _num(c.quantity),
            "unit": c.unit,
            "sortOrder": index,
        }
        for index, c in enumerate(event.consumables)
    ]
    return {
        "id": row_id(ns, f"event:{asset.key}/{event.key}"),
        "assetId": asset_ids[asset.key],
        "kind": event.kind,
        "title": event.title,
        "profileId": None if event.profile is None else profile_ids[event.profile],
        "occurredOn": event.occurredOn,
        "occurredTime": None,
        "tzId": event.tzId,
        "notes": event.notes,
        "source": "IMPORT",
        "sourceRef": f"{asset.key}/{event.key}",
        "createdAt": as_of_millis,
        "updatedAt": as_of_millis,
        "measurements": measurements,
        "consumables": consumables,
    }

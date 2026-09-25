"""The command key lists the overlay tools build their bodies from — a **vendored copy** of three
entries of the repository's golden `docs/api/command-shapes.json` (master plan dec. 48, ruled M15).

Nothing reads that file at runtime: this package is installed and run on its own, and a server that
opened a repository path would stop working the moment it ran anywhere but a checkout. So the three
entries the overlays need — `asset`, `schedule` and `healthSubject` — are written out here as
constants, and `tests/test_command_shapes.py` holds them equal to the golden file, key for key and in
order. When the app's command changes, the JVM test on the app side fails until the golden file
moves, and the pytest here fails until this copy moves with it.

`update_asset`, `update_schedule` and `update_health_subject` send **every key listed here** for
their command, each read off the row (under its row name, `SCHEDULE_ROW_TO_COMMAND` for the
schedule's two renamed targets), with the caller's arguments laid over it — so a key the app adds is
carried by adding it here, with no change to any tool.
"""

from __future__ import annotations

from types import MappingProxyType
from typing import Any, Mapping

ASSET_KEYS: tuple[str, ...] = (
    "name", "category", "description", "notes", "manufacturer", "model", "serialNumber",
    "purchaseOn", "inServiceOn", "purchasePriceMinor", "currency", "vendor", "location",
    "warrantyExpiresOn", "warrantyNotes", "parentAssetId", "seasonStartMmdd", "seasonEndMmdd",
    "templateKey",
)
"""The asset command. Its season pair is the one compatibility input it keeps; `templateKey` is
read on a create and ignored on an edit, and the asset row reports it, so the overlay carries it."""

SCHEDULE_KEYS: tuple[str, ...] = (
    "targetAssetId", "targetGroupId", "title", "description", "timeInterval", "timeUnit",
    "timeBasis", "anchorOn", "leadDays", "meterDefinitionId", "meterInterval", "anchorMeter",
    "meterLead", "servicePolicy", "policyOffsetDays", "completionMode", "profileId",
    "remindersEnabled", "providers",
)
"""The schedule command in its 1.4 form."""

SCHEDULE_LEGACY_KEYS: tuple[str, ...] = ("seasonBehavior", "seasonReentry", "seasonReentryOffsetDays")
"""1.3's three season fields, still accepted as deprecated inputs — never in a body beside
`servicePolicy`/`policyOffsetDays`."""

SCHEDULE_ACTION_FLAGS: tuple[str, ...] = ("unlinkHealthSubject",)
"""Actions, not fields: no row carries one."""

SCHEDULE_ROW_TO_COMMAND: Mapping[str, str] = MappingProxyType(
    {"assetId": "targetAssetId", "groupId": "targetGroupId"}
)
"""The row reports `assetId`/`groupId`; the command takes `targetAssetId`/`targetGroupId`."""

HEALTH_SUBJECT_KEYS: tuple[str, ...] = (
    "assetId", "name", "kind", "driver", "scheduleId", "baselineProfileId", "nominalUntilDays",
    "warningFromDays", "criticalFromDays", "weight", "sortOrder",
)
"""The health-subject command as a create takes it. The edit takes every key but `assetId`: a
subject never changes asset."""


def vendored() -> dict[str, dict[str, Any]]:
    """The three entries in the golden file's own shape, for the equality test."""
    return {
        "asset": {"keys": list(ASSET_KEYS)},
        "schedule": {
            "keys": list(SCHEDULE_KEYS),
            "legacyKeys": list(SCHEDULE_LEGACY_KEYS),
            "actionFlags": list(SCHEDULE_ACTION_FLAGS),
            "rowToCommand": dict(SCHEDULE_ROW_TO_COMMAND),
        },
        "healthSubject": {"keys": list(HEALTH_SUBJECT_KEYS)},
    }

"""#92 C32: the one pure rule that turns a manifest replacement and the phone's replace offer into the replace
tool's arguments — used three times on purpose, as `plan.py`'s resolution helpers are: by `phone.snapshot` for the
phone's own plan, by `plan.plan` to decide, and by `apply.apply` for the write, each against the offer it read.

Every schedule title, group name and tag label must match exactly one row of the offer, and only ids are sent: a
tag moves only when its binding is named on its own (R92-2). `schedule_start_on`, `retired_on` and `manual_phase`
are sent only when the manifest gives them — nothing is defaulted (R86-10).
"""

from __future__ import annotations

from typing import Any

from . import manifest as manifestmod

_ARGUMENT = {
    "name": "name", "category": "category", "manufacturer": "manufacturer", "model": "model",
    "serialNumber": "serial_number", "purchaseOn": "purchase_on", "inServiceOn": "in_service_on",
    "purchasePriceMinor": "purchase_price_minor", "currency": "currency", "vendor": "vendor",
    "location": "location", "warrantyExpiresOn": "warranty_expires_on", "warrantyNotes": "warranty_notes",
    "parentAssetId": "parent_asset_id",
}


def _ids(rows: Any, field: str, wanted: tuple[str, ...], what: str) -> tuple[list[str], str | None]:
    ids: list[str] = []
    for name in wanted:
        matches = [row for row in rows or [] if isinstance(row, dict) and row.get(field) == name]
        if len(matches) != 1:
            return [], f"{what} {name!r} matches {len(matches)} in the replace offer"
        ids.append(str(matches[0]["id"]))
    return ids, None


def arguments(
    replacement: manifestmod.Replacement, offer: dict[str, Any],
) -> tuple[dict[str, Any] | None, str | None]:
    """`(arguments, None)` — every argument but the asset id and `plan_only` — or `(None, reason)`."""
    schedule_ids, err = _ids(offer.get("schedules"), "title", replacement.schedules, "schedule")
    group_ids, group_err = _ids(offer.get("groups"), "name", replacement.groups, "group")
    tag_ids, tag_err = _ids(offer.get("tags"), "label", replacement.move_tags, "tag")
    if err or group_err or tag_err:
        return None, err or group_err or tag_err
    args: dict[str, Any] = {_ARGUMENT[key]: value for key, value in replacement.successor}
    args.update(carry_season=replacement.carry_season, carry_setup=replacement.carry_setup,
                carry_notes=replacement.carry_notes, schedule_ids=schedule_ids, group_ids=group_ids,
                moved_tag_ids=tag_ids)
    for key, value in (("retired_on", replacement.retired_on), ("manual_phase", replacement.manual_phase),
                       ("schedule_start_on", replacement.schedule_start_on)):
        if value is not None:
            args[key] = value
    return args, None


def clean(phone_plan: Any) -> bool:
    """The replace tool's own strictness: eligible, not blocked, `problems` present and exactly `[]`, and a
    non-empty `sourcesDigest`."""
    return (
        isinstance(phone_plan, dict) and phone_plan.get("eligible") is True and phone_plan.get("blockedBy") is None
        and "problems" in phone_plan and phone_plan["problems"] == []
        and isinstance(phone_plan.get("sourcesDigest"), str) and phone_plan["sourcesDigest"] != ""
    )

"""Apply a clean `Plan`: create groups, then schedules, through the phone's automation API, then
re-plan and hand back the result (invariant 7 — refuse on any `CONFLICT`/`ERROR`, groups before
schedules, re-plan at the end).

`apply` re-snapshots the phone (`phone.snapshot`) right before writing, rather than trusting ids
implied by the `Plan` it was handed — a person may have reviewed that plan for a while before
calling apply, and this is the one place a stale id would otherwise reach the wire. It reuses
`plan.py`'s own resolution helpers (`resolve_asset`, `match_profiles`, `match_groups`) against that
fresh snapshot, so an id this module writes with is always the one on the phone right now.

A group this apply itself just created is targeted by its own fresh id (`created_group_ids`); a
group that already existed is re-resolved by name, the identical rule `plan.py` used to call it
`IDENTICAL`/`CONFLICT` in the first place. `anchor_on` travels from the manifest to the wire without
ever being read as a date — invariant 8.
"""

from __future__ import annotations

from dataclasses import dataclass

from . import manifest as manifestmod
from . import phone
from . import plan as planmod


class ApplyRefused(RuntimeError):
    """The given `Plan` was not clean (`Plan.clean` is `False`); nothing was written."""


class ApplyError(RuntimeError):
    """A manifest entry the plan called `CREATE` could not be resolved against the fresh snapshot
    this apply took — the phone changed under the plan between when it was computed and when this
    ran. Names the entry's kind and key. Not transactional: writes already made by this call are
    not undone."""


@dataclass(frozen=True)
class ApplyResult:
    groups_created: int
    schedules_created: int
    reapply_plan: planmod.Plan


async def apply(
    manifest: manifestmod.Manifest, plan_result: planmod.Plan, client: phone.ToolClient,
) -> ApplyResult:
    if not plan_result.clean:
        raise ApplyRefused("the plan has CONFLICT/ERROR entries; nothing was written")

    inventory = await phone.snapshot(client)
    decisions = {(e.kind, e.key): e.decision for e in plan_result.entries}

    created_group_ids: dict[str, str] = {}
    groups_created = 0
    for group in manifest.groups:
        if decisions.get(("group", group.key)) != "CREATE":
            continue
        members = []
        for sort_order, member in enumerate(group.members):
            asset_id, err = planmod.resolve_asset(inventory, member.asset)
            if err is not None:
                raise ApplyError(f"group {group.key!r}: {err}")
            members.append({"assetId": asset_id, "sortOrder": sort_order})
        payload = await phone.call_tool(
            client, "create_group",
            {"name": group.name, "description": group.description, "members": members},
        )
        created_group_ids[group.key] = payload["group"]["id"]
        groups_created += 1

    schedules_created = 0
    for schedule in manifest.schedules:
        if decisions.get(("schedule", schedule.key)) != "CREATE":
            continue
        args: dict[str, object] = {
            "title": schedule.title,
            "description": schedule.description,
            "time_interval": schedule.time.interval,
            "time_unit": schedule.time.unit,
            "time_basis": schedule.time.basis,
            "anchor_on": schedule.time.anchor_on,  # verbatim -- invariant 8, never re-derived
            "lead_days": schedule.lead_days,
            "completion_mode": schedule.completion_mode,
            "season_behavior": schedule.season_behavior,
            "reminders_enabled": schedule.reminders_enabled,
        }
        if schedule.target_asset is not None:
            asset_id, err = planmod.resolve_asset(inventory, schedule.target_asset)
            if err is not None:
                raise ApplyError(f"schedule {schedule.key!r}: {err}")
            args["target_asset_id"] = asset_id
            if schedule.profile is not None:
                matches = planmod.match_profiles(inventory, asset_id, schedule.profile)
                if len(matches) != 1:
                    raise ApplyError(
                        f"schedule {schedule.key!r}: {len(matches)} profiles named "
                        f"{schedule.profile!r} on that asset"
                    )
                args["profile_id"] = matches[0].id
        else:
            group_key = schedule.target_group
            assert group_key is not None
            if group_key in created_group_ids:
                args["target_group_id"] = created_group_ids[group_key]
            else:
                group_entry = next(g for g in manifest.groups if g.key == group_key)
                matches = planmod.match_groups(inventory, group_entry.name)
                if len(matches) != 1:
                    raise ApplyError(
                        f"schedule {schedule.key!r}: {len(matches)} groups named "
                        f"{group_entry.name!r} on the phone"
                    )
                args["target_group_id"] = matches[0].id

        await phone.call_tool(client, "create_schedule", args)
        schedules_created += 1

    reapply_inventory = await phone.snapshot(client)
    reapply_plan = planmod.plan(manifest, reapply_inventory)
    return ApplyResult(groups_created, schedules_created, reapply_plan)

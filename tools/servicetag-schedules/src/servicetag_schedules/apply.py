"""Apply a plan: create groups, then schedules, through the phone's automation API, then re-plan
and hand back the result (invariant 7 — refuse on any `CONFLICT`/`ERROR`, groups before schedules,
re-plan at the end).

`apply` re-snapshots the phone (`phone.snapshot`) right before writing, and **recomputes the plan
from that fresh snapshot** — it never replays the decisions of the `Plan` it was handed. A person
may have reviewed that plan for a while before calling apply, or a second run of this tool (or the
owner, by hand) may have written the very row this manifest was about to `CREATE`; re-planning
against what is on the phone *right now* is what turns that into `IDENTICAL` (nothing written)
instead of a second, indistinguishable row this tool can never take back (it never edits, archives
or deletes). The `plan_result` argument is therefore only ever used for its `.clean` gate, applied
twice: once immediately (refuse before any I/O if the plan the caller is holding is already dirty),
and again against the fresh plan (refuse if the phone drifted into a dirty state between then and
now). It reuses `plan.py`'s own resolution helpers (`resolve_asset`, `match_profiles`,
`match_groups`) against that same fresh snapshot, so an id this module writes with is always the one
on the phone right now.

A group this apply itself just created is targeted by its own fresh id (`created_group_ids`); a
group that already existed is re-resolved by name, the identical rule `plan.py` used to call it
`IDENTICAL`/`CONFLICT` in the first place. `anchor_on` travels from the manifest to the wire without
ever being read as a date — invariant 8. Every write call passes `entry=` through to
`phone.call_tool`, so an `is_error` from the phone surfaces naming the manifest entry it was for,
not just the tool.
"""

from __future__ import annotations

from dataclasses import dataclass

from . import manifest as manifestmod
from . import phone
from . import plan as planmod


class ApplyRefused(RuntimeError):
    """The plan — the one given, or the one recomputed from a fresh snapshot right before writing
    — was not clean (`Plan.clean` is `False`). Nothing was written."""


class ApplyError(RuntimeError):
    """A manifest entry the fresh plan called `CREATE` could not be resolved while building the
    write for it. Since the fresh plan and every write below it resolve names against the exact
    same `Inventory`, this is a defensive guard rather than a reachable drift in ordinary use — the
    place that would actually diverge from `plan.plan`'s own resolution is a bug in this module, and
    this is what turns that bug into a clear message naming the entry instead of a wrong write or an
    opaque crash. Not transactional: writes already made by this call are not undone."""


@dataclass(frozen=True)
class ApplyResult:
    groups_created: int
    schedules_created: int
    reapply_plan: planmod.Plan


async def apply(
    manifest: manifestmod.Manifest, plan_result: planmod.Plan, client: phone.ToolClient,
) -> ApplyResult:
    if not plan_result.clean:
        raise ApplyRefused("the given plan has CONFLICT/ERROR entries; nothing was written")

    inventory = await phone.snapshot(client)
    fresh_plan = planmod.plan(manifest, inventory)
    if not fresh_plan.clean:
        raise ApplyRefused(
            "the phone changed since the plan was computed; the fresh plan has CONFLICT/ERROR "
            "entries; nothing was written"
        )
    decisions = {(e.kind, e.key): e.decision for e in fresh_plan.entries}
    group_entries_by_key = {group.key: group for group in manifest.groups}

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
            entry=f"group {group.key!r}",
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
                group_entry = group_entries_by_key.get(group_key)
                if group_entry is None:
                    raise ApplyError(f"schedule {schedule.key!r}: unknown manifest group key {group_key!r}")
                matches = planmod.match_groups(inventory, group_entry.name)
                if len(matches) != 1:
                    raise ApplyError(
                        f"schedule {schedule.key!r}: {len(matches)} groups named "
                        f"{group_entry.name!r} on the phone"
                    )
                args["target_group_id"] = matches[0].id

        await phone.call_tool(client, "create_schedule", args, entry=f"schedule {schedule.key!r}")
        schedules_created += 1

    reapply_inventory = await phone.snapshot(client)
    reapply_plan = planmod.plan(manifest, reapply_inventory)
    return ApplyResult(groups_created, schedules_created, reapply_plan)

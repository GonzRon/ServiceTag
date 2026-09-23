"""Plan a manifest against a phone's `Inventory` — **pure**: no I/O, no clock, nothing but the two
values it is given. This is where the plan's eight invariants
(`docs/superpowers/plans/2026-09-23-servicetag-stage-b/plan.md`, "Resolution rules") live: asset
and profile resolution, group and schedule identity, the group-target narrowing rule, and the two
duplicate checks. `manifest.load` validates shape; this module decides what each entry *means*
against the phone, and never raises — every problem becomes an `ERROR` (or `CONFLICT`) entry so a
caller sees every hazard in one pass, not just the first one.

The resolution helpers (`resolve_asset`, `match_assets`, `match_profiles`, `match_groups`) are
exported for `apply.py`, which re-resolves the same names against a fresh `Inventory` right before
writing — the identical rule, applied twice, on purpose (see `apply.py`'s module docstring).
"""

from __future__ import annotations

from dataclasses import dataclass

from . import manifest as manifestmod
from . import phone

Decision = str  # "CREATE" | "IDENTICAL" | "CONFLICT" | "ERROR"

_DECISIONS: tuple[str, ...] = ("CREATE", "IDENTICAL", "CONFLICT", "ERROR")


@dataclass(frozen=True)
class PlanEntry:
    kind: str  # "group" | "schedule"
    key: str
    decision: str
    reason: str


@dataclass(frozen=True)
class Plan:
    entries: tuple[PlanEntry, ...]

    @property
    def clean(self) -> bool:
        """True iff no entry is `CONFLICT` or `ERROR` — the one thing `apply.apply` checks before
        writing anything (invariant 7)."""
        return all(e.decision not in ("CONFLICT", "ERROR") for e in self.entries)

    def summary(self) -> dict[str, int]:
        counts = {d: 0 for d in _DECISIONS}
        for e in self.entries:
            counts[e.decision] += 1
        return counts


# ---- resolution helpers (shared with apply.py) --------------------------------------------------

def match_assets(inventory: phone.Inventory, name: str) -> list[phone.Asset]:
    """Every non-archived, non-retired asset (top-level or component) named exactly `name`."""
    return [a for a in inventory.assets if a.name == name and not a.archived and not a.retired]


def resolve_asset(inventory: phone.Inventory, name: str) -> tuple[str | None, str | None]:
    """`(asset_id, None)` on exactly one match, else `(None, reason)` — invariant 1."""
    matches = match_assets(inventory, name)
    if len(matches) == 1:
        return matches[0].id, None
    if not matches:
        return None, f"no asset named {name!r}"
    return None, f"{len(matches)} assets named {name!r} (ambiguous)"


def match_profiles(inventory: phone.Inventory, asset_id: str, name: str) -> list[phone.Profile]:
    """Every non-archived profile of `asset_id` named exactly `name`."""
    return [
        p for p in inventory.profiles
        if p.asset_id == asset_id and p.name == name and not p.archived
    ]


def match_groups(inventory: phone.Inventory, name: str) -> list[phone.Group]:
    """Every non-archived group named exactly `name` — invariant 3's identity."""
    return [g for g in inventory.groups if g.name == name and not g.archived]


# ---- groups -------------------------------------------------------------------------------------

@dataclass(frozen=True)
class _GroupResolution:
    entry: PlanEntry
    ok: bool
    existing: phone.Group | None
    member_asset_ids: frozenset[str]


def _resolve_group_members(
    inventory: phone.Inventory, group: manifestmod.Group,
) -> tuple[frozenset[str], list[str]]:
    ids: set[str] = set()
    errors: list[str] = []
    for member in group.members:
        asset_id, err = resolve_asset(inventory, member.asset)
        if err is not None:
            errors.append(err)
        else:
            ids.add(asset_id)  # type: ignore[arg-type]
    return frozenset(ids), errors


def _plan_group(
    group: manifestmod.Group, inventory: phone.Inventory, *, duplicate: bool,
) -> _GroupResolution:
    if duplicate:
        entry = PlanEntry("group", group.key, "ERROR", "duplicate manifest key")
        return _GroupResolution(entry, ok=False, existing=None, member_asset_ids=frozenset())

    member_ids, errors = _resolve_group_members(inventory, group)
    if errors:
        entry = PlanEntry("group", group.key, "ERROR", "; ".join(errors))
        return _GroupResolution(entry, ok=False, existing=None, member_asset_ids=frozenset())

    matches = match_groups(inventory, group.name)
    if not matches:
        entry = PlanEntry("group", group.key, "CREATE", "no existing group named this")
        return _GroupResolution(entry, ok=True, existing=None, member_asset_ids=member_ids)
    if len(matches) > 1:
        entry = PlanEntry(
            "group", group.key, "ERROR",
            f"{len(matches)} non-archived groups named {group.name!r} on the phone",
        )
        return _GroupResolution(entry, ok=False, existing=None, member_asset_ids=member_ids)

    existing = matches[0]
    if existing.open_member_asset_ids == member_ids:
        entry = PlanEntry("group", group.key, "IDENTICAL", "matches the existing group")
    else:
        entry = PlanEntry("group", group.key, "CONFLICT", "existing group has a different member set")
    return _GroupResolution(entry, ok=True, existing=existing, member_asset_ids=member_ids)


# ---- schedules ------------------------------------------------------------------------------------

@dataclass(frozen=True)
class _ScheduleTarget:
    """A schedule entry's resolved identity, once every pre-check has passed."""

    identity: tuple[str, str]  # ("asset", asset_id) | ("group", manifest group key)
    profile_id: str | None
    existing_matches: tuple[phone.Schedule, ...]


def _schedule_rule(
    schedule: manifestmod.Schedule, profile_id: str | None,
) -> tuple[object, ...]:
    """The identity-comparison tuple, invariant 4's field list, in the same order on both sides."""
    return (
        schedule.time.interval, schedule.time.unit, schedule.time.basis, schedule.time.anchor_on,
        schedule.lead_days, schedule.completion_mode, profile_id, schedule.season_behavior,
    )


def _phone_schedule_rule(row: phone.Schedule) -> tuple[object, ...]:
    return (
        row.time_interval, row.time_unit, row.time_basis, row.anchor_on,
        row.lead_days, row.completion_mode, row.profile_id, row.season_behavior,
    )


def _resolve_schedule_target(
    schedule: manifestmod.Schedule,
    inventory: phone.Inventory,
    group_results: dict[str, _GroupResolution],
) -> _ScheduleTarget | PlanEntry:
    """Either the schedule's resolved target (ready for identity comparison) or a finished
    `ERROR` `PlanEntry` — every pre-check invariants 1, 2 and 5 require, short of the duplicate
    (target, title) check (invariant 6b), which needs every entry resolved first."""
    if schedule.target_asset is not None:
        asset_id, err = resolve_asset(inventory, schedule.target_asset)
        if err is not None:
            return PlanEntry("schedule", schedule.key, "ERROR", err)

        profile_id: str | None = None
        if schedule.completion_mode == "FORM":
            if schedule.profile is None:
                return PlanEntry("schedule", schedule.key, "ERROR", "FORM requires a profile")
            matches = match_profiles(inventory, asset_id, schedule.profile)
            if not matches:
                return PlanEntry(
                    "schedule", schedule.key, "ERROR",
                    f"no profile named {schedule.profile!r} on that asset",
                )
            if len(matches) > 1:
                return PlanEntry(
                    "schedule", schedule.key, "ERROR",
                    f"{len(matches)} profiles named {schedule.profile!r} on that asset (ambiguous)",
                )
            profile_id = matches[0].id
        elif schedule.profile is not None:  # QUICK
            return PlanEntry("schedule", schedule.key, "ERROR", "QUICK forbids a profile")

        existing = tuple(
            row for row in inventory.schedules
            if not row.archived and row.target_asset_id == asset_id and row.title == schedule.title
        )
        return _ScheduleTarget(("asset", asset_id), profile_id, existing)

    # A group target.
    group_key = schedule.target_group
    assert group_key is not None
    gres = group_results.get(group_key)
    if gres is None:
        return PlanEntry(
            "schedule", schedule.key, "ERROR", f"unknown manifest group key {group_key!r}",
        )
    if not gres.ok:
        return PlanEntry(
            "schedule", schedule.key, "ERROR", f"depends on group {group_key!r} which is ERROR",
        )

    # Invariant 5, all four branches, checked in a fixed order.
    if schedule.completion_mode != "QUICK":
        return PlanEntry(
            "schedule", schedule.key, "ERROR",
            "a group-targeted schedule must be completionMode QUICK",
        )
    if schedule.profile is not None:
        return PlanEntry(
            "schedule", schedule.key, "ERROR", "a group-targeted schedule may not name a profile",
        )
    if schedule.season_behavior != "IGNORE":
        return PlanEntry(
            "schedule", schedule.key, "ERROR",
            "a group-targeted schedule must be seasonBehavior IGNORE",
        )
    member_count = (
        len(gres.existing.open_member_asset_ids) if gres.existing is not None
        else len(gres.member_asset_ids)
    )
    if member_count < 1:
        return PlanEntry("schedule", schedule.key, "ERROR", f"group {group_key!r} has no members")

    if gres.existing is not None:
        existing = tuple(
            row for row in inventory.schedules
            if not row.archived
            and row.target_group_id == gres.existing.id
            and row.title == schedule.title
        )
    else:
        existing = ()  # the group does not exist on the phone yet; nothing can already target it
    return _ScheduleTarget(("group", group_key), None, existing)


def _decide_schedule(schedule: manifestmod.Schedule, target: _ScheduleTarget) -> PlanEntry:
    if not target.existing_matches:
        return PlanEntry("schedule", schedule.key, "CREATE", "no existing schedule matches this target and title")
    if len(target.existing_matches) > 1:
        return PlanEntry(
            "schedule", schedule.key, "ERROR",
            "multiple existing schedules match this target and title",
        )
    row = target.existing_matches[0]
    if _schedule_rule(schedule, target.profile_id) == _phone_schedule_rule(row):
        return PlanEntry("schedule", schedule.key, "IDENTICAL", "matches the existing schedule")
    return PlanEntry("schedule", schedule.key, "CONFLICT", "existing schedule's rule differs")


# ---- the plan -------------------------------------------------------------------------------------

def plan(manifest: manifestmod.Manifest, inventory: phone.Inventory) -> Plan:
    """Compute a `Plan` for `manifest` against `inventory`. Pure: raises nothing, touches nothing
    but its two arguments."""
    group_key_counts: dict[str, int] = {}
    for group in manifest.groups:
        group_key_counts[group.key] = group_key_counts.get(group.key, 0) + 1

    group_results: dict[str, _GroupResolution] = {}
    group_entries: list[PlanEntry] = []
    for group in manifest.groups:
        result = _plan_group(group, inventory, duplicate=group_key_counts[group.key] > 1)
        group_entries.append(result.entry)
        # On a duplicate key, the last entry read wins the lookup a schedule uses — every instance
        # is ERROR either way (both directly, from `duplicate=True`, and via the propagation below,
        # were it looked up by an earlier duplicate instead), so which one is kept never changes a
        # schedule's decision.
        group_results[group.key] = result

    schedule_key_counts: dict[str, int] = {}
    for schedule in manifest.schedules:
        schedule_key_counts[schedule.key] = schedule_key_counts.get(schedule.key, 0) + 1

    # Pass 1: resolve every schedule's target, or its ERROR, independent of its siblings.
    resolved: list[PlanEntry | _ScheduleTarget] = []
    for schedule in manifest.schedules:
        if schedule_key_counts[schedule.key] > 1:
            resolved.append(PlanEntry("schedule", schedule.key, "ERROR", "duplicate manifest key"))
            continue
        resolved.append(_resolve_schedule_target(schedule, inventory, group_results))

    # Pass 2: invariant 6b — two schedules that resolved to the same (target, title) identity.
    identity_counts: dict[tuple[str, str, str], int] = {}
    for schedule, item in zip(manifest.schedules, resolved):
        if isinstance(item, _ScheduleTarget):
            key = (item.identity[0], item.identity[1], schedule.title)
            identity_counts[key] = identity_counts.get(key, 0) + 1

    schedule_entries: list[PlanEntry] = []
    for schedule, item in zip(manifest.schedules, resolved):
        if isinstance(item, PlanEntry):
            schedule_entries.append(item)
            continue
        key = (item.identity[0], item.identity[1], schedule.title)
        if identity_counts[key] > 1:
            schedule_entries.append(
                PlanEntry(
                    "schedule", schedule.key, "ERROR",
                    "duplicate (target, title) within this manifest",
                )
            )
            continue
        schedule_entries.append(_decide_schedule(schedule, item))

    return Plan(entries=tuple(group_entries) + tuple(schedule_entries))

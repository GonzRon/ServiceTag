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

import unicodedata
from dataclasses import dataclass

from . import draft
from . import legacy_mapping
from . import manifest as manifestmod
from . import phone

_DECISIONS: tuple[str, ...] = ("CREATE", "IDENTICAL", "CONFLICT", "ERROR")


@dataclass(frozen=True)
class PlanEntry:
    kind: str  # "group" | "schedule" | "replacement"
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
    group: manifestmod.Group, inventory: phone.Inventory, *, duplicate_reason: str | None,
) -> _GroupResolution:
    if duplicate_reason is not None:
        entry = PlanEntry("group", group.key, "ERROR", duplicate_reason)
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
    """The identity-comparison tuple, invariant 4's field list, in the same order on both sides —
    its last element the `(servicePolicy, policyOffsetDays)` pair.

    1.4: the manifest still says `seasonBehavior`, and `apply.py` still writes it through the MCP's
    deprecated argument; the phone stores a service policy. So the manifest's value is compared as
    the app translates it — spec §4.1 (`legacy_mapping.to_policy`), with no re-entry and a time
    rule, since a manifest schedule has no re-entry and always a time rule: `IGNORE` is
    `CONTINUOUS`, `FOLLOW_ASSET` is `IN_SERVICE_AT_START` at 0. That is what keeps a loaded
    manifest re-planning IDENTICAL against 1.4 rows."""
    policy = legacy_mapping.to_policy(schedule.season_behavior, has_time_rule=True)
    return (
        schedule.time.interval, schedule.time.unit, schedule.time.basis, schedule.time.anchor_on,
        schedule.lead_days, schedule.completion_mode, profile_id, policy,
    )


def _phone_schedule_rule(row: phone.Schedule) -> tuple[object, ...]:
    """The row's side: its own policy and offset, never the derived `seasonBehavior`."""
    return (
        row.time_interval, row.time_unit, row.time_basis, row.anchor_on,
        row.lead_days, row.completion_mode, row.profile_id,
        (row.service_policy, row.policy_offset_days),
    )


def _policy_text(policy: object) -> str:
    name, offset = policy  # type: ignore[misc]
    return str(name) if offset is None else f"{name} (offset {offset})"


def _rule_difference(
    schedule: manifestmod.Schedule, mine: tuple[object, ...], theirs: tuple[object, ...],
) -> str:
    """A CONFLICT's reason. A difference in the rule proper keeps its shipped wording; a policy
    difference is named — the phone's policy, and what the manifest's `seasonBehavior` maps to — so
    a row no manifest value can describe (a `PRE_SERVICE` one, above all) is never a mystery."""
    reasons: list[str] = []
    if mine[:-1] != theirs[:-1]:
        reasons.append("existing schedule's rule differs")
    if mine[-1] != theirs[-1]:
        reasons.append(
            f"existing schedule's service policy differs: the phone has {_policy_text(theirs[-1])}, "
            f"the manifest's seasonBehavior {schedule.season_behavior} is {_policy_text(mine[-1])}"
        )
    return "; ".join(reasons)


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

        # Invariant 2 (amended 2026-09-23): FORM requires a profile; QUICK may carry one on an
        # asset target too -- `CompleteSchedule` stamps the completion with the profile's event
        # kind and keeps the profile link either way. Only a group target forbids one (invariant 5,
        # below). Whichever mode, a *given* profile name still has to resolve to exactly one
        # non-archived profile of this asset.
        if schedule.completion_mode == "FORM" and schedule.profile is None:
            return PlanEntry("schedule", schedule.key, "ERROR", "FORM requires a profile")

        profile_id: str | None = None
        if schedule.profile is not None:
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
    mine = _schedule_rule(schedule, target.profile_id)
    theirs = _phone_schedule_rule(row)
    if mine == theirs:
        return PlanEntry("schedule", schedule.key, "IDENTICAL", "matches the existing schedule")
    return PlanEntry("schedule", schedule.key, "CONFLICT", _rule_difference(schedule, mine, theirs))


# ---- replacements (#92 C32) -------------------------------------------------------------------------
#
# Identity goes through the succession, never the name alone: after a replacement the successor usually keeps the
# predecessor's name (R86-8), so the name finds both. A namesake that is itself a successor is never a candidate
# (m8), and IDENTICAL is decided before CREATE. IDENTICAL is tighter than C32's name-only rule (the controller's
# ruling): every successor field the manifest gives must equal the existing successor's, trimmed; any difference
# is CONFLICT, since a replacement cannot be undone.

def lone_candidate(read: phone.ReplacementRead | None) -> phone.Namesake | None:
    """The one namesake that is not a successor, when there is exactly one — shared with `apply.py`."""
    originals = [n for n in read.namesakes if not n.is_successor] if read is not None else []
    return originals[0] if len(originals) == 1 else None


_REMOVED = ((0x00AD, 0x00AD), (0x034F, 0x034F), (0x061C, 0x061C), (0x17B4, 0x17B5), (0x180E, 0x180E),
            (0x200B, 0x200B), (0x200E, 0x200F), (0x202A, 0x202E), (0x2060, 0x206F), (0xFEFF, 0xFEFF),
            (0xFFF0, 0xFFF8), (0x1D173, 0x1D17A), (0xE0000, 0xE001F), (0xE0080, 0xE00FF), (0xE01F0, 0xE0FFF))
_SPACE_LIKE = {0x115F, 0x1160, 0x2800, 0x3164, 0xFFA0}
# Kotlin/JVM `Char.isWhitespace()` (`Character.isWhitespace || isSpaceChar`), which the phone's `trim()` and
# `CategoryKey`'s collapse use. Python's `str.split()` and `strip()` also count U+0085 (NEL); the phone keeps it.
_KOTLIN_WHITESPACE = "".join(map(chr, (*range(0x09, 0x0E), *range(0x1C, 0x21), 0xA0, 0x1680, *range(0x2000, 0x200B),
                                       0x2028, 0x2029, 0x202F, 0x205F, 0x3000)))
_TO_SPACE = str.maketrans(dict.fromkeys(_KOTLIN_WHITESPACE, " "))


def _trim(text: str) -> str:
    """Kotlin's `trim()`: only `Char.isWhitespace()` characters, never NEL."""
    return text.strip(_KOTLIN_WHITESPACE)


def category_key(text: str) -> str | None:
    """The phone's category identity (`core/.../journal/CategoryKey.kt`, `of`): NFC, the removed code points
    dropped and the space-like blanks made spaces, NFC again, trimmed, whitespace runs collapsed, lower-cased;
    None when blank. Whitespace is Kotlin's `Char.isWhitespace()` set, not Python's (U+0085 is kept, as the
    phone keeps it). The phone stores a category in its canonical spelling, so a successor's category is compared
    by this key. One exception is not copied: every tag character is kept here, where the phone drops those
    outside three subdivision flags, so a divergence can only read CONFLICT, never a false IDENTICAL."""
    kept = "".join(" " if ord(c) in _SPACE_LIKE else c for c in unicodedata.normalize("NFC", text)
                   if not any(lo <= ord(c) <= hi for lo, hi in _REMOVED))
    runs = unicodedata.normalize("NFC", kept).translate(_TO_SPACE).split(" ")
    return " ".join(run for run in runs if run).lower() or None


def _differing(replacement: manifestmod.Replacement, successor: dict) -> list[str]:
    """The manifest's successor keys whose value the existing successor does not hold, as the phone stores it:
    trimmed, a blank as absent (`blankToNull`, and the non-null `""` defaults), a category by its key."""
    def norm(key: str, value: object) -> object:
        if not isinstance(value, str):
            return value
        return category_key(value) if key == "category" else (_trim(value) or None)
    return [key for key, value in replacement.successor if norm(key, successor.get(key)) != norm(key, value)]


def _decide_replacement(replacement: manifestmod.Replacement, read: phone.ReplacementRead | None) -> PlanEntry:
    def decided(decision: str, reason: str) -> PlanEntry:
        return PlanEntry("replacement", replacement.key, decision, reason)

    name = replacement.predecessor
    if read is None:
        return decided("ERROR", "the snapshot did not read this replacement")
    originals = [n for n in read.namesakes if not n.is_successor]
    if not read.namesakes:
        return decided("ERROR", f"no asset named {name!r}")
    if not originals:
        return decided("ERROR", f"every asset named {name!r} is itself a successor, never a candidate")
    if len(originals) > 1:
        return decided("ERROR", f"{len(originals)} assets named {name!r} that are not successors (ambiguous)")
    namesake_ids = {n.asset_id for n in read.namesakes}
    if any(n.is_successor and n.predecessor_id not in namesake_ids for n in read.namesakes):
        # A successor whose own predecessor does not carry the name (renamed) is another live asset: ambiguous.
        return decided("ERROR", f"another asset named {name!r} is the successor of a differently named one "
                                "(ambiguous)")
    candidate = originals[0]
    if candidate.successor is not None:
        differing = _differing(replacement, candidate.successor)
        if not differing:
            return decided("IDENTICAL", "already replaced, by a successor matching every field the manifest gives")
        return decided("CONFLICT", f"already replaced by something else ({', '.join(differing)} differ); "
                                   "a replacement cannot be undone")
    offer = candidate.offer or {}
    if offer.get("held") is True:
        return decided("CONFLICT", "the asset is held (transferred out)")
    if offer.get("eligible") is not True:
        return decided("ERROR", "the phone's replace offer is not eligible")
    _, unresolved = draft.arguments(replacement, offer)
    if unresolved is not None:
        return decided("ERROR", unresolved)
    if not draft.clean(candidate.phone_plan):
        phone_plan = candidate.phone_plan if isinstance(candidate.phone_plan, dict) else {}
        codes = [str(p.get("code")) for p in phone_plan.get("problems") or [] if isinstance(p, dict)]
        blocked = phone_plan.get("blockedBy")
        return decided("ERROR", "the phone's plan is not clean: " + ", ".join(
            ([f"blockedBy {blocked}"] if blocked else []) + codes or ["no clean plan"]))
    return decided("CREATE", "not replaced yet; the phone's plan is clean")


def _plan_replacements(manifest: manifestmod.Manifest, inventory: phone.Inventory) -> list[PlanEntry]:
    keys = [r.key for r in manifest.replacements]
    predecessors = [r.predecessor for r in manifest.replacements]
    reads = {read.key: read for read in inventory.replacements}
    # Names this manifest's groups and schedules resolve: after a replacement such a name finds the successor
    # (a retired predecessor is never resolved), so the manifest could never re-plan IDENTICAL.
    named = {m.asset for g in manifest.groups for m in g.members}
    named |= {s.target_asset for s in manifest.schedules if s.target_asset is not None}
    entries = []
    for r in manifest.replacements:
        if keys.count(r.key) > 1:
            entries.append(PlanEntry("replacement", r.key, "ERROR", "duplicate manifest key"))
        elif predecessors.count(r.predecessor) > 1:
            entries.append(PlanEntry("replacement", r.key, "ERROR", "duplicate predecessor within this manifest"))
        else:
            entry = _decide_replacement(r, reads.get(r.key))
            if entry.decision == "CREATE" and r.predecessor in named:
                entry = PlanEntry("replacement", r.key, "ERROR", (
                    f"this manifest also names {r.predecessor!r} in its schedules or groups; after the replacement "
                    "that name finds the successor, so it could never re-plan IDENTICAL: replace it from a manifest "
                    "of its own, then load the successor's schedules in another"))
            entries.append(entry)
    return entries


# ---- the plan -------------------------------------------------------------------------------------

def plan(manifest: manifestmod.Manifest, inventory: phone.Inventory) -> Plan:
    """Compute a `Plan` for `manifest` against `inventory`. Pure: raises nothing, touches nothing
    but its two arguments."""
    group_key_counts: dict[str, int] = {}
    group_name_counts: dict[str, int] = {}
    for group in manifest.groups:
        group_key_counts[group.key] = group_key_counts.get(group.key, 0) + 1
        group_name_counts[group.name] = group_name_counts.get(group.name, 0) + 1

    group_results: dict[str, _GroupResolution] = {}
    group_entries: list[PlanEntry] = []
    for group in manifest.groups:
        # A duplicate *key* is checked first: it is the more specific problem (this exact manifest
        # entry is not addressable at all), and a manifest that reuses a key is very likely also
        # reusing a name on the very same entries, which would otherwise double-report one hazard
        # under two reasons.
        if group_key_counts[group.key] > 1:
            duplicate_reason = "duplicate manifest key"
        elif group_name_counts[group.name] > 1:
            # Invariant 3: a group's identity on the phone is its *name*. Two manifest entries
            # under different keys but the same name would both `CREATE` a same-named group on an
            # empty phone — a plan this module called clean, and a duplicate `apply` can never take
            # back (this tool never archives or deletes).
            duplicate_reason = f"duplicate group name {group.name!r}"
        else:
            duplicate_reason = None
        result = _plan_group(group, inventory, duplicate_reason=duplicate_reason)
        group_entries.append(result.entry)
        # On a duplicate key or name, the last entry read wins the lookup a schedule uses — every
        # instance is ERROR either way (both directly, from `duplicate_reason`, and via the
        # propagation below, were it looked up by an earlier duplicate instead), so which one is
        # kept never changes a schedule's decision.
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

    replacement_entries = _plan_replacements(manifest, inventory)
    return Plan(entries=tuple(group_entries) + tuple(schedule_entries) + tuple(replacement_entries))

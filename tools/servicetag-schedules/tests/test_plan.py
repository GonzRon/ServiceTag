"""`plan.plan`: pure decisions against a hand-built `Inventory` -- no client, no I/O. One test per
hazard in plan.md's "Resolution rules (invariants)" section, referenced by number in each test's
docstring or name.
"""

from __future__ import annotations

import asyncio
from pathlib import Path

from servicetag_schedules import manifest as M
from servicetag_schedules import phone as P
from servicetag_schedules import plan as PL

# ---- manifest builders ---------------------------------------------------------------------------


def mk_manifest(groups=(), schedules=()) -> M.Manifest:
    return M.Manifest(manifest_version=1, as_of="2026-09-23", groups=tuple(groups), schedules=tuple(schedules))


def mk_group(key: str, name: str, members: tuple[str, ...], description: str | None = None) -> M.Group:
    return M.Group(key=key, name=name, description=description, members=tuple(M.Member(a) for a in members))


def mk_time(interval: int = 30, unit: str = "DAY", basis: str = "FIXED", anchor_on: str = "2026-01-01") -> M.Time:
    return M.Time(interval, unit, basis, anchor_on)


def mk_schedule(
    key: str,
    title: str,
    *,
    target_asset: str | None = None,
    target_group: str | None = None,
    time: M.Time | None = None,
    lead_days: int = 0,
    completion_mode: str = "QUICK",
    profile: str | None = None,
    season_behavior: str = "IGNORE",
    reminders_enabled: bool = False,
) -> M.Schedule:
    return M.Schedule(
        key=key, title=title, target_asset=target_asset, target_group=target_group,
        description=None, time=time or mk_time(), lead_days=lead_days,
        completion_mode=completion_mode, profile=profile, season_behavior=season_behavior,
        reminders_enabled=reminders_enabled,
    )


# ---- inventory builders ---------------------------------------------------------------------------


def mk_asset(id_: str, name: str, *, archived: bool = False, retired: bool = False, parent_id: str | None = None) -> P.Asset:
    return P.Asset(id=id_, name=name, archived=archived, retired=retired, parent_id=parent_id)


def mk_profile(id_: str, asset_id: str, name: str, *, archived: bool = False) -> P.Profile:
    return P.Profile(id=id_, asset_id=asset_id, name=name, archived=archived)


def mk_pgroup(id_: str, name: str, member_ids: tuple[str, ...], *, archived: bool = False) -> P.Group:
    return P.Group(id=id_, name=name, archived=archived, open_member_asset_ids=frozenset(member_ids))


def mk_pschedule(
    id_: str,
    title: str,
    *,
    target_asset_id: str | None = None,
    target_group_id: str | None = None,
    time_interval: int = 30,
    time_unit: str = "DAY",
    time_basis: str = "FIXED",
    anchor_on: str = "2026-01-01",
    lead_days: int = 0,
    completion_mode: str = "QUICK",
    profile_id: str | None = None,
    service_policy: str = "CONTINUOUS",
    policy_offset_days: int | None = None,
    archived: bool = False,
) -> P.Schedule:
    """A 1.4 row as `phone.snapshot` reads it: the policy and its offset, never 1.3's triple."""
    return P.Schedule(
        id=id_, title=title, target_asset_id=target_asset_id, target_group_id=target_group_id,
        time_interval=time_interval, time_unit=time_unit, time_basis=time_basis, anchor_on=anchor_on,
        lead_days=lead_days, completion_mode=completion_mode, profile_id=profile_id,
        service_policy=service_policy, policy_offset_days=policy_offset_days, archived=archived,
    )


def entry(result: PL.Plan, kind: str, key: str) -> PL.PlanEntry:
    return next(e for e in result.entries if e.kind == kind and e.key == key)


# ---- groups: invariants 1 and 3 -------------------------------------------------------------------


def test_group_create_when_no_existing_group_matches() -> None:
    a = mk_asset("a1", "Mister north")
    manifest = mk_manifest(groups=[mk_group("g1", "Misters", ("Mister north",))])
    result = PL.plan(manifest, P.Inventory(assets=(a,)))
    assert entry(result, "group", "g1").decision == "CREATE"


def test_group_identical_when_member_set_matches() -> None:
    a = mk_asset("a1", "Mister north")
    existing = mk_pgroup("pg1", "Misters", ("a1",))
    manifest = mk_manifest(groups=[mk_group("g1", "Misters", ("Mister north",))])
    result = PL.plan(manifest, P.Inventory(assets=(a,), groups=(existing,)))
    assert entry(result, "group", "g1").decision == "IDENTICAL"


def test_group_conflict_when_member_set_differs() -> None:
    a = mk_asset("a1", "Mister north")
    b = mk_asset("a2", "Mister south")
    existing = mk_pgroup("pg1", "Misters", ("a2",))  # different member
    manifest = mk_manifest(groups=[mk_group("g1", "Misters", ("Mister north",))])
    result = PL.plan(manifest, P.Inventory(assets=(a, b), groups=(existing,)))
    assert entry(result, "group", "g1").decision == "CONFLICT"


def test_group_error_on_ambiguous_member_asset() -> None:
    """Invariant 1: two non-archived assets sharing a name -> ERROR."""
    a = mk_asset("a1", "Mister")
    b = mk_asset("a2", "Mister")
    manifest = mk_manifest(groups=[mk_group("g1", "Misters", ("Mister",))])
    result = PL.plan(manifest, P.Inventory(assets=(a, b)))
    assert entry(result, "group", "g1").decision == "ERROR"


def test_group_error_on_missing_member_asset() -> None:
    """Invariant 1: zero matching assets -> ERROR."""
    manifest = mk_manifest(groups=[mk_group("g1", "Misters", ("Nonexistent",))])
    result = PL.plan(manifest, P.Inventory())
    assert entry(result, "group", "g1").decision == "ERROR"


def test_asset_resolution_ignores_an_archived_namesake() -> None:
    """S3: invariant 1's filter is *non-archived, non-retired* -- an archived asset sharing a name
    with an active one must not make the active one ambiguous. Deleting `not a.archived` from
    `match_assets` would resolve two matches here and turn this CREATE into ERROR."""
    active = mk_asset("a1", "Mister")
    archived_namesake = mk_asset("a2", "Mister", archived=True)
    manifest = mk_manifest(groups=[mk_group("g1", "Misters", ("Mister",))])
    result = PL.plan(manifest, P.Inventory(assets=(active, archived_namesake)))
    assert entry(result, "group", "g1").decision == "CREATE"


def test_asset_resolution_ignores_a_retired_namesake() -> None:
    """S3: the other half of invariant 1's filter. Deleting `not a.retired` from `match_assets`
    would resolve two matches here and turn this CREATE into ERROR."""
    active = mk_asset("a1", "Mister")
    retired_namesake = mk_asset("a2", "Mister", retired=True)
    manifest = mk_manifest(groups=[mk_group("g1", "Misters", ("Mister",))])
    result = PL.plan(manifest, P.Inventory(assets=(active, retired_namesake)))
    assert entry(result, "group", "g1").decision == "CREATE"


def test_group_identity_ignores_an_archived_existing_group() -> None:
    """Invariant 3: identity is matched among *non-archived* groups; an archived one of the same
    name is 'absent', so the manifest group still CREATEs."""
    a = mk_asset("a1", "Mister north")
    archived_existing = mk_pgroup("pg1", "Misters", ("a1",), archived=True)
    manifest = mk_manifest(groups=[mk_group("g1", "Misters", ("Mister north",))])
    result = PL.plan(manifest, P.Inventory(assets=(a,), groups=(archived_existing,)))
    assert entry(result, "group", "g1").decision == "CREATE"


# ---- schedules targeting an asset: invariants 1, 2, 4 ----------------------------------------------


def test_schedule_asset_target_create() -> None:
    a = mk_asset("a1", "Garden shed")
    manifest = mk_manifest(schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed")])
    result = PL.plan(manifest, P.Inventory(assets=(a,)))
    assert entry(result, "schedule", "s1").decision == "CREATE"


def test_schedule_asset_target_identical() -> None:
    a = mk_asset("a1", "Garden shed")
    existing = mk_pschedule("ps1", "Inspect roof", target_asset_id="a1")
    manifest = mk_manifest(schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed")])
    result = PL.plan(manifest, P.Inventory(assets=(a,), schedules=(existing,)))
    assert entry(result, "schedule", "s1").decision == "IDENTICAL"


def test_schedule_asset_target_conflict_on_a_rule_difference() -> None:
    a = mk_asset("a1", "Garden shed")
    existing = mk_pschedule("ps1", "Inspect roof", target_asset_id="a1", anchor_on="2020-01-01")
    manifest = mk_manifest(
        schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed", time=mk_time(anchor_on="2026-01-01"))]
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,), schedules=(existing,)))
    assert entry(result, "schedule", "s1").decision == "CONFLICT"


def test_schedule_asset_target_conflict_ignores_description_and_reminders() -> None:
    """Invariant 4: `description`, `remindersEnabled`, `providers` are not identity."""
    a = mk_asset("a1", "Garden shed")
    existing = mk_pschedule("ps1", "Inspect roof", target_asset_id="a1")
    manifest = mk_manifest(
        schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed", reminders_enabled=True)]
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,), schedules=(existing,)))
    assert entry(result, "schedule", "s1").decision == "IDENTICAL"


def test_schedule_error_on_ambiguous_target_asset() -> None:
    a = mk_asset("a1", "Pump")
    b = mk_asset("a2", "Pump")
    manifest = mk_manifest(schedules=[mk_schedule("s1", "Service pump", target_asset="Pump")])
    result = PL.plan(manifest, P.Inventory(assets=(a, b)))
    assert entry(result, "schedule", "s1").decision == "ERROR"


def test_schedule_archived_existing_never_matched_for_identity() -> None:
    a = mk_asset("a1", "Garden shed")
    archived_existing = mk_pschedule("ps1", "Inspect roof", target_asset_id="a1", archived=True)
    manifest = mk_manifest(schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed")])
    result = PL.plan(manifest, P.Inventory(assets=(a,), schedules=(archived_existing,)))
    assert entry(result, "schedule", "s1").decision == "CREATE"


# ---- profile resolution: invariant 2 --------------------------------------------------------------


def test_form_requires_a_profile() -> None:
    a = mk_asset("a1", "Garden shed")
    manifest = mk_manifest(
        schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed", completion_mode="FORM", profile=None)]
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,)))
    assert entry(result, "schedule", "s1").decision == "ERROR"


def test_quick_asset_target_with_a_resolvable_profile_creates() -> None:
    """Invariant 2, amended 2026-09-23 at the first real plan: a QUICK *asset*-targeted schedule
    may carry a profile -- `CompleteSchedule` stamps the completion with the profile's event kind
    and keeps the link either way. Only a group target still forbids one (invariant 5)."""
    a = mk_asset("a1", "Garden shed")
    p = mk_profile("p1", "a1", "Roof inspection")
    manifest = mk_manifest(
        schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed", completion_mode="QUICK", profile="Roof inspection")]
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,), profiles=(p,)))
    assert entry(result, "schedule", "s1").decision == "CREATE"


def test_quick_asset_target_with_no_profile_still_creates() -> None:
    """A QUICK schedule was always allowed to carry no profile at all; the amendment only lifted
    the ban on one being present, so this path must be unaffected."""
    a = mk_asset("a1", "Garden shed")
    manifest = mk_manifest(
        schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed", completion_mode="QUICK", profile=None)]
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,)))
    assert entry(result, "schedule", "s1").decision == "CREATE"


def test_quick_asset_target_profile_not_found_is_still_an_error() -> None:
    """The amendment lifts the QUICK/profile ban, not profile resolution itself."""
    a = mk_asset("a1", "Garden shed")
    manifest = mk_manifest(
        schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed", completion_mode="QUICK", profile="Nope")]
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,)))
    assert entry(result, "schedule", "s1").decision == "ERROR"


def test_quick_asset_target_profile_id_is_part_of_identity() -> None:
    """Invariant 4: `profileId` is compared for identity exactly as before -- a QUICK schedule that
    now matches an existing row's profile is IDENTICAL, a QUICK schedule whose profile differs from
    an existing row's is CONFLICT."""
    a = mk_asset("a1", "Garden shed")
    p = mk_profile("p1", "a1", "Roof inspection")
    same_profile = mk_pschedule("ps1", "Inspect roof", target_asset_id="a1", completion_mode="QUICK", profile_id="p1")
    manifest = mk_manifest(
        schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed", completion_mode="QUICK", profile="Roof inspection")]
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,), profiles=(p,), schedules=(same_profile,)))
    assert entry(result, "schedule", "s1").decision == "IDENTICAL"

    different_profile = mk_pschedule("ps2", "Inspect roof", target_asset_id="a1", completion_mode="QUICK", profile_id="p-other")
    result2 = PL.plan(manifest, P.Inventory(assets=(a,), profiles=(p,), schedules=(different_profile,)))
    assert entry(result2, "schedule", "s1").decision == "CONFLICT"


def test_form_profile_not_found_is_an_error() -> None:
    a = mk_asset("a1", "Garden shed")
    manifest = mk_manifest(
        schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed", completion_mode="FORM", profile="Nope")]
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,)))
    assert entry(result, "schedule", "s1").decision == "ERROR"


def test_form_profile_ambiguous_is_an_error() -> None:
    a = mk_asset("a1", "Garden shed")
    p1 = mk_profile("p1", "a1", "Roof inspection")
    p2 = mk_profile("p2", "a1", "Roof inspection")
    manifest = mk_manifest(
        schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed", completion_mode="FORM", profile="Roof inspection")]
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,), profiles=(p1, p2)))
    assert entry(result, "schedule", "s1").decision == "ERROR"


def test_form_profile_archived_is_an_error() -> None:
    """S3: invariant 2's filter is *non-archived*. Deleting `not p.archived` from `match_profiles`
    would resolve this archived profile and turn this ERROR into CREATE."""
    a = mk_asset("a1", "Garden shed")
    archived_profile = mk_profile("p1", "a1", "Roof inspection", archived=True)
    manifest = mk_manifest(
        schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed", completion_mode="FORM", profile="Roof inspection")]
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,), profiles=(archived_profile,)))
    assert entry(result, "schedule", "s1").decision == "ERROR"


def test_form_with_a_resolvable_profile_creates() -> None:
    a = mk_asset("a1", "Garden shed")
    p = mk_profile("p1", "a1", "Roof inspection")
    manifest = mk_manifest(
        schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed", completion_mode="FORM", profile="Roof inspection")]
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,), profiles=(p,)))
    assert entry(result, "schedule", "s1").decision == "CREATE"


# ---- schedules targeting a group: invariants 1, 4, 5 ------------------------------------------------


def test_group_target_narrowing_rejects_form() -> None:
    """Invariant 5, branch 1."""
    a = mk_asset("a1", "Mister north")
    manifest = mk_manifest(
        groups=[mk_group("g1", "Misters", ("Mister north",))],
        schedules=[mk_schedule("s1", "Rinse", target_group="g1", completion_mode="FORM")],
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,)))
    assert entry(result, "schedule", "s1").decision == "ERROR"


def test_group_target_narrowing_rejects_a_profile() -> None:
    """Invariant 5, branch 2."""
    a = mk_asset("a1", "Mister north")
    manifest = mk_manifest(
        groups=[mk_group("g1", "Misters", ("Mister north",))],
        schedules=[mk_schedule("s1", "Rinse", target_group="g1", profile="Something")],
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,)))
    assert entry(result, "schedule", "s1").decision == "ERROR"


def test_group_target_narrowing_rejects_follow_asset_season() -> None:
    """Invariant 5, branch 3."""
    a = mk_asset("a1", "Mister north")
    manifest = mk_manifest(
        groups=[mk_group("g1", "Misters", ("Mister north",))],
        schedules=[mk_schedule("s1", "Rinse", target_group="g1", season_behavior="FOLLOW_ASSET")],
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,)))
    assert entry(result, "schedule", "s1").decision == "ERROR"


def test_group_target_narrowing_rejects_an_empty_group() -> None:
    """Invariant 5, branch 4: no members in the manifest and none on the phone."""
    manifest = mk_manifest(
        groups=[mk_group("g1", "Misters", ())],
        schedules=[mk_schedule("s1", "Rinse", target_group="g1")],
    )
    result = PL.plan(manifest, P.Inventory())
    assert entry(result, "schedule", "s1").decision == "ERROR"


def test_group_target_valid_narrowing_uses_manifest_member_count_when_group_is_create() -> None:
    a = mk_asset("a1", "Mister north")
    manifest = mk_manifest(
        groups=[mk_group("g1", "Misters", ("Mister north",))],
        schedules=[mk_schedule("s1", "Rinse", target_group="g1")],
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,)))
    assert entry(result, "group", "g1").decision == "CREATE"
    assert entry(result, "schedule", "s1").decision == "CREATE"


def test_group_target_identical_when_group_already_exists() -> None:
    a = mk_asset("a1", "Mister north")
    existing_group = mk_pgroup("pg1", "Misters", ("a1",))
    existing_schedule = mk_pschedule("ps1", "Rinse", target_group_id="pg1")
    manifest = mk_manifest(
        groups=[mk_group("g1", "Misters", ("Mister north",))],
        schedules=[mk_schedule("s1", "Rinse", target_group="g1")],
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,), groups=(existing_group,), schedules=(existing_schedule,)))
    assert entry(result, "group", "g1").decision == "IDENTICAL"
    assert entry(result, "schedule", "s1").decision == "IDENTICAL"


def test_group_target_conflict_when_existing_schedule_rule_differs() -> None:
    a = mk_asset("a1", "Mister north")
    existing_group = mk_pgroup("pg1", "Misters", ("a1",))
    existing_schedule = mk_pschedule("ps1", "Rinse", target_group_id="pg1", lead_days=99)
    manifest = mk_manifest(
        groups=[mk_group("g1", "Misters", ("Mister north",))],
        schedules=[mk_schedule("s1", "Rinse", target_group="g1", lead_days=0)],
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,), groups=(existing_group,), schedules=(existing_schedule,)))
    assert entry(result, "schedule", "s1").decision == "CONFLICT"


def test_schedule_error_on_unknown_manifest_group_key() -> None:
    manifest = mk_manifest(schedules=[mk_schedule("s1", "Rinse", target_group="nope")])
    result = PL.plan(manifest, P.Inventory())
    assert entry(result, "schedule", "s1").decision == "ERROR"


def test_schedule_error_propagates_from_its_group() -> None:
    """Invariant 1's propagation clause: a schedule depending on an ERROR group is ERROR too."""
    a = mk_asset("a1", "Mister")
    b = mk_asset("a2", "Mister")  # ambiguous member name
    manifest = mk_manifest(
        groups=[mk_group("g1", "Misters", ("Mister",))],
        schedules=[mk_schedule("s1", "Rinse", target_group="g1")],
    )
    result = PL.plan(manifest, P.Inventory(assets=(a, b)))
    assert entry(result, "group", "g1").decision == "ERROR"
    assert entry(result, "schedule", "s1").decision == "ERROR"


# ---- duplicates: invariant 6 ------------------------------------------------------------------------


def test_duplicate_manifest_group_keys_are_both_error() -> None:
    a = mk_asset("a1", "Mister north")
    manifest = mk_manifest(
        groups=[mk_group("g1", "Misters", ("Mister north",)), mk_group("g1", "Misters two", ())]
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,)))
    group_entries = [e for e in result.entries if e.kind == "group"]
    assert len(group_entries) == 2
    assert all(e.decision == "ERROR" for e in group_entries)


def test_duplicate_group_names_under_different_keys_are_both_error() -> None:
    """S1: invariant 3 makes a group's identity its *name*, not its manifest key. Two manifest
    groups with different keys but one name would otherwise both CREATE on an empty phone -- a
    plan called clean that writes a duplicate this tool can never take back."""
    a = mk_asset("a1", "Intake filter")
    manifest = mk_manifest(
        groups=[
            mk_group("a", "Filters", ("Intake filter",)),
            mk_group("b", "Filters", ("Intake filter",)),
        ]
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,)))
    assert entry(result, "group", "a").decision == "ERROR"
    assert entry(result, "group", "b").decision == "ERROR"
    assert result.clean is False


def test_two_group_targeted_schedules_sharing_a_title_on_the_same_group_are_both_error() -> None:
    """S1 / invariant 4: identity is (target, title); two schedules targeting the *same* manifest
    group with the same title collide even though their own manifest keys differ."""
    a = mk_asset("a1", "Mister north")
    manifest = mk_manifest(
        groups=[mk_group("g1", "Misters", ("Mister north",))],
        schedules=[
            mk_schedule("s1", "Rinse", target_group="g1"),
            mk_schedule("s2", "Rinse", target_group="g1"),
        ],
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,)))
    assert entry(result, "schedule", "s1").decision == "ERROR"
    assert entry(result, "schedule", "s2").decision == "ERROR"
    assert result.clean is False


def test_duplicate_manifest_schedule_keys_are_both_error() -> None:
    a = mk_asset("a1", "Garden shed")
    manifest = mk_manifest(
        schedules=[
            mk_schedule("s1", "Inspect roof", target_asset="Garden shed"),
            mk_schedule("s1", "Inspect roof again", target_asset="Garden shed"),
        ]
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,)))
    schedule_entries = [e for e in result.entries if e.kind == "schedule"]
    assert len(schedule_entries) == 2
    assert all(e.decision == "ERROR" for e in schedule_entries)


def test_duplicate_target_and_title_identity_is_error_on_both() -> None:
    """Invariant 6: two distinct manifest keys resolving to the same (target, title)."""
    a = mk_asset("a1", "Garden shed")
    manifest = mk_manifest(
        schedules=[
            mk_schedule("s1", "Inspect roof", target_asset="Garden shed"),
            mk_schedule("s2", "Inspect roof", target_asset="Garden shed"),
        ]
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,)))
    assert entry(result, "schedule", "s1").decision == "ERROR"
    assert entry(result, "schedule", "s2").decision == "ERROR"


def test_same_title_different_target_asset_is_not_a_duplicate() -> None:
    a = mk_asset("a1", "Garden shed")
    b = mk_asset("a2", "Workshop")
    manifest = mk_manifest(
        schedules=[
            mk_schedule("s1", "Inspect roof", target_asset="Garden shed"),
            mk_schedule("s2", "Inspect roof", target_asset="Workshop"),
        ]
    )
    result = PL.plan(manifest, P.Inventory(assets=(a, b)))
    assert entry(result, "schedule", "s1").decision == "CREATE"
    assert entry(result, "schedule", "s2").decision == "CREATE"


# ---- Plan.clean / Plan.summary -----------------------------------------------------------------------


def test_plan_clean_true_when_only_create_and_identical() -> None:
    a = mk_asset("a1", "Garden shed")
    existing = mk_pschedule("ps1", "Inspect roof", target_asset_id="a1")
    manifest = mk_manifest(
        schedules=[
            mk_schedule("s1", "Inspect roof", target_asset="Garden shed"),
            mk_schedule("s2", "New one", target_asset="Garden shed"),
        ]
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,), schedules=(existing,)))
    assert result.clean is True


def test_plan_clean_false_with_any_conflict_or_error() -> None:
    manifest = mk_manifest(schedules=[mk_schedule("s1", "Whatever", target_asset="Nonexistent")])
    result = PL.plan(manifest, P.Inventory())
    assert result.clean is False


def test_plan_summary_counts_each_decision() -> None:
    a = mk_asset("a1", "Garden shed")
    existing = mk_pschedule("ps1", "Inspect roof", target_asset_id="a1")
    manifest = mk_manifest(
        schedules=[
            mk_schedule("s1", "Inspect roof", target_asset="Garden shed"),  # IDENTICAL
            mk_schedule("s2", "New one", target_asset="Garden shed"),  # CREATE
            mk_schedule("s3", "Broken", target_asset="Nonexistent"),  # ERROR
        ]
    )
    result = PL.plan(manifest, P.Inventory(assets=(a,), schedules=(existing,)))
    counts = result.summary()
    assert counts == {"CREATE": 1, "IDENTICAL": 1, "CONFLICT": 0, "ERROR": 1}


# ---- 1.4.1 (#80): the re-plan is blind to providers and to updatedAt --------------------------------


def test_a_providerless_row_and_a_repaired_one_both_replan_identical(fake_client) -> None:
    """A loaded schedule re-plans IDENTICAL whether the phone still holds it providerless (loaded by a
    1.3/1.4.0 loader) or `repair_schedule_providers` has since given it LOCAL and moved its
    `updatedAt`: providers are not identity (invariant 4), and `updatedAt` is never read at all —
    here it is absent on one row and not even a number on the other."""
    shed = fake_client.add_asset(name="Garden shed")
    mister = fake_client.add_asset(name="Greenhouse mister north")
    fake_client.add_schedule(title="Inspect roof", target_asset_id=shed)
    fake_client.add_schedule(title="Inspect roof", target_asset_id=mister)
    providerless, repaired = fake_client.schedules
    providerless.update(providers=[], remindersEnabled=True)
    providerless.pop("updatedAt", None)
    repaired.update(providers=[{"provider": "LOCAL", "enabled": True}], remindersEnabled=True, updatedAt="never read")
    manifest = mk_manifest(
        schedules=[
            mk_schedule("s1", "Inspect roof", target_asset="Garden shed", reminders_enabled=True),
            mk_schedule("s2", "Inspect roof", target_asset="Greenhouse mister north", reminders_enabled=True),
        ]
    )

    result = PL.plan(manifest, asyncio.run(P.snapshot(fake_client)))

    assert entry(result, "schedule", "s1").decision == "IDENTICAL"
    assert entry(result, "schedule", "s2").decision == "IDENTICAL"
    assert result.summary() == {"CREATE": 0, "IDENTICAL": 2, "CONFLICT": 0, "ERROR": 0}


# ---- 1.4: the re-plan compares through spec §4.1 (lockstep) -------------------------------------


FIXTURE = Path(__file__).resolve().parent / "fixtures" / "estate-manifest.json"


def test_a_loaded_ignore_manifest_replans_identical_against_14_rows() -> None:
    """The lockstep proof in miniature: a manifest whose schedules are all `IGNORE` (as all 43
    Stage-B schedules are), already loaded, against the rows a 1.4 phone reports for it —
    `servicePolicy` `CONTINUOUS`, no offset — re-plans entirely IDENTICAL."""
    manifest = M.load(FIXTURE)
    assert {s.season_behavior for s in manifest.schedules} == {"IGNORE"}
    shed = mk_asset("a1", "Garden shed")
    north = mk_asset("a2", "Greenhouse mister north")
    south = mk_asset("a3", "Greenhouse mister south")
    roof = mk_profile("p1", "a1", "Roof inspection")
    misters = mk_pgroup("pg1", "Greenhouse misting nozzles", ("a2", "a3"))
    rows = (
        mk_pschedule(
            "ps1", "Inspect the shed roof", target_asset_id="a1", time_interval=6, time_unit="MONTH",
            anchor_on="2026-10-01", lead_days=7, completion_mode="FORM", profile_id="p1",
            service_policy="CONTINUOUS", policy_offset_days=None,
        ),
        mk_pschedule(
            "ps2", "Rinse the mister nozzles", target_group_id="pg1", time_interval=30,
            time_unit="DAY", anchor_on="2026-09-25", lead_days=3,
            service_policy="CONTINUOUS", policy_offset_days=None,
        ),
    )
    inventory = P.Inventory(
        assets=(shed, north, south), profiles=(roof,), groups=(misters,), schedules=rows,
    )

    result = PL.plan(manifest, inventory)

    assert result.summary() == {"CREATE": 0, "IDENTICAL": 3, "CONFLICT": 0, "ERROR": 0}
    assert result.clean


def test_follow_asset_matches_at_start_zero() -> None:
    """A manifest `FOLLOW_ASSET` (no re-entry, a time rule) is `IN_SERVICE_AT_START` at 0 — that row
    is IDENTICAL, and the other two policies 1.3 would also have called `FOLLOW_ASSET` are not."""
    a = mk_asset("a1", "Snowblower")
    manifest = mk_manifest(
        schedules=[mk_schedule("s1", "Tune-up", target_asset="Snowblower", season_behavior="FOLLOW_ASSET")]
    )

    def decided(**policy) -> str:
        row = mk_pschedule("ps1", "Tune-up", target_asset_id="a1", **policy)
        return entry(PL.plan(manifest, P.Inventory(assets=(a,), schedules=(row,))), "schedule", "s1").decision

    assert decided(service_policy="IN_SERVICE_AT_START", policy_offset_days=0) == "IDENTICAL"
    assert decided(service_policy="IN_SERVICE_AT_START", policy_offset_days=5) == "CONFLICT"
    assert decided(service_policy="IN_SERVICE_RESUME_CLAMPED", policy_offset_days=None) == "CONFLICT"


def test_a_policy_difference_is_a_conflict() -> None:
    """Every policy the manifest's value does not map to is a CONFLICT whose reason names the policy
    difference — a `PRE_SERVICE` row against an `IGNORE` entry above all (it reads a null
    `seasonBehavior`, so no 1.3 value could describe it) — and a CONFLICT means the plan is not
    clean, so apply writes nothing."""
    a = mk_asset("a1", "Mower")
    for behavior, policy, offset in (
        ("IGNORE", "PRE_SERVICE", -14),
        ("IGNORE", "IN_SERVICE_AT_START", 0),
        ("FOLLOW_ASSET", "IN_SERVICE_RESUME_CLAMPED", None),
        ("FOLLOW_ASSET", "IN_SERVICE_AT_START", 30),
        ("FOLLOW_ASSET", "CONTINUOUS", None),
    ):
        manifest = mk_manifest(
            schedules=[mk_schedule("s1", "Blade service", target_asset="Mower", season_behavior=behavior)]
        )
        row = mk_pschedule(
            "ps1", "Blade service", target_asset_id="a1", service_policy=policy, policy_offset_days=offset,
        )
        result = PL.plan(manifest, P.Inventory(assets=(a,), schedules=(row,)))
        decision = entry(result, "schedule", "s1")
        assert decision.decision == "CONFLICT", (behavior, policy, offset)
        assert "service policy" in decision.reason, decision.reason
        assert policy in decision.reason, decision.reason
        assert not result.clean


def test_a_rule_difference_still_reads_as_one_and_names_the_policy_only_when_it_differs() -> None:
    a = mk_asset("a1", "Mower")
    manifest = mk_manifest(schedules=[mk_schedule("s1", "Blade service", target_asset="Mower")])
    row = mk_pschedule("ps1", "Blade service", target_asset_id="a1", lead_days=9)
    decision = entry(PL.plan(manifest, P.Inventory(assets=(a,), schedules=(row,))), "schedule", "s1")
    assert (decision.decision, decision.reason) == ("CONFLICT", "existing schedule's rule differs")

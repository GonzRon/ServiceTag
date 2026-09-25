"""`apply.apply`: writes through a `FakeClient` (see `conftest.py`), which records every call in
order and answers `create_group`/`create_schedule` with freshly minted ids -- exactly what
`plan.md`'s test list asks for: refuses on any CONFLICT/ERROR, creates groups before schedules,
resolves a manifest group referenced by a schedule to the id the fake returned, and a re-plan after
apply is all IDENTICAL; a second apply then writes nothing.
"""

from __future__ import annotations

import asyncio
from pathlib import Path

import pytest

from servicetag_schedules import apply as A
from servicetag_schedules import manifest as M
from servicetag_schedules import phone as P
from servicetag_schedules import plan as PL


def _run(coro):
    return asyncio.run(coro)


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


async def _plan_against(manifest: M.Manifest, client) -> PL.Plan:
    inventory = await P.snapshot(client)
    return PL.plan(manifest, inventory)


# ---- refusal --------------------------------------------------------------------------------------


def test_apply_refuses_and_writes_nothing_when_the_plan_is_not_clean(fake_client) -> None:
    manifest = mk_manifest(schedules=[mk_schedule("s1", "Whatever", target_asset="Nonexistent")])
    dirty_plan = PL.Plan(entries=(PL.PlanEntry("schedule", "s1", "ERROR", "no asset"),))
    with pytest.raises(A.ApplyRefused):
        _run(A.apply(manifest, dirty_plan, fake_client))
    assert fake_client.calls == []


# ---- S2: a write-path is_error names the entry key, not just the tool -----------------------------


def test_apply_names_the_group_entry_when_create_group_is_refused(fake_client) -> None:
    fake_client.add_asset(name="Mister north")
    manifest = mk_manifest(groups=[mk_group("g1", "Misters", ("Mister north",))])
    result = _run(_plan_against(manifest, fake_client))
    fake_client.fail_next("create_group", "422 GROUP_NAME_REQUIRED: blank")

    with pytest.raises(P.PhoneError) as exc:
        _run(A.apply(manifest, result, fake_client))

    message = str(exc.value)
    assert message.startswith("group 'g1':")
    assert "create_group" in message


def test_apply_names_the_schedule_entry_when_create_schedule_is_refused(fake_client) -> None:
    fake_client.add_asset(name="Garden shed")
    manifest = mk_manifest(schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed")])
    result = _run(_plan_against(manifest, fake_client))
    fake_client.fail_next("create_schedule", "422 SCHEDULE_INVALID: bad row")

    with pytest.raises(P.PhoneError) as exc:
        _run(A.apply(manifest, result, fake_client))

    message = str(exc.value)
    assert message.startswith("schedule 's1':")
    assert "create_schedule" in message


# ---- ordering and id-flow --------------------------------------------------------------------------


def test_apply_creates_the_group_before_the_schedule_that_targets_it(fake_client) -> None:
    fake_client.add_asset(name="Mister north")
    fake_client.add_asset(name="Mister south")
    manifest = mk_manifest(
        groups=[mk_group("g1", "Misters", ("Mister north", "Mister south"))],
        schedules=[mk_schedule("s1", "Rinse", target_group="g1")],
    )
    result = _run(_plan_against(manifest, fake_client))
    assert result.clean

    outcome = _run(A.apply(manifest, result, fake_client))

    tool_names = [name for name, _ in fake_client.calls]
    group_index = tool_names.index("create_group")
    schedule_index = tool_names.index("create_schedule")
    assert group_index < schedule_index
    assert outcome.groups_created == 1
    assert outcome.schedules_created == 1


def test_apply_resolves_the_freshly_created_group_id_into_the_schedule(fake_client) -> None:
    fake_client.add_asset(name="Mister north")
    manifest = mk_manifest(
        groups=[mk_group("g1", "Misters", ("Mister north",))],
        schedules=[mk_schedule("s1", "Rinse", target_group="g1")],
    )
    result = _run(_plan_against(manifest, fake_client))

    _run(A.apply(manifest, result, fake_client))

    [group_row] = fake_client.groups
    [schedule_row] = fake_client.schedules
    assert schedule_row["groupId"] == group_row["id"]


def test_apply_uses_an_already_existing_group_id_when_the_group_was_identical(fake_client) -> None:
    asset_id = fake_client.add_asset(name="Mister north")
    existing_group_id = fake_client.add_group(name="Misters", member_asset_ids=[asset_id])
    manifest = mk_manifest(
        groups=[mk_group("g1", "Misters", ("Mister north",))],
        schedules=[mk_schedule("s1", "Rinse", target_group="g1")],
    )
    result = _run(_plan_against(manifest, fake_client))
    assert result.clean

    _run(A.apply(manifest, result, fake_client))

    tool_names = [name for name, _ in fake_client.calls]
    assert "create_group" not in tool_names  # the group already existed; only the schedule was created
    [schedule_row] = fake_client.schedules
    assert schedule_row["groupId"] == existing_group_id


def test_apply_sends_members_in_manifest_order_as_sort_order(fake_client) -> None:
    fake_client.add_asset(name="Mister north")
    fake_client.add_asset(name="Mister south")
    manifest = mk_manifest(groups=[mk_group("g1", "Misters", ("Mister north", "Mister south"))])
    result = _run(_plan_against(manifest, fake_client))

    _run(A.apply(manifest, result, fake_client))

    [group_row] = fake_client.groups
    assert [m["sortOrder"] for m in group_row["members"]] == [0, 1]


def test_apply_sends_anchor_on_verbatim(fake_client) -> None:
    fake_client.add_asset(name="Garden shed")
    manifest = mk_manifest(
        schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed", time=mk_time(anchor_on="2026-12-31"))]
    )
    result = _run(_plan_against(manifest, fake_client))

    _run(A.apply(manifest, result, fake_client))

    [schedule_row] = fake_client.schedules
    assert schedule_row["anchorOn"] == "2026-12-31"


def test_apply_sends_the_profile_id_for_a_quick_asset_target_schedule(fake_client) -> None:
    """Invariant 2, amended 2026-09-23: a QUICK asset-targeted schedule may carry a profile, and
    its resolved profileId must reach `create_schedule` exactly as a FORM schedule's does."""
    shed_id = fake_client.add_asset(name="Garden shed")
    profile_id = fake_client.add_profile(asset_id=shed_id, name="Roof inspection")
    manifest = mk_manifest(
        schedules=[
            mk_schedule(
                "s1", "Inspect roof", target_asset="Garden shed",
                completion_mode="QUICK", profile="Roof inspection",
            )
        ]
    )
    result = _run(_plan_against(manifest, fake_client))
    assert result.clean

    _run(A.apply(manifest, result, fake_client))

    [schedule_row] = fake_client.schedules
    assert schedule_row["profileId"] == profile_id
    assert schedule_row["completionMode"] == "QUICK"


# ---- re-plan and idempotence ------------------------------------------------------------------------


def test_reapply_plan_after_apply_is_all_identical(fake_client) -> None:
    fake_client.add_asset(name="Garden shed")
    manifest = mk_manifest(schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed")])
    result = _run(_plan_against(manifest, fake_client))

    outcome = _run(A.apply(manifest, result, fake_client))

    counts = outcome.reapply_plan.summary()
    assert counts["CREATE"] == 0 and counts["CONFLICT"] == 0 and counts["ERROR"] == 0
    assert counts["IDENTICAL"] == 1


def test_a_second_apply_against_the_post_apply_state_writes_nothing(fake_client) -> None:
    fake_client.add_asset(name="Garden shed")
    manifest = mk_manifest(schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed")])
    first_plan = _run(_plan_against(manifest, fake_client))
    _run(A.apply(manifest, first_plan, fake_client))

    second_plan = _run(_plan_against(manifest, fake_client))
    assert second_plan.summary()["CREATE"] == 0

    outcome = _run(A.apply(manifest, second_plan, fake_client))
    assert outcome.groups_created == 0
    assert outcome.schedules_created == 0


# ---- S4: apply recomputes the plan from the fresh snapshot, never replays a stale one --------------


def test_apply_treats_a_row_created_between_plan_and_apply_as_identical_not_a_duplicate(fake_client) -> None:
    """The controller's S4 scenario: the plan says CREATE; before apply runs, something else (the
    owner, or another run of this tool) creates that exact row on the phone. Apply must recompute
    against what is there *now* and see IDENTICAL, not write a second, indistinguishable copy this
    tool can never take back."""
    shed_id = fake_client.add_asset(name="Garden shed")
    manifest = mk_manifest(schedules=[mk_schedule("s1", "Inspect roof", target_asset="Garden shed")])
    stale_plan = _run(_plan_against(manifest, fake_client))
    assert stale_plan.summary()["CREATE"] == 1

    # The row appears on the phone -- matching the manifest's rule exactly -- after the plan above
    # was computed but before apply is called.
    fake_client.add_schedule(
        title="Inspect roof", target_asset_id=shed_id, time_interval=30, time_unit="DAY",
        time_basis="FIXED", anchor_on="2026-01-01", lead_days=0, completion_mode="QUICK",
        season_behavior="IGNORE",
    )

    outcome = _run(A.apply(manifest, stale_plan, fake_client))

    assert outcome.schedules_created == 0
    assert len(fake_client.schedules) == 1  # no duplicate row
    tool_names = [name for name, _ in fake_client.calls]
    assert "create_schedule" not in tool_names
    assert outcome.reapply_plan.summary() == {"CREATE": 0, "IDENTICAL": 1, "CONFLICT": 0, "ERROR": 0}


def test_apply_refuses_when_the_phone_drifted_into_a_dirty_state_since_the_plan(fake_client) -> None:
    """A narrower S4 case: the fresh snapshot itself is no longer clean (here, a second asset with
    the same name showed up between plan and apply, making the target ambiguous). Apply must refuse
    rather than write against whichever match happens to resolve."""
    fake_client.add_asset(name="Pump")
    manifest = mk_manifest(schedules=[mk_schedule("s1", "Service", target_asset="Pump")])
    stale_plan = _run(_plan_against(manifest, fake_client))
    assert stale_plan.clean

    fake_client.add_asset(name="Pump")  # a second "Pump" appears before apply runs

    with pytest.raises(A.ApplyRefused):
        _run(A.apply(manifest, stale_plan, fake_client))

    tool_names = [name for name, _ in fake_client.calls]
    assert "create_group" not in tool_names
    assert "create_schedule" not in tool_names


# ---- the committed synthetic fixture, end to end ------------------------------------------------------


def test_the_estate_fixture_plans_applies_and_reapplies_clean(fake_client) -> None:
    shed_id = fake_client.add_asset(name="Garden shed")
    fake_client.add_profile(asset_id=shed_id, name="Roof inspection")
    fake_client.add_asset(name="Greenhouse mister north")
    fake_client.add_asset(name="Greenhouse mister south")

    manifest = M.load(Path(__file__).parent / "fixtures" / "estate-manifest.json")

    first_plan = _run(_plan_against(manifest, fake_client))
    assert first_plan.summary() == {"CREATE": 3, "IDENTICAL": 0, "CONFLICT": 0, "ERROR": 0}

    outcome = _run(A.apply(manifest, first_plan, fake_client))
    assert outcome.groups_created == 1
    assert outcome.schedules_created == 2
    assert outcome.reapply_plan.summary() == {"CREATE": 0, "IDENTICAL": 3, "CONFLICT": 0, "ERROR": 0}

    second_plan = _run(_plan_against(manifest, fake_client))
    assert second_plan.summary() == {"CREATE": 0, "IDENTICAL": 3, "CONFLICT": 0, "ERROR": 0}
    second_outcome = _run(A.apply(manifest, second_plan, fake_client))
    assert second_outcome.groups_created == 0
    assert second_outcome.schedules_created == 0

    # Q2: apply -- across planning, applying and re-planning, twice over -- only ever calls the
    # read tools plus create_group/create_schedule. Never update_*, archive_*, delete_* -- the
    # "never edits, archives or deletes an existing row" constraint, pinned as a test rather than
    # left to inspection. (1.4: `status` is one of the reads -- the snapshot's schema check.)
    allowed_tools = {
        "status", "list_assets", "list_profiles", "list_groups", "list_schedules",
        "create_group", "create_schedule",
    }
    tool_names = {name for name, _ in fake_client.calls}
    assert tool_names <= allowed_tools
    assert not any(name.startswith(("update_", "archive_", "delete_")) for name in tool_names)

"""`phone.snapshot`: reads a `FakeClient`'s in-memory state through the same MCP tools a real
phone answers, and turns it into an `Inventory`."""

from __future__ import annotations

import asyncio

import pytest

from servicetag_schedules import phone


def _run(coro):
    return asyncio.run(coro)


def test_snapshot_reads_top_level_and_component_assets(fake_client) -> None:
    top = fake_client.add_asset(name="Garden shed")
    fake_client.add_asset(name="Shed door", parent_id=top)
    fake_client.add_asset(name="Retired mower", retired=True)
    fake_client.add_asset(name="Old pump", archived=True)

    inventory = _run(phone.snapshot(fake_client))

    by_name = {a.name: a for a in inventory.assets}
    assert by_name["Garden shed"].parent_id is None
    assert by_name["Garden shed"].archived is False
    assert by_name["Shed door"].parent_id == top
    assert by_name["Retired mower"].retired is True
    assert by_name["Old pump"].archived is True


def test_snapshot_reads_profiles_per_asset(fake_client) -> None:
    asset_id = fake_client.add_asset(name="Garden shed")
    fake_client.add_profile(asset_id=asset_id, name="Roof inspection")
    fake_client.add_profile(asset_id=asset_id, name="Old profile", archived=True)

    inventory = _run(phone.snapshot(fake_client))

    profiles = [p for p in inventory.profiles if p.asset_id == asset_id]
    assert {p.name for p in profiles} == {"Roof inspection", "Old profile"}
    assert next(p for p in profiles if p.name == "Old profile").archived is True


def test_snapshot_computes_open_group_members(fake_client) -> None:
    a = fake_client.add_asset(name="Mister north")
    b = fake_client.add_asset(name="Mister south")
    group_id = fake_client.add_group(name="Greenhouse misters", member_asset_ids=[a, b])
    # simulate one member's window having been closed on the phone
    fake_client.groups[0]["members"][1]["removedAt"] = 12345

    inventory = _run(phone.snapshot(fake_client))

    group = next(g for g in inventory.groups if g.id == group_id)
    assert group.open_member_asset_ids == frozenset({a})


def test_snapshot_marks_archived_schedules(fake_client) -> None:
    fake_client.add_schedule(title="Active one", target_asset_id="whatever")
    fake_client.add_schedule(title="Archived one", target_asset_id="whatever", archived=True)

    inventory = _run(phone.snapshot(fake_client))

    by_title = {s.title: s for s in inventory.schedules}
    assert by_title["Active one"].archived is False
    assert by_title["Archived one"].archived is True


def test_call_tool_raises_phone_error_on_is_error(fake_client) -> None:
    with pytest.raises(phone.PhoneError, match="list_profiles"):
        _run(phone.call_tool(fake_client, "list_profiles", {}))  # missing required asset_id


def test_call_tool_error_leads_with_the_entry_when_given(fake_client) -> None:
    """S2: a write made on a manifest entry's behalf names that entry, not just the tool."""
    fake_client.fail_next("create_schedule", "422 SCHEDULE_INVALID: bad row")
    with pytest.raises(phone.PhoneError) as exc:
        _run(phone.call_tool(fake_client, "create_schedule", {"title": "x"}, entry="schedule 's31'"))
    message = str(exc.value)
    assert message.startswith("schedule 's31':")
    assert "create_schedule" in message
    assert "SCHEDULE_INVALID" in message


def test_call_tool_error_without_an_entry_still_names_only_the_tool(fake_client) -> None:
    with pytest.raises(phone.PhoneError, match=r"^list_profiles:"):
        _run(phone.call_tool(fake_client, "list_profiles", {}))


def test_schedule_reads_service_policy_and_offset(fake_client) -> None:
    """1.4: the snapshot reads the row's own `servicePolicy` and `policyOffsetDays`, and no longer
    1.3's derived `seasonBehavior` — which reads `FOLLOW_ASSET` for three different policies and
    `null` for a fourth, so it could never tell a re-plan what the phone actually holds."""
    fake_client.add_schedule(
        title="Tune-up", target_asset_id="a", service_policy="PRE_SERVICE", policy_offset_days=-14,
    )
    fake_client.add_schedule(
        title="Blade service", target_asset_id="a", service_policy="IN_SERVICE_AT_START",
        policy_offset_days=5,
    )
    fake_client.add_schedule(
        title="Cover check", target_asset_id="a", service_policy="IN_SERVICE_RESUME_CLAMPED",
    )
    fake_client.add_schedule(title="Filter", target_asset_id="a")

    inventory = _run(phone.snapshot(fake_client))

    by_title = {s.title: (s.service_policy, s.policy_offset_days) for s in inventory.schedules}
    assert by_title == {
        "Tune-up": ("PRE_SERVICE", -14),
        "Blade service": ("IN_SERVICE_AT_START", 5),
        "Cover check": ("IN_SERVICE_RESUME_CLAMPED", None),
        "Filter": ("CONTINUOUS", None),
    }
    for schedule in inventory.schedules:
        assert not hasattr(schedule, "season_behavior"), "the derived triple is not read"


@pytest.mark.parametrize(
    ("answer", "named"),
    [({"schemaVersion": 7}, "schema 7"), ({}, "no schemaVersion"), ({"schemaVersion": "8"}, "'8'")],
    ids=["schema-7", "missing", "not-an-integer"],
)
def test_snapshot_refuses_a_phone_older_than_schema_8_before_any_read(fake_client, answer, named) -> None:
    """The loader's half of lockstep (spec §9.3): against a pre-1.4 app its re-plan could only read
    rows with no `servicePolicy`, so it refuses up front — the same check the MCP makes before a
    write — with the version the phone reports and the one it needs, and reads nothing else."""
    fake_client.add_asset(name="Garden shed")
    fake_client.status_answer = {"appVersion": "1.3.0", **answer}

    with pytest.raises(phone.PhoneError) as exc:
        _run(phone.snapshot(fake_client))

    message = str(exc.value)
    assert named in message
    assert "ServiceTag 1.4.0 (schema 8)" in message
    assert fake_client.calls == [("status", {})], "nothing is read before the version is confirmed"


def test_snapshot_reads_the_status_first_and_then_the_phone(fake_client) -> None:
    fake_client.add_asset(name="Garden shed")
    _run(phone.snapshot(fake_client))
    assert [name for name, _ in fake_client.calls][:2] == ["status", "list_assets"]

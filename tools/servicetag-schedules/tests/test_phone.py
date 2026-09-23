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

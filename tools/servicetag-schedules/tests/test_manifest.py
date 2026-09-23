"""`manifest.load`/`manifest.parse`: shape validation only. Business invariants (asset/profile
resolution, duplicate identities) are `plan.py`'s job and are tested in `test_plan.py` — see
`manifest.py`'s module docstring for why a duplicate manifest `key` loads fine here."""

from __future__ import annotations

from pathlib import Path

import pytest

from servicetag_schedules import manifest


def _valid_obj() -> dict:
    return {
        "manifestVersion": 1,
        "asOf": "2026-09-23",
        "groups": [
            {
                "key": "g1",
                "name": "Greenhouse misters",
                "description": "Every mister head",
                "members": [{"asset": "Mister north"}, {"asset": "Mister south"}],
            }
        ],
        "schedules": [
            {
                "key": "s1",
                "title": "Inspect the shed roof",
                "target": {"asset": "Garden shed"},
                "description": None,
                "time": {"interval": 6, "unit": "MONTH", "basis": "FIXED", "anchorOn": "2026-10-01"},
                "leadDays": 7,
                "completionMode": "FORM",
                "profile": "Roof inspection",
                "seasonBehavior": "IGNORE",
                "remindersEnabled": True,
                "tags": ["seasonal-example"],
                "source": {"todoistId": "1", "cadence": "twice a year"},
            },
            {
                "key": "s2",
                "title": "Rinse the mister nozzles",
                "target": {"group": "g1"},
                "time": {"interval": 30, "unit": "DAY", "basis": "FIXED", "anchorOn": "2026-09-25"},
                "leadDays": 3,
                "completionMode": "QUICK",
                "seasonBehavior": "IGNORE",
                "remindersEnabled": False,
            },
        ],
    }


def test_a_valid_manifest_loads() -> None:
    m = manifest.parse(_valid_obj())
    assert m.manifest_version == 1
    assert m.as_of == "2026-09-23"
    assert len(m.groups) == 1
    assert m.groups[0].key == "g1"
    assert m.groups[0].members == (manifest.Member("Mister north"), manifest.Member("Mister south"))
    assert len(m.schedules) == 2
    s1, s2 = m.schedules
    assert s1.target_asset == "Garden shed" and s1.target_group is None
    assert s1.profile == "Roof inspection"
    assert s1.time == manifest.Time(6, "MONTH", "FIXED", "2026-10-01")
    assert s1.source == manifest.Source("1", "twice a year")
    assert s2.target_group == "g1" and s2.is_group_target
    assert s2.source == manifest.Source()  # `source` omitted entirely -> both fields None


def test_unknown_top_level_key_is_an_error() -> None:
    obj = _valid_obj()
    obj["bogus"] = 1
    with pytest.raises(manifest.ManifestError) as exc:
        manifest.parse(obj)
    assert exc.value.path == "bogus"


def test_missing_required_top_level_key_is_an_error() -> None:
    obj = _valid_obj()
    del obj["asOf"]
    with pytest.raises(manifest.ManifestError, match="asOf"):
        manifest.parse(obj)


def test_manifest_version_must_be_1() -> None:
    obj = _valid_obj()
    obj["manifestVersion"] = 2
    with pytest.raises(manifest.ManifestError, match="manifestVersion"):
        manifest.parse(obj)


def test_bad_as_of_date_is_rejected() -> None:
    obj = _valid_obj()
    obj["asOf"] = "not-a-date"
    with pytest.raises(manifest.ManifestError, match="asOf"):
        manifest.parse(obj)


def test_as_of_rejects_a_non_calendar_date() -> None:
    obj = _valid_obj()
    obj["asOf"] = "2026-02-30"
    with pytest.raises(manifest.ManifestError, match="asOf"):
        manifest.parse(obj)


def test_group_unknown_key_is_an_error() -> None:
    obj = _valid_obj()
    obj["groups"][0]["bogus"] = 1
    with pytest.raises(manifest.ManifestError) as exc:
        manifest.parse(obj)
    assert "bogus" in exc.value.path


def test_group_member_unknown_key_is_an_error() -> None:
    obj = _valid_obj()
    obj["groups"][0]["members"][0]["extra"] = "x"
    with pytest.raises(manifest.ManifestError):
        manifest.parse(obj)


def test_schedule_target_naming_both_asset_and_group_is_an_error() -> None:
    obj = _valid_obj()
    obj["schedules"][1]["target"] = {"asset": "Garden shed", "group": "g1"}
    with pytest.raises(manifest.ManifestError, match="exactly one"):
        manifest.parse(obj)


def test_schedule_target_naming_neither_asset_nor_group_is_an_error() -> None:
    obj = _valid_obj()
    obj["schedules"][1]["target"] = {}
    with pytest.raises(manifest.ManifestError, match="exactly one"):
        manifest.parse(obj)


def test_bad_time_unit_is_rejected() -> None:
    obj = _valid_obj()
    obj["schedules"][0]["time"]["unit"] = "FORTNIGHT"
    with pytest.raises(manifest.ManifestError):
        manifest.parse(obj)


def test_bad_anchor_on_is_rejected() -> None:
    obj = _valid_obj()
    obj["schedules"][0]["time"]["anchorOn"] = "10/01/2026"
    with pytest.raises(manifest.ManifestError):
        manifest.parse(obj)


def test_negative_lead_days_is_rejected() -> None:
    obj = _valid_obj()
    obj["schedules"][0]["leadDays"] = -1
    with pytest.raises(manifest.ManifestError):
        manifest.parse(obj)


def test_non_positive_time_interval_is_rejected() -> None:
    obj = _valid_obj()
    obj["schedules"][0]["time"]["interval"] = 0
    with pytest.raises(manifest.ManifestError):
        manifest.parse(obj)


def test_bad_completion_mode_is_rejected() -> None:
    obj = _valid_obj()
    obj["schedules"][0]["completionMode"] = "TAP"
    with pytest.raises(manifest.ManifestError):
        manifest.parse(obj)


def test_blank_tag_is_rejected() -> None:
    obj = _valid_obj()
    obj["schedules"][0]["tags"] = [""]
    with pytest.raises(manifest.ManifestError):
        manifest.parse(obj)


def test_duplicate_group_keys_load_fine_plan_reports_the_error() -> None:
    """Shape validation does not check identifier uniqueness -- see the module docstring. Loading
    two groups sharing a `key` succeeds; `plan.plan` is where that becomes `ERROR` (test_plan.py)."""
    obj = _valid_obj()
    obj["groups"].append(dict(obj["groups"][0]))
    m = manifest.parse(obj)
    assert len(m.groups) == 2
    assert m.groups[0].key == m.groups[1].key == "g1"


def test_load_reports_a_missing_file() -> None:
    with pytest.raises(manifest.ManifestError):
        manifest.load("/nonexistent/path/does-not-exist.json")


def test_load_reports_malformed_json(tmp_path: Path) -> None:
    bad = tmp_path / "bad.json"
    bad.write_text("{not json", encoding="utf-8")
    with pytest.raises(manifest.ManifestError, match="not valid JSON"):
        manifest.load(bad)


def test_load_reads_the_committed_fixture() -> None:
    fixture = Path(__file__).parent / "fixtures" / "estate-manifest.json"
    m = manifest.load(fixture)
    assert m.manifest_version == 1
    assert {g.key for g in m.groups} == {"greenhouse-misters"}
    assert {s.key for s in m.schedules} == {"shed-roof-inspection", "misters-rinse"}

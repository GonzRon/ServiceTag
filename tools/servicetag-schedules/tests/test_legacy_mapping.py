"""`legacy_mapping.to_policy`: spec §4.1's table, which the loader's re-plan uses to compare a
manifest's `seasonBehavior` with a 1.4 row's `servicePolicy` and `policyOffsetDays`.

It is proved against the repository's golden `docs/api/legacy-season-mapping.json` — the same cases
the app's migration, its format ≤7 decoder and its API legacy form are held to — read here at test
time only (master plan §4, "the agreement proof").
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from servicetag_schedules.legacy_mapping import to_policy

GOLDEN = Path(__file__).resolve().parents[3] / "docs" / "api" / "legacy-season-mapping.json"
"""`tests` → `servicetag-schedules` → `tools` → the repository root."""


def golden_cases() -> list[dict]:
    return json.loads(GOLDEN.read_text(encoding="utf-8"))


def test_every_golden_case() -> None:
    cases = golden_cases()
    assert len(cases) >= 30, "the golden file lost cases"
    for case in cases:
        got = to_policy(
            case["seasonBehavior"],
            case["seasonReentry"],
            case["seasonReentryOffsetDays"],
            has_time_rule=case["hasTimeRule"],
        )
        assert got == (case["servicePolicy"], case["policyOffsetDays"]), case


def test_the_golden_file_holds_the_cases_that_matter() -> None:
    """So `test_every_golden_case` cannot pass on a table that never meets the hard rows: an
    `MM-DD` re-entry under `FOLLOW_ASSET`, `RESUME_CLAMPED`, an absent behaviour, and the offset
    edges with and without a time rule."""
    cases = golden_cases()
    follow = [c for c in cases if c["seasonBehavior"] == "FOLLOW_ASSET"]
    assert any(re.fullmatch(r"\d\d-\d\d", c["seasonReentry"] or "") for c in follow)
    assert any(c["seasonReentry"] == "RESUME_CLAMPED" for c in follow)
    assert any(c["seasonBehavior"] is None for c in cases)
    offsets = {(c["seasonReentryOffsetDays"], c["hasTimeRule"]) for c in follow}
    for edge in (-1, 0, 365, 366, None):
        assert (edge, True) in offsets, edge
    assert any(not has_rule for _, has_rule in offsets)


def test_the_two_manifest_values_map_as_the_re_plan_compares_them() -> None:
    """The manifest carries a behaviour and nothing else, and every manifest schedule has a time
    rule: IGNORE is `CONTINUOUS` with no offset, FOLLOW_ASSET is `IN_SERVICE_AT_START` at 0."""
    assert to_policy("IGNORE") == ("CONTINUOUS", None)
    assert to_policy("FOLLOW_ASSET") == ("IN_SERVICE_AT_START", 0)


def test_an_unknown_behaviour_is_not_in_the_table() -> None:
    """It normalises and never refuses a *value*, but an unknown behaviour name was never a value:
    1.3 refused it as an unknown enum, and so does this."""
    with pytest.raises(ValueError, match="SOMETIMES"):
        to_policy("SOMETIMES")

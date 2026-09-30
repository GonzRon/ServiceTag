"""#92's two replace tools (C19–C23, C27; R92-1, R92-2): `get_replace_offer` and `replace_asset`.

`replace_asset` plans first and, by default, only plans. With `plan_only=False` it applies **only a clean,
eligible plan**, sending that plan's own `sourcesDigest` with the identical draft; a plan with problems, a
blocked plan or a plan without a digest is refused here and the apply is never sent. A replay after success is
IDENTICAL when the recorded successor carries the requested name. Tags move by binding id only: an "all", a
label or a pattern is refused before any request.
"""

from __future__ import annotations

import inspect
import json

import httpx
import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import command_shapes
from servicetag_mcp import server as server_module

ASSET = "a-heater"
NEW = "a-heater-2"
PLAN_PATH = f"/v1/assets/{ASSET}/replace-plan"
APPLY_PATH = f"/v1/assets/{ASSET}/replace"
DIGEST = "ab" * 32
STATUS_16 = {"appVersion": "1.5.0", "apiVersion": 1, "schemaVersion": 16, "backupFormatVersion": 16,
             "installationId": "fixture-installation", "counts": {}}
SUCCESSION = {"id": "s1", "predecessorAssetId": ASSET, "successorAssetId": NEW, "replacedOn": "2026-09-30",
              "createdAt": 1}


def plan(**overrides) -> dict:
    answer = {"eligible": True, "blockedBy": None, "replacedOn": "2026-09-30", "problems": [], "sourcesDigest": DIGEST}
    answer.update(overrides)
    return answer


def body_of(recorded) -> dict:
    return json.loads(recorded.body.decode())


def calls(api) -> list[tuple[str, str]]:
    return [(r.method, r.path) for r in api.requests]


@pytest.fixture
def phone(paired):
    paired.reply("GET", "/v1/status", 200, STATUS_16)
    paired.reply("POST", PLAN_PATH, 200, plan())
    paired.reply("POST", APPLY_PATH, 201, {"successor": {"id": NEW, "name": "Example Water Heater"},
                                           "succession": SUCCESSION})
    paired.reply("GET", f"/v1/assets/{ASSET}", 200, {"asset": {"id": ASSET, "retiredOn": "2026-09-30"}})
    return paired


def replace(**arguments):
    arguments.setdefault("asset_id", ASSET)
    arguments.setdefault("name", "Example Water Heater")
    arguments.setdefault("retired_on", "2026-09-30")
    return server_module.replace_asset(**arguments)


def test_plan_only_is_the_default_and_the_plan_comes_back_as_sent(phone) -> None:
    answer = replace(category="Plumbing", carry_notes=True, schedule_ids=["s2", "s1"],
                     schedule_start_on="2026-10-01", moved_tag_ids=["t1"])
    assert answer == plan()
    assert calls(phone) == [("GET", "/v1/status"), ("POST", PLAN_PATH)]
    sent = body_of(phone.last())
    assert list(sent) == [k for k in command_shapes.REPLACE_DRAFT_KEYS if k != "sourcesDigest"]
    assert sent["successor"] == {"name": "Example Water Heater", "category": "Plumbing"}
    assert (sent["retiredOn"], sent["carryNotes"], sent["carrySeason"], sent["manualPhase"]) == (
        "2026-09-30", True, False, None)
    assert (sent["scheduleIds"], sent["groupIds"], sent["movedTagIds"]) == (["s2", "s1"], [], ["t1"])
    assert sent["scheduleStartOn"] == "2026-10-01"
    assert replace(carry_setup=True)["sourcesDigest"] == DIGEST
    assert body_of(phone.last())["scheduleStartOn"] is None, "schedule_start_on is never defaulted"


def test_the_apply_sends_the_plans_own_digest_with_the_identical_draft(phone) -> None:
    answer = replace(schedule_ids=["s1"], schedule_start_on="2026-10-01", plan_only=False)
    assert calls(phone) == [("GET", "/v1/status"), ("POST", PLAN_PATH), ("POST", APPLY_PATH),
                            ("GET", f"/v1/assets/{ASSET}")]
    planned, applied = body_of(phone.requests[1]), body_of(phone.requests[2])
    assert applied.pop("sourcesDigest") == DIGEST
    assert applied == planned
    assert answer["decision"] == "CREATED"
    assert answer["successor"]["id"] == NEW and answer["succession"] == SUCCESSION
    assert answer["predecessor"]["id"] == ASSET


@pytest.mark.parametrize("unclean", [
    plan(problems=[{"code": "REPLACE_PHASE_REQUIRED", "field": "manualPhase", "problem": "PhaseRequired"}]),
    plan(eligible=False, blockedBy="asset_transferred_out"),
    plan(sourcesDigest=None),
    plan(eligible=False),
])
def test_a_plan_that_is_not_clean_is_refused_here_and_never_applied(phone, unclean) -> None:
    phone.reply("POST", PLAN_PATH, 200, unclean)
    with pytest.raises(ToolError, match="nothing was applied"):
        replace(plan_only=False)
    assert ("POST", APPLY_PATH) not in calls(phone)


@pytest.mark.parametrize("via", ["plan", "apply"])
def test_already_replaced_by_the_requested_name_is_identical_and_any_other_name_is_refused(phone, via) -> None:
    if via == "plan":
        phone.reply("POST", PLAN_PATH, 200, plan(eligible=False, blockedBy="ASSET_ALREADY_REPLACED"))
    else:
        phone.reply("POST", APPLY_PATH, 409, {"error": {"code": "ASSET_ALREADY_REPLACED", "message": "replaced",
                                                        "problems": [f"ReplacedBy(successorAssetId={NEW})"]}})
    phone.reply("GET", f"/v1/assets/{ASSET}/succession", 200, {"replaces": None, "replacedBy": SUCCESSION})
    phone.reply("GET", f"/v1/assets/{NEW}", 200, {"asset": {"id": NEW, "name": "Example Water Heater"}})
    answer = replace(plan_only=False)
    assert answer["decision"] == "IDENTICAL"
    assert (answer["successor"]["id"], answer["succession"], answer["predecessor"]["id"]) == (NEW, SUCCESSION, ASSET)
    assert calls(phone).count(("POST", APPLY_PATH)) == (1 if via == "apply" else 0)

    with pytest.raises(ToolError, match="ASSET_ALREADY_REPLACED"):
        replace(name="Another Heater", plan_only=False)


def test_a_stale_apply_passes_through_with_plan_again(phone) -> None:
    phone.reply("POST", APPLY_PATH, 409, {"error": {"code": "REPLACE_STALE", "message": "changed",
                                                    "field": "sourcesDigest"}})
    with pytest.raises(ToolError, match="REPLACE_STALE") as raised:
        replace(plan_only=False)
    assert "plan again" in str(raised.value) and "confirm" in str(raised.value)
    assert calls(phone).count(("POST", APPLY_PATH)) == 1


@pytest.mark.parametrize("failure", [httpx.ReadTimeout, httpx.RemoteProtocolError])
def test_a_lost_apply_answer_is_unknown_and_says_read_the_succession(phone, monkeypatch, failure) -> None:
    real = httpx.request

    def lost(method, url, **kwargs):
        if url.endswith("/replace"):
            raise failure("no answer", request=httpx.Request(method, url))
        return real(method, url, **kwargs)

    monkeypatch.setattr(httpx, "request", lost)
    answer = replace(plan_only=False)
    assert answer["decision"] == "UNKNOWN"
    assert "get_asset_succession" in answer["next"]


@pytest.mark.parametrize("tags", ["all", ["all"], ["ALL"], ["*"], ["Heater tag"], ["t*"], [""], ["t1", None]])
def test_tags_move_by_binding_id_only(phone, tags) -> None:
    with pytest.raises(ToolError, match="binding id"):
        replace(moved_tag_ids=tags)
    assert phone.requests == []


def test_both_tools_need_schema_16_and_name_a_missing_route(phone) -> None:
    missing = {"error": {"code": "not_found", "message": "no such route"}}
    phone.reply("GET", f"/v1/assets/{ASSET}/replace-offer", 404, missing)
    phone.reply("POST", PLAN_PATH, 404, missing)
    for call in (lambda: server_module.get_replace_offer(asset_id=ASSET), lambda: replace(plan_only=False)):
        with pytest.raises(ToolError, match="APP_ROUTE_MISSING"):
            call()
    assert ("POST", APPLY_PATH) not in calls(phone)

    phone.requests.clear()
    server_module.device.schema_version = None
    phone.reply("GET", "/v1/status", 200, dict(STATUS_16, schemaVersion=15))
    for call in (lambda: server_module.get_replace_offer(asset_id=ASSET), lambda: replace()):
        with pytest.raises(ToolError, match="APP_SCHEMA_TOO_OLD"):
            call()
    assert calls(phone) == [("GET", "/v1/status")]


def test_get_replace_offer_answers_as_sent(phone) -> None:
    offer = {"eligible": True, "held": False, "replacedBy": None, "tags": [{"id": "t1", "label": "Heater"}],
             "schedules": [{"id": "s1", "timeInterval": 1}], "childNames": [], "openLoan": False}
    phone.reply("GET", f"/v1/assets/{ASSET}/replace-offer", 200, offer)
    assert server_module.get_replace_offer(asset_id=ASSET) == offer
    assert calls(phone) == [("GET", "/v1/status"), ("GET", f"/v1/assets/{ASSET}/replace-offer")]


def test_the_signature_mirrors_the_draft_and_the_docstring_says_plan_then_apply() -> None:
    parameters = inspect.signature(server_module.replace_asset).parameters
    assert parameters["plan_only"].default is True
    assert parameters["schedule_start_on"].default is None
    assert parameters["name"].default is inspect.Parameter.empty
    top = {server_module._wire(n) for n in parameters} - {"assetId", "planOnly"}
    successor = top - set(command_shapes.REPLACE_DRAFT_KEYS)
    assert successor == set(command_shapes.REPLACE_SUCCESSOR_KEYS)
    assert top - successor == set(command_shapes.REPLACE_DRAFT_KEYS) - {"successor", "sourcesDigest"}
    assert list(inspect.signature(server_module.get_replace_offer).parameters) == ["asset_id"]
    doc = " ".join(inspect.getdoc(server_module.replace_asset).split())
    for words in ("Plan first", "`plan_only=True` (the default)", "only a clean, eligible plan", "`sourcesDigest`",
                  "`REPLACE_STALE`", "plan again", "`ASSET_ALREADY_REPLACED`", "IDENTICAL", "never writes NFC",
                  "by id", "safe to run again", "get_asset_succession"):
        assert words in doc, words

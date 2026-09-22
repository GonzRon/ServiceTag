"""The archive-writer test matrix from the task brief: byte-for-byte determinism (including across
`PYTHONHASHSEED` values), the structural no-clock/no-environment/no-random/no-uuid4 guard, the zip
shape, the size cap, and the manifest's exact fields.
"""

from __future__ import annotations

import hashlib
import io
import json
import os
import re
import subprocess
import sys
import tokenize
import zipfile
from datetime import datetime, timezone
from io import StringIO
from pathlib import Path
from typing import Any

import pytest

from servicetag_bundle.archive import (
    ArchiveTooLarge,
    DATA_ENTRY,
    MANIFEST_ENTRY,
    archive_bytes,
    write_archive,
)
from servicetag_bundle.ids import namespace_of, row_id
from servicetag_bundle.rows import build_rows
from servicetag_bundle.source import parse_source

_SRC_ROOT = Path(__file__).resolve().parents[1] / "src"


# ---- fixture ------------------------------------------------------------------------------------

def rich_source() -> dict[str, Any]:
    """One asset with two definitions, a profile carrying a field and a consumable, and one event
    carrying a measurement and a consumable usage -- enough for every manifest count to be
    non-zero, including the nested tallies (`profileFields`, `profileConsumables`, `measurements`,
    `consumableUsages`)."""
    return {
        "formatVersion": 1,
        "namespace": "widget-farm",
        "bundleKey": "stage-a",
        "asOf": "2026-09-21T00:00:00Z",
        "tzId": "America/Denver",
        "assets": [
            {
                "key": "widget-mixer",
                "name": "Widget Mixer 3000",
                "definitions": [
                    {"key": "ph", "label": "pH", "valueType": "NUMBER"},
                    {"key": "temp", "label": "Temperature", "valueType": "NUMBER"},
                ],
                "profiles": [
                    {
                        "key": "water-test",
                        "name": "Water Test",
                        "eventKind": "MEASUREMENT",
                        "fields": [{"definition": "ph", "required": True}],
                        "consumables": [
                            {"key": "filter", "name": "Filter", "defaultQuantity": 2, "unit": "pcs"},
                        ],
                    },
                ],
                "events": [
                    {
                        "key": "e1",
                        "kind": "MEASUREMENT",
                        "occurredOn": "2026-09-20",
                        "title": "Morning check",
                        "profile": "water-test",
                        "values": {"ph": 7.5},
                        "consumables": [
                            {"key": "filter", "name": "Filter", "quantity": 1, "unit": "pcs"},
                        ],
                    },
                ],
            },
        ],
    }


def _source():
    return parse_source(rich_source())


def asymmetric_source() -> dict[str, Any]:
    """A source where every nested tally differs from its parent table's size *and* from every
    other table's size (2 assets, 4 definitions, 1 profile with 2 fields and 3 consumables, 2
    events -- `e1` with 3 values and 3 consumable usages, `e2` bare) so a writer that summed the
    wrong list -- `len(assetEvents)` where it should sum `len(e["measurements"]) for e in
    assetEvents`, say -- cannot pass by coincidence the way `measurements == consumableUsages ==
    len(assetEvents) == 2` once did here."""
    return {
        "formatVersion": 1,
        "namespace": "widget-farm",
        "bundleKey": "stage-a",
        "asOf": "2026-09-21T00:00:00Z",
        "tzId": "America/Denver",
        "assets": [
            {
                "key": "widget-fan",
                "name": "Widget Fan",
                "parent": "widget-mixer",
            },
            {
                "key": "widget-mixer",
                "name": "Widget Mixer 3000",
                "definitions": [
                    {"key": "ph", "label": "pH", "valueType": "NUMBER"},
                    {"key": "temp", "label": "Temperature", "valueType": "NUMBER"},
                    {"key": "humidity", "label": "Humidity", "valueType": "NUMBER"},
                    {"key": "pressure", "label": "Pressure", "valueType": "NUMBER"},
                ],
                "profiles": [
                    {
                        "key": "water-test",
                        "name": "Water Test",
                        "eventKind": "MEASUREMENT",
                        "fields": [
                            {"definition": "ph", "required": True},
                            {"definition": "temp"},
                        ],
                        "consumables": [
                            {"key": "filter", "name": "Filter", "defaultQuantity": 2, "unit": "pcs"},
                            {"key": "salt", "name": "Salt", "defaultQuantity": 1, "unit": "kg"},
                            {"key": "wipes", "name": "Wipes", "defaultQuantity": 5, "unit": "pcs"},
                        ],
                    },
                ],
                "events": [
                    {
                        "key": "e1",
                        "kind": "MEASUREMENT",
                        "occurredOn": "2026-09-20",
                        "title": "Morning check",
                        "profile": "water-test",
                        "values": {"ph": 7.5, "temp": 68, "humidity": 55},
                        "consumables": [
                            {"key": "filter", "name": "Filter", "quantity": 1, "unit": "pcs"},
                            {"key": "salt", "name": "Salt", "quantity": 1, "unit": "kg"},
                            {"key": "wipes", "name": "Wipes", "quantity": 2, "unit": "pcs"},
                        ],
                    },
                    {
                        "key": "e2",
                        "kind": "NOTE",
                        "occurredOn": "2026-09-19",
                        "title": "Second check",
                    },
                ],
            },
        ],
    }


# ---- determinism ------------------------------------------------------------------------------

def test_archive_bytes_identical_across_processes_with_different_hash_seeds(tmp_path):
    source_path = tmp_path / "source.json"
    source_path.write_text(json.dumps(rich_source()), encoding="utf-8")

    script = (
        "import sys\n"
        "from servicetag_bundle.source import load_source\n"
        "from servicetag_bundle.archive import archive_bytes\n"
        f"sys.stdout.buffer.write(archive_bytes(load_source({str(source_path)!r})))\n"
    )

    def run(seed: str) -> bytes:
        env = dict(os.environ)
        env["PYTHONHASHSEED"] = seed
        result = subprocess.run(
            [sys.executable, "-c", script], capture_output=True, env=env, check=True,
        )
        return result.stdout

    a = run("1")
    b = run("987654321")
    assert a == b


def test_archive_bytes_same_source_twice_in_process():
    a = archive_bytes(_source())
    b = archive_bytes(parse_source(rich_source()))
    assert a == b


# ---- zip shape ------------------------------------------------------------------------------

def test_zip_has_exactly_two_entries_in_order():
    archive = archive_bytes(_source())
    with zipfile.ZipFile(io.BytesIO(archive)) as zf:
        names = zf.namelist()
    assert names == [MANIFEST_ENTRY, DATA_ENTRY]


def test_zip_entries_have_pinned_zipinfo_fields():
    archive = archive_bytes(_source())
    with zipfile.ZipFile(io.BytesIO(archive)) as zf:
        infos = zf.infolist()
    assert len(infos) == 2
    for info in infos:
        assert info.date_time == (1980, 1, 1, 0, 0, 0)
        assert info.compress_type == zipfile.ZIP_DEFLATED
        assert info.create_system == 3
        assert info.external_attr == 0o644 << 16
        assert info.extra == b""


# ---- structural: no clock, environment, random or uuid4 in src/ -------------------------------

def _strip_comments_and_strings(source_text: str) -> str:
    """Blanks out every COMMENT and STRING token (docstrings included), preserving line/column
    positions, so the word-anchored regexes below only ever see real code."""
    lines = source_text.splitlines(keepends=True)
    try:
        tokens = list(tokenize.generate_tokens(StringIO(source_text).readline))
    except tokenize.TokenizeError:
        return source_text
    for tok in tokens:
        if tok.type not in (tokenize.COMMENT, tokenize.STRING):
            continue
        (start_row, start_col), (end_row, end_col) = tok.start, tok.end
        if start_row == end_row:
            line = lines[start_row - 1]
            lines[start_row - 1] = line[:start_col] + " " * (end_col - start_col) + line[end_col:]
        else:
            first = lines[start_row - 1]
            first_len = len(first.rstrip("\n"))
            lines[start_row - 1] = first[:start_col] + " " * (first_len - start_col) + first[first_len:]
            for row in range(start_row, end_row - 1):
                body = lines[row]
                body_len = len(body.rstrip("\n"))
                lines[row] = " " * body_len + body[body_len:]
            last = lines[end_row - 1]
            lines[end_row - 1] = " " * end_col + last[end_col:]
    return "".join(lines)


_FORBIDDEN = [
    re.compile(r"\btime\."),
    re.compile(r"datetime\.now"),
    re.compile(r"datetime\.today"),
    re.compile(r"os\.environ"),
    re.compile(r"\brandom\b"),
    re.compile(r"uuid4"),
    re.compile(r"\butcnow\b"),
    re.compile(r"date\.today"),
    re.compile(r"os\.getenv"),
]

#: Every module the walk below must actually visit -- if `_SRC_ROOT` ever stopped resolving (a
#: layout change, a rename), `violations == []` would pass vacuously on zero files scanned.
_EXPECTED_MODULES = {
    "servicetag_bundle/__init__.py",
    "servicetag_bundle/archive.py",
    "servicetag_bundle/cli.py",
    "servicetag_bundle/currencies.py",
    "servicetag_bundle/ids.py",
    "servicetag_bundle/rows.py",
    "servicetag_bundle/source.py",
    "servicetag_bundle/validate.py",
}


def test_no_clock_environment_random_or_uuid4_calls_in_src():
    violations: list[str] = []
    scanned: set[str] = set()
    for path in sorted(_SRC_ROOT.rglob("*.py")):
        rel = str(path.relative_to(_SRC_ROOT))
        scanned.add(rel)
        stripped = _strip_comments_and_strings(path.read_text(encoding="utf-8"))
        for pattern in _FORBIDDEN:
            for match in pattern.finditer(stripped):
                line_no = stripped.count("\n", 0, match.start()) + 1
                violations.append(f"{rel}:{line_no}: {pattern.pattern!r}")
    assert violations == []
    assert scanned == _EXPECTED_MODULES


def test_the_guard_actually_catches_a_real_clock_call(tmp_path):
    """Proves the previous test isn't vacuous: a real `datetime.now()` call, outside any comment
    or string, must be caught."""
    planted = "import datetime\n\n\ndef f():\n    return datetime.datetime.now()\n"
    stripped = _strip_comments_and_strings(planted)
    assert any(p.search(stripped) for p in _FORBIDDEN)


# ---- size cap -----------------------------------------------------------------------------------

def test_build_refuses_an_archive_over_the_size_cap(tmp_path, monkeypatch):
    import servicetag_bundle.archive as archive_module

    monkeypatch.setattr(archive_module, "MAX_ARCHIVE_BYTES", 10)
    out = tmp_path / "out.zip"
    with pytest.raises(ArchiveTooLarge):
        write_archive(_source(), out)
    assert not out.exists()


def test_inspect_sized_archive_matches_file_size(tmp_path):
    out = tmp_path / "out.zip"
    write_archive(_source(), out)
    assert out.stat().st_size == len(archive_bytes(_source()))


# ---- the manifest -------------------------------------------------------------------------------

def test_manifest_fixed_version_fields():
    manifest, _ = _built()
    assert manifest["formatVersion"] == 5
    assert manifest["schemaVersion"] == 5
    assert manifest["artifactFormatVersion"] == 1
    assert manifest["artifactCount"] == 0
    assert manifest["artifactBytes"] == 0
    assert manifest["appVersion"] == "servicetag-bundle/0.1.0"


def test_manifest_key_set_matches_backup_manifest_exactly():
    """`BackupManifest` (`BackupFormat.kt`) has exactly these 10 fields and the decoder has no
    `ignoreUnknownKeys` -- an extra key here would decode-fail as `BackupCorrupt`."""
    manifest, _ = _built()
    assert set(manifest) == {
        "formatVersion", "appVersion", "schemaVersion", "createdAt", "counts", "dataSha256",
        "backupSetId", "artifactFormatVersion", "artifactCount", "artifactBytes",
    }


def _built() -> tuple[dict[str, Any], bytes]:
    from servicetag_bundle.archive import _build

    return _build(_source())


def test_manifest_backup_set_id():
    manifest, _ = _built()
    ns = namespace_of(_source())
    assert manifest["backupSetId"] == row_id(ns, "set:stage-a")
    assert manifest["backupSetId"]  # non-blank


def test_manifest_created_at_is_as_of_millis():
    manifest, _ = _built()
    expected = int(
        (datetime(2026, 9, 21, 0, 0, 0, tzinfo=timezone.utc) - datetime(1970, 1, 1, tzinfo=timezone.utc))
        .total_seconds()
    ) * 1000
    assert manifest["createdAt"] == expected


def test_manifest_data_sha256_matches_the_written_data_entry():
    archive = archive_bytes(_source())
    with zipfile.ZipFile(io.BytesIO(archive)) as zf:
        manifest = json.loads(zf.read(MANIFEST_ENTRY))
        data_bytes = zf.read(DATA_ENTRY)
    assert manifest["dataSha256"] == hashlib.sha256(data_bytes).hexdigest()


def test_manifest_counts_match_backup_codec_keys_and_numbers():
    """Every nested tally in `asymmetric_source()` differs from its parent table's size, so this
    would fail if `_counts` summed the wrong list (e.g. table size instead of the nested rows)."""
    from servicetag_bundle.archive import _build

    manifest, _ = _build(parse_source(asymmetric_source()))
    assert manifest["counts"] == {
        "assets": 2,
        "nfcTags": 0,
        "externalLinks": 0,
        "measurementDefinitions": 4,
        "eventProfiles": 1,
        "assetEvents": 2,
        "profileFields": 2,
        "profileConsumables": 3,
        "measurements": 3,
        "consumableUsages": 3,
        "attachments": 0,
    }


def test_data_json_round_trips_to_build_rows():
    source = _source()
    archive = archive_bytes(source)
    with zipfile.ZipFile(io.BytesIO(archive)) as zf:
        data = json.loads(zf.read(DATA_ENTRY))
    assert data == build_rows(source)


def _pinned_dumps(obj: Any) -> bytes:
    return json.dumps(
        obj, sort_keys=True, separators=(",", ":"), ensure_ascii=True, allow_nan=False,
    ).encode("utf-8")


def test_manifest_and_data_entry_bytes_match_the_pinned_serialisation():
    """The cross-seed subprocess test can't catch a lost `sort_keys` (CPython dicts already
    iterate in insertion order regardless of `PYTHONHASHSEED`) -- so pin the actual bytes."""
    source = _source()
    manifest, archive = _built()
    with zipfile.ZipFile(io.BytesIO(archive)) as zf:
        manifest_bytes = zf.read(MANIFEST_ENTRY)
        data_bytes = zf.read(DATA_ENTRY)
    assert manifest_bytes == _pinned_dumps(manifest)
    assert data_bytes == _pinned_dumps(build_rows(source))


def test_data_json_escapes_non_ascii_with_ensure_ascii():
    source_dict = rich_source()
    source_dict["assets"][0]["manufacturer"] = "Schmöltz Werke"  # fictional; needs escaping
    archive = archive_bytes(parse_source(source_dict))
    with zipfile.ZipFile(io.BytesIO(archive)) as zf:
        raw = zf.read(DATA_ENTRY)
    assert b"Schm\\u00f6ltz Werke" in raw
    assert "ö".encode("utf-8") not in raw


# ---- the size cap applies to archive_bytes too (S7) ----------------------------------------------

def test_archive_bytes_also_refuses_an_archive_over_the_size_cap(monkeypatch):
    import servicetag_bundle.archive as archive_module

    monkeypatch.setattr(archive_module, "MAX_ARCHIVE_BYTES", 10)
    with pytest.raises(ArchiveTooLarge):
        archive_bytes(_source())


# ---- the manifest's createdAt ties to every row's createdAt/updatedAt (S10) -----------------------

def test_manifest_created_at_equals_every_rows_created_at_and_updated_at():
    source = _source()
    manifest, archive = _built()
    with zipfile.ZipFile(io.BytesIO(archive)) as zf:
        data = json.loads(zf.read(DATA_ENTRY))
    for table in ("assets", "measurementDefinitions", "eventProfiles", "assetEvents"):
        for row in data[table]:
            assert row["createdAt"] == manifest["createdAt"]
            assert row["updatedAt"] == manifest["createdAt"]


# ---- write_archive is atomic (S8) -----------------------------------------------------------------

def test_write_archive_leaves_nothing_behind_on_a_mid_write_failure(tmp_path, monkeypatch):
    import servicetag_bundle.archive as archive_module

    def _boom(*args, **kwargs):
        raise OSError("simulated failure")

    monkeypatch.setattr(archive_module.os, "replace", _boom)
    out = tmp_path / "out.zip"

    with pytest.raises(OSError):
        write_archive(_source(), out)

    assert not out.exists()
    assert list(tmp_path.iterdir()) == []  # the ".partial" temp file was cleaned up too


def test_write_archive_does_not_disturb_a_pre_existing_partial_sibling(tmp_path):
    """The temp name used to be the predictable `out.name + ".partial"`; a build would silently
    clobber a pre-existing file under that exact name. `tempfile.mkstemp` picks a unique name, so
    that old sibling is never touched."""
    out = tmp_path / "out.zip"
    stray = tmp_path / (out.name + ".partial")
    stray.write_bytes(b"unrelated sibling content")

    write_archive(_source(), out)

    assert stray.read_bytes() == b"unrelated sibling content"
    assert out.exists()
    leftovers = [p for p in tmp_path.iterdir() if p not in (out, stray)]
    assert leftovers == []  # no stray mkstemp temp file left behind on success either

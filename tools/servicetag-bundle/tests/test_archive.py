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
]


def test_no_clock_environment_random_or_uuid4_calls_in_src():
    violations: list[str] = []
    for path in sorted(_SRC_ROOT.rglob("*.py")):
        stripped = _strip_comments_and_strings(path.read_text(encoding="utf-8"))
        for pattern in _FORBIDDEN:
            for match in pattern.finditer(stripped):
                line_no = stripped.count("\n", 0, match.start()) + 1
                violations.append(f"{path.relative_to(_SRC_ROOT)}:{line_no}: {pattern.pattern!r}")
    assert violations == []


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
    manifest, _ = _built()
    assert manifest["counts"] == {
        "assets": 1,
        "nfcTags": 0,
        "externalLinks": 0,
        "measurementDefinitions": 2,
        "eventProfiles": 1,
        "assetEvents": 1,
        "profileFields": 1,
        "profileConsumables": 1,
        "measurements": 1,
        "consumableUsages": 1,
        "attachments": 0,
    }


def test_data_json_round_trips_to_build_rows():
    source = _source()
    archive = archive_bytes(source)
    with zipfile.ZipFile(io.BytesIO(archive)) as zf:
        data = json.loads(zf.read(DATA_ENTRY))
    assert data == build_rows(source)

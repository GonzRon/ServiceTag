"""The decoder-conformance golden test (Task 4): a payload-level comparison between the archive
freshly generated from the committed fixture and the committed JVM test resource
(`core/src/test/resources/stage-a-synthetic-estate.zip`, built by the CLI from
`fixtures/synthetic-estate.json`).

Raw zip bytes are never compared across machines: DEFLATE output depends on the zlib build the
interpreter links against (this workstation's CPython links zlib-ng, CI links stock zlib), so two
otherwise-identical builds can differ byte-for-byte while decoding identically. Byte identity
(Invariant 1, `test_archive.py`'s `PYTHONHASHSEED` test) is a same-machine, cross-process guarantee
only -- see the task-4 addendum (controller ruling S6). What this test compares instead, at the
payload level: the same entry names in the same order; identical *decompressed* bytes per entry;
identical `ZipInfo` `date_time`/`compress_type`/`create_system`/`external_attr`/`extra`; and that
`sha256(data.json bytes)` equals the committed manifest's `dataSha256` (which is what
`BackupCodec.decode` actually checks).

Every test here opens the committed resource directly, with no `skipif` guard -- so a missing
resource fails the run (a `FileNotFoundError` from `zipfile.ZipFile`), it never skips. A generator
change that alters the archive's bytes therefore fails CI until the fixture is deliberately
regenerated and both files (`fixtures/synthetic-estate.json` and the committed resource) are
committed together -- see the README's regeneration step.
"""

from __future__ import annotations

import hashlib
import io
import json
import zipfile
from pathlib import Path

from servicetag_bundle.archive import DATA_ENTRY, MANIFEST_ENTRY, archive_bytes
from servicetag_bundle.source import load_source

_FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "synthetic-estate.json"

#: The JVM test resource this tool's output is pinned against -- `core/src/test/resources`,
#: three levels up from `tools/servicetag-bundle` (the repository root).
_RESOURCE = (
    Path(__file__).resolve().parents[3]
    / "core" / "src" / "test" / "resources" / "stage-a-synthetic-estate.zip"
)


def _generated_zip() -> zipfile.ZipFile:
    source = load_source(_FIXTURE)
    return zipfile.ZipFile(io.BytesIO(archive_bytes(source)))


def _committed_zip() -> zipfile.ZipFile:
    # No existence guard: a missing file raises FileNotFoundError here, which pytest reports as
    # a failing test, never a skip.
    return zipfile.ZipFile(_RESOURCE)


def test_committed_resource_is_present_and_readable():
    with _committed_zip() as committed:
        assert committed.namelist()  # a readable zip with at least one entry


def test_entry_names_match_in_the_same_order():
    with _committed_zip() as committed, _generated_zip() as generated:
        assert committed.namelist() == [MANIFEST_ENTRY, DATA_ENTRY]
        assert generated.namelist() == [MANIFEST_ENTRY, DATA_ENTRY]
        assert committed.namelist() == generated.namelist()


def test_decompressed_entry_bytes_are_identical():
    with _committed_zip() as committed, _generated_zip() as generated:
        for name in (MANIFEST_ENTRY, DATA_ENTRY):
            assert committed.read(name) == generated.read(name), f"{name}: decompressed bytes differ"


def test_zipinfo_fields_are_identical_per_entry():
    with _committed_zip() as committed, _generated_zip() as generated:
        committed_by_name = {info.filename: info for info in committed.infolist()}
        generated_by_name = {info.filename: info for info in generated.infolist()}
        assert set(committed_by_name) == set(generated_by_name)
        for name, committed_info in committed_by_name.items():
            generated_info = generated_by_name[name]
            assert committed_info.date_time == generated_info.date_time, name
            assert committed_info.compress_type == generated_info.compress_type, name
            assert committed_info.create_system == generated_info.create_system, name
            assert committed_info.external_attr == generated_info.external_attr, name
            assert committed_info.extra == generated_info.extra, name


def test_data_json_sha256_matches_the_committed_manifest():
    with _committed_zip() as committed:
        manifest = json.loads(committed.read(MANIFEST_ENTRY))
        data_bytes = committed.read(DATA_ENTRY)
    assert hashlib.sha256(data_bytes).hexdigest() == manifest["dataSha256"]

"""The CLI test matrix from the task brief: `check`, `build`, `inspect` -- exit codes, stderr
`path: message` lines, and the write-nothing-on-failure invariant.
"""

from __future__ import annotations

import json
import zipfile
from pathlib import Path

from servicetag_bundle.cli import main

from conftest import rich_source


def _write_source(path: Path, obj: dict) -> Path:
    path.write_text(json.dumps(obj), encoding="utf-8")
    return path


# ---- check ----------------------------------------------------------------------------------

def test_check_valid_source_exits_zero(tmp_path, capsys):
    source_path = _write_source(tmp_path / "source.json", rich_source())
    code = main(["check", str(source_path)])
    assert code == 0
    out = capsys.readouterr().out
    assert str(source_path) in out


def test_check_invalid_source_exits_one_with_path_on_stderr_and_creates_nothing(tmp_path, capsys):
    bad = dict(rich_source())
    bad["formatVersion"] = 2
    source_path = _write_source(tmp_path / "source.json", bad)
    before = set(tmp_path.iterdir())

    code = main(["check", str(source_path)])

    assert code == 1
    err = capsys.readouterr().err
    assert err.strip() == f"{source_path}: formatVersion: must be 1"
    assert set(tmp_path.iterdir()) == before


def test_check_reports_a_present_deferred(tmp_path, capsys):
    source = rich_source()
    source["deferred"] = {"note": "kept aside"}
    source_path = _write_source(tmp_path / "source.json", source)

    code = main(["check", str(source_path)])

    assert code == 0
    out = capsys.readouterr().out
    assert "deferred" in out.lower()


# ---- build ------------------------------------------------------------------------------------

def test_build_invalid_source_writes_nothing(tmp_path, capsys):
    bad = dict(rich_source())
    bad["formatVersion"] = 2
    source_path = _write_source(tmp_path / "source.json", bad)
    out_path = tmp_path / "out.zip"

    code = main(["build", str(source_path), str(out_path)])

    assert code == 1
    assert not out_path.exists()
    assert capsys.readouterr().err.strip() == "formatVersion: must be 1"


def test_build_writes_exactly_one_file_at_the_given_path(tmp_path, capsys):
    source_path = _write_source(tmp_path / "source.json", rich_source())
    out_path = tmp_path / "out.zip"
    before = set(tmp_path.iterdir())

    code = main(["build", str(source_path), str(out_path)])

    assert code == 0
    after = set(tmp_path.iterdir())
    assert after - before == {out_path}
    out = capsys.readouterr().out
    assert "backupSetId=" in out
    assert "assets=1" in out


def test_build_does_not_create_missing_parent_directories(tmp_path, capsys):
    source_path = _write_source(tmp_path / "source.json", rich_source())
    out_path = tmp_path / "missing-dir" / "out.zip"

    code = main(["build", str(source_path), str(out_path)])

    assert code == 1
    assert not out_path.parent.exists()
    err = capsys.readouterr().err
    assert str(out_path) in err


def test_build_without_force_refuses_an_existing_output(tmp_path, capsys):
    source_path = _write_source(tmp_path / "source.json", rich_source())
    out_path = tmp_path / "out.zip"
    out_path.write_bytes(b"already here")

    code = main(["build", str(source_path), str(out_path)])

    assert code == 1
    assert out_path.read_bytes() == b"already here"
    err = capsys.readouterr().err
    assert str(out_path) in err


def test_build_with_force_overwrites_an_existing_output(tmp_path, capsys):
    source_path = _write_source(tmp_path / "source.json", rich_source())
    out_path = tmp_path / "out.zip"
    out_path.write_bytes(b"already here")

    code = main(["build", str(source_path), str(out_path), "--force"])

    assert code == 0
    assert out_path.read_bytes() != b"already here"


def test_build_refuses_an_archive_over_the_size_cap(tmp_path, capsys, monkeypatch):
    import servicetag_bundle.archive as archive_module

    monkeypatch.setattr(archive_module, "MAX_ARCHIVE_BYTES", 10)
    source_path = _write_source(tmp_path / "source.json", rich_source())
    out_path = tmp_path / "out.zip"

    code = main(["build", str(source_path), str(out_path)])

    assert code == 1
    assert not out_path.exists()
    err = capsys.readouterr().err
    assert str(out_path) in err
    assert "byte" in err


# ---- inspect ------------------------------------------------------------------------------------

def test_inspect_prints_counts_and_size_of_a_built_archive(tmp_path, capsys):
    source_path = _write_source(tmp_path / "source.json", rich_source())
    out_path = tmp_path / "out.zip"
    main(["build", str(source_path), str(out_path)])
    capsys.readouterr()  # discard build's output

    code = main(["inspect", str(out_path)])

    assert code == 0
    out = capsys.readouterr().out
    assert str(out_path.stat().st_size) in out
    assert "assets=1" in out
    assert "formatVersion=5" in out
    assert "schemaVersion=5" in out


def test_inspect_a_format_le4_manifest_reports_cleanly_instead_of_a_keyerror(tmp_path, capsys):
    """A format <=4 archive's manifest legitimately lacks `backupSetId` and the `artifact*`
    fields (BackupCodec's own class doc: format 4 has "none of the four new manifest fields").
    `inspect` must report what's there, never crash."""
    archive_path = tmp_path / "old.zip"
    minimal_manifest = json.dumps(
        {"formatVersion": 3, "appVersion": "old-app/1.0", "schemaVersion": 2, "createdAt": 0,
         "counts": {"assets": 0}, "dataSha256": "0" * 64}
    ).encode("utf-8")
    with zipfile.ZipFile(archive_path, "w") as zf:
        zf.writestr("manifest.json", minimal_manifest)
        zf.writestr("data.json", b"{}")

    code = main(["inspect", str(archive_path)])

    assert code == 0
    out = capsys.readouterr().out
    assert "formatVersion=3" in out
    assert "backupSetId=-" in out
    assert "assets=0" in out


def test_inspect_never_decodes_a_row_even_when_data_json_is_garbage(tmp_path, capsys):
    """`inspect` reads only `MANIFEST_ENTRY` (`cli.py`'s `_cmd_inspect`) -- it must report cleanly
    off a complete manifest even when `data.json` is not valid JSON at all, proving the row data is
    never touched, let alone decoded (S9)."""
    archive_path = tmp_path / "garbage-data.zip"
    manifest = json.dumps(
        {"formatVersion": 5, "appVersion": "servicetag-bundle/0.1.0", "schemaVersion": 5,
         "createdAt": 0, "counts": {"assets": 1}, "dataSha256": "0" * 64}
    ).encode("utf-8")
    with zipfile.ZipFile(archive_path, "w") as zf:
        zf.writestr("manifest.json", manifest)
        zf.writestr("data.json", b"not json at all, and not even rows if it were")

    code = main(["inspect", str(archive_path)])

    assert code == 0
    out = capsys.readouterr().out
    assert "formatVersion=5" in out
    assert "assets=1" in out


def test_inspect_a_corrupt_manifest_reports_missing_fields_not_a_false_clean_bill(tmp_path, capsys):
    """Only the four format-5-only fields may default; `formatVersion`, `appVersion`,
    `schemaVersion`, `createdAt`, `counts`, `dataSha256` have no default in `BackupManifest` --
    missing any of them means the manifest is corrupt, and `inspect` must say so and exit 1,
    never print all-dashes and exit 0 as though the archive were merely old."""
    archive_path = tmp_path / "corrupt.zip"
    with zipfile.ZipFile(archive_path, "w") as zf:
        zf.writestr("manifest.json", json.dumps({"nothing": 1}).encode("utf-8"))
        zf.writestr("data.json", b"{}")

    code = main(["inspect", str(archive_path)])

    assert code == 1
    err = capsys.readouterr().err
    assert "manifest.json is missing" in err
    for field in (
        "formatVersion", "appVersion", "schemaVersion", "createdAt", "counts", "dataSha256",
    ):
        assert field in err


# ---- never a traceback (S4) ------------------------------------------------------------------

def test_check_missing_source_file_reports_cleanly_not_a_traceback(tmp_path, capsys):
    missing = tmp_path / "does-not-exist.json"

    code = main(["check", str(missing)])

    assert code == 1
    err = capsys.readouterr().err
    assert str(missing) in err
    assert "Traceback" not in err


def test_build_missing_source_file_reports_cleanly_not_a_traceback(tmp_path, capsys):
    missing = tmp_path / "does-not-exist.json"
    out_path = tmp_path / "out.zip"

    code = main(["build", str(missing), str(out_path)])

    assert code == 1
    assert not out_path.exists()
    err = capsys.readouterr().err
    assert str(missing) in err
    assert "Traceback" not in err


def test_check_malformed_json_reports_cleanly_not_a_traceback(tmp_path, capsys):
    source_path = tmp_path / "source.json"
    source_path.write_text("{not valid json", encoding="utf-8")

    code = main(["check", str(source_path)])

    assert code == 1
    err = capsys.readouterr().err
    assert str(source_path) in err
    assert "Traceback" not in err


def test_build_malformed_json_reports_cleanly_not_a_traceback(tmp_path, capsys):
    source_path = tmp_path / "source.json"
    source_path.write_text("{not valid json", encoding="utf-8")
    out_path = tmp_path / "out.zip"

    code = main(["build", str(source_path), str(out_path)])

    assert code == 1
    assert not out_path.exists()
    err = capsys.readouterr().err
    assert str(source_path) in err
    assert "Traceback" not in err


def test_build_force_onto_a_directory_reports_cleanly_not_a_traceback(tmp_path, capsys):
    source_path = _write_source(tmp_path / "source.json", rich_source())
    out_path = tmp_path / "out-dir"
    out_path.mkdir()

    code = main(["build", str(source_path), str(out_path), "--force"])

    assert code == 1
    err = capsys.readouterr().err
    assert str(out_path) in err
    assert "Traceback" not in err
    assert out_path.is_dir()  # untouched
    assert set(tmp_path.iterdir()) == {out_path, source_path}  # no stray ".partial" temp file


def test_inspect_a_manifest_that_is_not_an_object_reports_cleanly_not_a_traceback(tmp_path, capsys):
    """A `manifest.json` that decodes to a JSON list, string or number parses cleanly but isn't a
    manifest -- `key not in manifest` would otherwise raise or misbehave instead of one clean
    `path: message` line."""
    archive_path = tmp_path / "not-an-object.zip"
    with zipfile.ZipFile(archive_path, "w") as zf:
        zf.writestr("manifest.json", json.dumps([1, 2, 3]).encode("utf-8"))
        zf.writestr("data.json", b"{}")

    code = main(["inspect", str(archive_path)])

    assert code == 1
    err = capsys.readouterr().err
    assert str(archive_path) in err
    assert "not a JSON object" in err
    assert "Traceback" not in err


def test_inspect_counts_that_is_not_an_object_reports_cleanly_not_a_traceback(tmp_path, capsys):
    """`counts` present but not a JSON object (e.g. a list) parses past the missing-fields check
    but `.items()` on it would otherwise raise `AttributeError` instead of one clean line."""
    archive_path = tmp_path / "bad-counts.zip"
    manifest = json.dumps(
        {"formatVersion": 5, "appVersion": "servicetag-bundle/0.1.0", "schemaVersion": 5,
         "createdAt": 0, "counts": [1, 2, 3], "dataSha256": "0" * 64}
    ).encode("utf-8")
    with zipfile.ZipFile(archive_path, "w") as zf:
        zf.writestr("manifest.json", manifest)
        zf.writestr("data.json", b"{}")

    code = main(["inspect", str(archive_path)])

    assert code == 1
    err = capsys.readouterr().err
    assert str(archive_path) in err
    assert "counts is not a JSON object" in err
    assert "Traceback" not in err

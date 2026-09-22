"""Builds the format-5 backup archive (`manifest.json` + `data.json` inside a deterministic zip)
from a validated `Source`. No clock, environment or filesystem access beyond the one `out` path
`write_archive` is given -- every byte comes from the source itself and from `rows.build_rows`.

The zip is built by hand, entry by entry, with an explicit `zipfile.ZipInfo` per entry: passing a
bare filename to `writestr` stamps the local wall clock into the zip's local-file-header timestamp,
which would make two otherwise-identical runs produce different bytes.
"""

from __future__ import annotations

import hashlib
import json
import zipfile
from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path
from typing import Any

from .ids import namespace_of, row_id
from .rows import build_rows
from .source import Source

FORMAT_VERSION = 5
SCHEMA_VERSION = 5
ARTIFACT_FORMAT_VERSION = 1
APP_VERSION = "servicetag-bundle/0.1.0"

MANIFEST_ENTRY = "manifest.json"
DATA_ENTRY = "data.json"

#: The API's import cap (`docs/api/v1.md` Import-merge, `tools/servicetag-mcp`'s client). `build`
#: refuses an archive larger than this without writing anything.
MAX_ARCHIVE_BYTES = 4 * 1024 * 1024

_ZIP_DATE_TIME = (1980, 1, 1, 0, 0, 0)
_EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


class ArchiveError(Exception):
    """Base for archive-writing failures the CLI reports as `path: message`."""


class ArchiveTooLarge(ArchiveError):
    """The built archive exceeds `MAX_ARCHIVE_BYTES`. Raised before anything is written."""

    def __init__(self, size: int, limit: int) -> None:
        super().__init__(f"archive is {size} bytes, over the {limit} byte limit")
        self.size = size
        self.limit = limit


def _epoch_millis(dt: datetime) -> int:
    """`dt` (already UTC, per `source._parse_as_of`) as epoch milliseconds -- integer arithmetic
    only, never via a float timestamp."""
    delta = dt - _EPOCH
    return delta.days * 86_400_000 + delta.seconds * 1000 + delta.microseconds // 1000


def _dumps(obj: Any) -> bytes:
    """The pinned serialisation: compact, sorted keys, ASCII-only, no NaN/Infinity -- so the same
    object always encodes to the same bytes regardless of `PYTHONHASHSEED`."""
    text = json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=True, allow_nan=False)
    return text.encode("utf-8")


def _counts(data: dict[str, list[dict[str, Any]]]) -> dict[str, int]:
    """Exactly the keys `BackupCodec.encode` writes into `manifest.counts`
    (`BackupCodec.kt`, the `counts = mapOf(...)` block): the seven table sizes, then the four
    nested-row tallies counted individually (profile fields/consumables, measurements, consumable
    usages), then attachments."""
    return {
        "assets": len(data["assets"]),
        "nfcTags": len(data["nfcTags"]),
        "externalLinks": len(data["externalLinks"]),
        "measurementDefinitions": len(data["measurementDefinitions"]),
        "eventProfiles": len(data["eventProfiles"]),
        "assetEvents": len(data["assetEvents"]),
        "profileFields": sum(len(p["fields"]) for p in data["eventProfiles"]),
        "profileConsumables": sum(len(p["consumables"]) for p in data["eventProfiles"]),
        "measurements": sum(len(e["measurements"]) for e in data["assetEvents"]),
        "consumableUsages": sum(len(e["consumables"]) for e in data["assetEvents"]),
        "attachments": len(data["attachments"]),
    }


def _zip_info(name: str) -> zipfile.ZipInfo:
    """A `ZipInfo` pinned for reproducibility: a fixed pre-1980-epoch-adjacent timestamp, explicit
    compression and Unix attributes, and no extra field."""
    info = zipfile.ZipInfo(name, date_time=_ZIP_DATE_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = 0o644 << 16
    info.extra = b""
    return info


def _build(source: Source) -> tuple[dict[str, Any], bytes]:
    """The manifest dict and the full archive bytes, computed together so the manifest's
    `dataSha256` is hashed from exactly the bytes written into the `data.json` entry."""
    ns = namespace_of(source)
    data = build_rows(source)
    data_bytes = _dumps(data)
    as_of_millis = _epoch_millis(source.asOf)

    manifest: dict[str, Any] = {
        "formatVersion": FORMAT_VERSION,
        "appVersion": APP_VERSION,
        "schemaVersion": SCHEMA_VERSION,
        "createdAt": as_of_millis,
        "counts": _counts(data),
        "dataSha256": hashlib.sha256(data_bytes).hexdigest(),
        "backupSetId": row_id(ns, f"set:{source.bundleKey}"),
        "artifactFormatVersion": ARTIFACT_FORMAT_VERSION,
        "artifactCount": 0,
        "artifactBytes": 0,
    }
    manifest_bytes = _dumps(manifest)

    buf = BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr(_zip_info(MANIFEST_ENTRY), manifest_bytes)
        zf.writestr(_zip_info(DATA_ENTRY), data_bytes)
    return manifest, buf.getvalue()


def archive_bytes(source: Source) -> bytes:
    """The archive's exact bytes -- deterministic across processes for the same source, including
    under different `PYTHONHASHSEED` values."""
    _, archive = _build(source)
    return archive


def write_archive(source: Source, out: Path) -> dict[str, Any]:
    """Builds the archive and writes it to `out` in one shot, refusing -- without writing anything
    -- an archive over `MAX_ARCHIVE_BYTES`. Returns the manifest dict as written.

    Does not create `out`'s parent directory (a missing parent surfaces as the underlying
    `OSError`) and does not check whether `out` already exists: overwrite policy belongs to the
    CLI, which decides before ever calling this.
    """
    manifest, archive = _build(source)
    if len(archive) > MAX_ARCHIVE_BYTES:
        raise ArchiveTooLarge(len(archive), MAX_ARCHIVE_BYTES)
    Path(out).write_bytes(archive)
    return manifest

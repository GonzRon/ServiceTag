# ServiceTag

**SCAN. MAINTAIN. REMEMBER.**

ServiceTag turns physical equipment into a durable digital maintenance record.

Put a small NFC tag on a generator, furnace, hot tub, mower, snowblower, water system, UPS, pump, appliance, vehicle, tool, or other serviceable asset. Tap the tag with your Android phone and ServiceTag opens the record for that exact piece of equipment: what it is, where it is, its make, model and serial number, what has been done to it, the measurements that were taken, the documents and photos that belong with it, its current condition and health, and what maintenance is coming next.

The tag itself carries only a stable ServiceTag identity. The useful information stays in ServiceTag's local database, so the equipment record can grow over years without trying to squeeze mutable data onto the NFC tag. Backups preserve those identities, which means the same physical tags can keep working after a phone replacement, reinstall, or restore.

ServiceTag is **local-first**. It does not require an account or a cloud service to identify equipment, keep maintenance history, calculate due work, or deliver local reminders. The phone remains the source of truth. Files can live in a folder you choose, backups can be exported and restored, and a deliberately narrow local Developer API and MCP bridge make it possible to automate larger maintenance inventories without turning the app into a hosted service.

## More than an NFC label

The NFC tag is the doorway; the Asset record is the product.

A ServiceTag Asset can describe a complete system or a component of another Asset. Its Service Record keeps an auditable history of maintenance events, repairs, replacements, notes, typed measurements, meter readings, materials used, photos, manuals, receipts, and references. Quick actions make recurring work fast to record, while custom readings and profiles let different equipment collect the information that actually matters.

Maintenance is modeled separately from history. A schedule can repeat by calendar time, by meter usage, or by whichever comes first. Related equipment can be maintained as a group. Local notifications can surface due work and let you complete, snooze, or open it from the reminder.

ServiceTag 1.4 added the next layer of real-world equipment behavior:

- **Operating seasons** can be year-round, calendar-based, or started and ended manually.
- **Maintenance policy** is separate from operating season, so work can happen before a season, when a season starts, while equipment is in service, or whenever it is due.
- **Maintenance breaks** can suppress routine work without pretending it was completed.
- **Condition** records whether an Asset is operational, degraded, or down, with an immutable history.
- **Health** is derived from things such as age and overdue maintenance and remains separate from condition.
- Dashboard, maintenance, scan, and Asset-detail views consume the same canonical schedule, season, condition, and health state.

That separation matters. A snowblower may need service before winter, a hot tub may need weekly care only while its manually activated season is running, and a UPS can be marked down because of a failed battery without ServiceTag inventing a maintenance completion.

## What it does today

- **NFC identity and binding** — create Assets, write and verify ServiceTag tags, safely rebind or replace tags, and scan directly into the correct Asset.
- **Asset records and hierarchy** — identity, category, make/model/serial, location, purchase/warranty information, parent systems and components, archive/retirement state, operating season, condition, and health.
- **Service history and measurements** — maintenance journal, repairs, replacements, notes, typed readings, meters, derived readings, consumables recorded on events, and configurable quick-action profiles.
- **Maintenance scheduling and local reminders** — time and meter rules, maintenance groups, due/overdue state, snooze/postpone, seasonal service policy, maintenance breaks, completion flows, and reminder-health diagnostics.
- **Documents and references** — attach photos, manuals, receipts and other files; save web/reference links; share a document, image, URL, or note into an Asset from Android's share sheet.
- **Backup, restore, and merge foundations** — export logical backups with stable IDs and attachment artifacts, restore onto a replacement installation, and use conflict-safe additive merge machinery without silently overwriting existing rows.
- **Local automation** — a loopback-only Developer API while its screen is open, plus the workstation-side MCP tooling under [`tools/servicetag-mcp/`](tools/servicetag-mcp/README.md).

## Where it is going

The roadmap of record is [issue #76](https://github.com/GonzRon/ServiceTag/issues/76).

The immediate post-1.4 work is deliberately practical: reminder and developer-surface reliability, better handling when an existing Asset becomes seasonal, UI/NFC polish, persistent Asset categories and filters, clearer health/NFC status in the Assets list, better key-document handling, temporary lending, and finally a **Transfer Pack** workflow for equipment that permanently changes hands.

Transfer Packs are intended for cases such as selling a house where some equipment stays behind. The owner will be able to select only the Assets that are leaving, export their relevant ServiceTag history and NFC identities into a shareable package, hand that package to another ServiceTag installation through normal Android sharing, and then mark those Assets as transferred out locally. The goal is to reuse the existing backup, artifact, merge, and NFC identity machinery rather than build a second synchronization system.

The next product phase is **supplies and consumables**: a canonical catalog for filters, batteries, belts, cartridges, chemicals, fluids and other service materials, with optional stock/reorder information and replacement-on-cadence workflows. After that, ServiceTag can attach manuals, receipts, photos, links and share-intake resources directly to those supply identities.

Larger ideas such as installed-component tracking, richer backup conflict resolution, telemetry/BLE ingestion, Home Assistant integrations, and LLM-assisted equipment research remain later work rather than prerequisites for the core maintenance app.

## Local-first by design

ServiceTag is intentionally useful on one phone with no account and no backend.

That means:

- NFC tags identify Assets; they do not contain the Asset database.
- Maintenance history remains local and auditable.
- Derived schedule state and health are recomputed from canonical facts rather than authored as mutable history.
- Files are ordinary files in storage you control.
- Backups are explicit and portable.
- Automation uses the same application use cases as the UI instead of bypassing them.
- External reminder-provider/Todoist support is not part of the active roadmap; ServiceTag's local scheduler and reminders are canonical.

ServiceTag is also distinct from [NoteTag](https://github.com/GonzRon/NoteTag). ServiceTag tags identify physical Assets. NoteTag handles the separate tag-to-note/link use case.

## Build

ServiceTag is an Android/Compose application with a pure-Kotlin `:core` domain module and the shared `nfc-tag-core` library as a git submodule.

```bash
git clone --recurse-submodules https://github.com/GonzRon/ServiceTag.git
cd ServiceTag
./gradlew :app:assembleDebug
```

The main local/CI gate is:

```bash
./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest :app:assembleDebug
```

Instrumented tests run on an emulator, not on a phone holding real data:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

## Project references

- [Post-1.4 roadmap — #76](https://github.com/GonzRon/ServiceTag/issues/76)
- [Developer API v1](docs/api/v1.md)
- [ServiceTag MCP tools](tools/servicetag-mcp/README.md)
- [1.4 seasons, service policy, condition and health specification](docs/superpowers/specs/2026-09-24-servicetag-1.4-seasons-policy-condition-health.md)
- [1.3 Android share-intake specification](docs/superpowers/specs/2026-09-23-servicetag-share-intake.md)
- [1.2 operational-maintenance specification](docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md)
- [Versioning and release policy](docs/versioning.md)

ServiceTag began as the maintenance half of the older noteNFC project. The 2026 product split moved note/link tagging into NoteTag and the shared NFC mechanics into `nfc-tag-core`, leaving ServiceTag focused on one job: giving physical equipment a durable identity and a maintenance memory that stays useful over its lifetime.

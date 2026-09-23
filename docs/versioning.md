# Versioning

ServiceTag's `versionName` follows **semantic versioning**, `MAJOR.MINOR.PATCH`, in its product sense (owner ruling 2026-09-18; lineage reset to 1.0.0 on 2026-09-20). Classify the change first; only then choose the number.

| Change | Bump | ServiceTag meaning |
|---|---|---|
| Bug fix or behaviour correction | PATCH | Something that was supposed to work already now does |
| Small UI correction | PATCH | No meaningful new capability |
| Security or reliability fix | PATCH | Existing behaviour made safer or correct |
| New user-facing capability | MINOR | A new feature, workflow, integration or substantial capability |
| Major compatible expansion | MINOR | For example a schedules and reminders subsystem |
| Breaking backup or data contract | MAJOR | Older clients or data no longer compatible without a migration |
| Breaking NFC record or protocol contract | MAJOR | An external contract intentionally made incompatible |
| Fundamental product-contract break | MAJOR | Documented behaviour intentionally broken |

Rules:

- A MINOR increment resets PATCH to 0. A MAJOR increment resets MINOR and PATCH to 0.
- Android's `versionCode` is independent: a monotonically increasing integer, +1 on every released APK whatever the `versionName` bump. It is never reset — a smaller code cannot install over a larger one without an uninstall.
- A release is the tag `servicetag-v<versionName>` on the exact green commit; the release workflow refuses to publish when the APK's `versionName` differs from the tag.
- A **forward-only** backup-format bump — where the new app reads every older archive and an older app safely refuses a newer one rather than dropping rows — is a **MINOR**. A change that makes the app unable to read data it previously could is a **MAJOR**.
- Released versions are never renamed or re-cut.

History:

Before the first public baseline, ServiceTag used temporary 2.x development release numbers during the product split and release-pipeline bring-up. Those release tags were retired before external distribution. The supported release history begins at 1.0.0. The retired development builds used `versionCode` 7 to 10, which is why 1.0.0 carries code 11 and installs over any of them in place; their engineering evidence stands in `docs/architecture/product-split-evidence.md` as pre-1.0 development-release evidence.

Supported release history:

| versionName | versionCode | what |
|---|---|---|
| 1.0.0 | 11 | first supported baseline: NFC asset identity, asset hierarchy, maintenance journal, typed measurements, event profiles, attachments, backup and restore, inspect and write NFC workflows, local-first persistence, a tested upgrade and signing path |
| 1.1.0 | 12 | local automation API: a loopback JSON API behind a per-session pairing code, alive only while the Developer API screen is open; a workstation MCP server at `tools/servicetag-mcp/`; an additive backup import that inserts what is new and never updates or deletes. No schema and no backup-format change. Contract: `docs/api/v1.md` |
| 1.2.0 | 13 | operational maintenance: maintenance schedules, maintenance groups, local reminders with quick actions, the scan completion sheet, reminder health and per-tag placement labels. Room schema **6**, backup format **6** — the new app reads every older archive and 1.1.x refuses a format-6 one with `BackupNewerFormat` rather than dropping rows, which is why this is a MINOR by the rule above. Contracts: `docs/api/v1.md`, `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md`. CI unit gate on the base commit: 1,180 tests in 132 suites, MCP 197 tests over 38 tools, bundle 158 tests |

Reserved next:

| what | versionName |
|---|---|
| an incompatible backup or protocol change | 2.0.0 |

No `versionCode` is reserved in advance. The 1.1.1 / code 13 reservation that stood here is struck:
1.2.0 took code 13, and reserving a code twice is how two releases come to claim one number.

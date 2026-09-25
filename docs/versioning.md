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
| 1.2.0 | 13 | operational maintenance: maintenance schedules, maintenance groups, local reminders with quick actions, the scan completion sheet, reminder health and per-tag placement labels. Room schema **6**, backup format **6** — the new app reads every older archive and 1.1.x refuses a format-6 one with `BackupNewerFormat` rather than dropping rows, which is why this is a MINOR by the rule above. Contracts: `docs/api/v1.md`, `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md`. unit gate 1,190 tests / 133 suites (`:core` 533, `:app` 597), MCP pytest 197 (38 tools), bundle pytest 158 |
| 1.2.1 | 14 | PATCH: `close_round` refuses to close a maintenance round before the current round's `due − lead` window opens, writing nothing, which closes the immediate-retry hole against the round `close_round` itself just opened; the device zone is now an explicit, required dependency at the `RecomputeSchedules` seam rather than a defaulted one (D-27's device-local-zone floor unchanged, no scheduling result changed). No schema or format change — Room schema **6**, backup format **6** unchanged. Contracts: `docs/api/v1.md` (409 `OCCURRENCE_NOT_YET_OPEN`), the 1.2 spec's §2.9 amendment note. Tooling carried by this tag: `tools/servicetag-schedules/` (the Stage-B schedule loader: plan first, apply only a clean plan; its own CI job). Unit gate 1,200 tests / 133 suites (`:core` 540, `:app` 600, nfc-core 50, nfc-android 10) from scratch at the release tip; MCP pytest 197 (38 tools), bundle pytest 158, schedules pytest 92. |
| 1.3.0 | 15 | **share intake:** Android `ACTION_SEND` intake of a URL, a document, an image or an external note link into an asset; a new `asset_reference` table and a **References** section on asset detail with Open, Edit, Remove and "Add link"; a reference surface on the loopback API and MCP. Room schema **7**, backup format **7** — the new app reads every older archive and 1.2.x refuses a format-7 one with `BackupNewerFormat` rather than dropping rows, which is why this is a MINOR by the rule above. Contracts: `docs/api/v1.md`, `docs/superpowers/specs/2026-09-23-servicetag-share-intake.md`. Unit gate 1,379 tests / 150 suites (`:core` 613, `:app` 706, nfc-core 50, nfc-android 10) from scratch at the release tip, zero skips; connected suite 167 on the emulator, zero skips (whole suite at the last wave's tip; the release fix round's touched class re-run at the release tip); MCP pytest 211 (41 tools), bundle pytest 158, schedules pytest 92. |
| 1.4.0 | 16 | **seasons, service policy, condition and health:** operating seasons (calendar and manual) — an asset is in use all year, between two calendar dates, or between a START and an END recorded by hand as immutable activation facts; maintenance service policy and a maintenance break — each schedule's work is done whenever it is due, when the season starts or before it starts, work held past the break reads DEFERRED, and a season or a break moves only the actionable date, never the raw due; operational condition and derived health — a dated OPERATIONAL / DEGRADED / DOWN history, and a NOMINAL / WARNING / CRITICAL health computed at read time from configured subjects and never stored; the pin floor no longer moves on a non-rule edit (#64). Room schema **8**, backup format **8** — the new app reads every older archive (formats 1–8, a format-7 one through the legacy season mapping) and 1.3.x refuses a format-8 one with `BackupNewerFormat` rather than dropping rows, which is why this is a MINOR by the rule above. `/v1` extended compatibly: every 1.3 request keeps its meaning on every row a 1.3 body can describe, and the deprecated season inputs (`seasonBehavior`, `seasonReentry`, `seasonReentryOffsetDays`, the asset's season pair) are still accepted through the legacy mapping. Released together: the app, the MCP server (`tools/servicetag-mcp/`, 55 tools) and the schedules loader (`tools/servicetag-schedules/`). Contracts: `docs/api/v1.md`, `docs/superpowers/specs/2026-09-24-servicetag-1.4-seasons-policy-condition-health.md`. Unit gate 1,843 tests / 218 suites (`:core` 817, `:app` 966, nfc-core 50, nfc-android 10) from scratch, zero skips; MCP pytest 315 (55 tools), bundle pytest 158, schedules pytest 106 — B15's gate run; the controller replaces these with the release tip's R1, R2 and R3 counts before tagging. |

Reserved next:

| what | versionName |
|---|---|
| an incompatible backup or protocol change | 2.0.0 |

No `versionCode` is reserved in advance. The 1.1.1 / code 13 reservation that stood here is struck:
1.2.0 took code 13, and reserving a code twice is how two releases come to claim one number.

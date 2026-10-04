# Security and privacy review

Status: design-phase document, 2026-09-14. Scope is the real Android attack surface of a
local-first app; nothing enterprise-grade is proposed.

## Threat model in one paragraph

The adversary is (a) anyone who can present an NFC tag or send an intent to the phone, (b) any
app on the same phone that can register a custom scheme or read exported components, (c) whoever
obtains a backup file or the phone's storage, and (d) the user's own mistakes. There is no
ServiceTag backend, no required account, and no multi-user sharing. The original design's network-facing
surface was the optional Todoist client.

> **Amended 2026-10-04 (#85, #16, #101).** The released app has two additional intentional outbound
> network surfaces. **Save as document** (#85) performs an explicit HTTPS download of a reference only when
> the owner asks ServiceTag to materialize it (or when the owner's paired workstation asks through the
> foreground Developer API). **Home Assistant season sync** (#16) performs authenticated reads from the
> Home Assistant address the owner configured. Neither changes the local-first source-of-truth model; the
> Developer API listener itself remains loopback-only. See the current user-facing network/permission inventory
> in `docs/local-first-and-permissions.md`.

## Surface-by-surface analysis

| Surface | Threat | Control | Phase |
|---|---|---|---|
| **NFC payload** | Malicious or malformed record makes the app crash, misroute, or execute something | Tags carry only an id. `NdefCodec` validates TNF, type, exact payload length (18 B), version byte, zero flags, hex charset for legacy; anything else is `Malformed`/`Foreign` and shown, never acted on. No string from a tag is ever used as a URI. Bounded parsing; extra records ignored. | 1 |
| **NFC dispatch** | Another app claims our external type | External-type filters are namespaced by our domain and an AAR pins the package; a competing claim only produces a chooser. On API 37 the dispatch activity is protected by `DISPATCH_NFC_MESSAGE`. | 1, 7 |
| **Tag writing** | Overwriting someone else's tag; writing while a screen you did not intend is open | Reader mode only on the Write screen; read-before-write; explicit confirmation when the tag holds a different noteNFC payload or foreign NDEF content; read-back verification; lock is opt-in with a warning. | 1 |
| **Deep links (`notenfc://`)** | Hijack by another app; injection of ids | Navigation-only: no URI performs a mutation (complete/snooze/postpone require an in-app tap). UUIDs validated by shape and existence. Custom-scheme hijack can at worst open the wrong app; nothing sensitive is in a URL. App Links would close this if a domain becomes available (D8 R-4). | 1 |
| **Outbound URI launching** | Stored link opens a dangerous scheme (`intent:`, `file:`, `content:`, `javascript:`) or crashes on a missing handler | `LinkLaunchPolicy` allowlist (`joplin`, `obsidian`, `logseq`, `http`, `https`) + one-time user confirmation for other schemes + hard block list; `ActivityNotFoundException` caught; `<queries>` for handler checks. The share sheet's text is parsed for a URI; raw text is never stored as a URI. | 1 |
| **Exported components** | Any app can start our activities/receivers with crafted extras | Only the launcher/deeplink host and the NFC dispatch activity are exported. **Amended 2026-09-23 (ServiceTag 1.3.0): three are exported now — the launcher/deep-link host `MainActivity`, `nfc.NfcDispatchActivity`, and the share target `share.ShareIntakeActivity`, which `ACTION_SEND` requires to be exported (spec ruling D-14, the amendment to #35's AC 3). The sentence above is kept for the record.** The three are asserted **by name as an exact set**, never as a count, by `ManifestContractTest`. Quick-action receiver and boot receivers are not exported and are addressed with explicit intents and `FLAG_IMMUTABLE` PendingIntents. No exported ContentProvider. Deep-link extras are ignored; only the URI is read. | 1, 3 |
| **Notification actions** | Spoofed broadcast completes a schedule | Actions carry a random per-notification nonce stored in-process/DataStore and checked by the receiver; receiver not exported. | 3 |
| **Todoist token** | Token theft from storage or backup; leakage in logs | `SecretStore`: AES-GCM key in Android Keystore (`setUserAuthenticationRequired` off — background sync must work), ciphertext in `noBackupFilesDir`; excluded by `dataExtractionRules`; never in exports; never logged; revoked on disconnect (token deleted locally; user told to rotate in Todoist). Token entry field is masked. | 5 |
| **Home Assistant token** (#16) | Token theft from storage or backup; leakage in logs, the API or the MCP; the bearer sent to the wrong host | `SecretStore` as designed above, built for #16: an AES-256-GCM key per connection in Android Keystore (`setUserAuthenticationRequired` off — background checks must work), ciphertext in `noBackupFilesDir/ha-secrets/`. **Not** excluded by `dataExtractionRules`, unlike the Todoist row: Auto Backup never copies `noBackupFilesDir` and the key never leaves Keystore, so the rule would add nothing (R16-5). A platform restore brings the non-secret Room rows back without the key, and every binding reads `NEEDS_TOKEN` and sends nothing until a token is entered here (R16-Q-F). Never a Room column; never in a ServiceTag backup, export, merge or Transfer Pack; never on the Developer API or the MCP; never logged. The bearer goes only to the configured origin: no redirect followed, no proxy, system trust anchors only; http only to an RFC 1918 IPv4 literal, and only after the phone confirms the captured home Wi-Fi. Revoked on disconnect: deleted locally; the owner revokes it in HA (the confirm dialog says so). Token entry field is masked, never pre-filled, with no reveal toggle. Home Assistant has no token that can only read, so the owner makes one for a dedicated user without administrator rights; ServiceTag sends one GET per check. | #16 |
| **Location permissions** (#16) | A location grant used for more than it was asked for; a request the owner did not choose | Declared in the manifest, which grants nothing. Precise and approximate location are requested together only when the owner chooses "Only on this home Wi-Fi", and serve one fact: the connected Wi-Fi's name, compared before each request with the name the owner captured. Background location is requested only when the owner turns "Background checks on this home network" On, after that grant (API 29+; from API 30 the owner grants it on the app's settings page). Nothing else is read — no position, no BSSID, no address. An approximate grant counts as none; "Any network" asks for nothing. Without precise location the home-network mode sends nothing and says why; without background location the connection falls back to checks while the app is open, with a visible status. `NEARBY_WIFI_DEVICES` and `ACCESS_LOCAL_NETWORK` are not requested. | #16 |
| **Todoist content** | Task content edited remotely to inject links or forge completions | Remote content is data: displayed as text, never linkified inside noteNFC, never interpreted as commands. Only `checked`/activity-log completion events, `due.date`, the recurrence string (verified, never adopted), and `is_deleted` are interpreted, per D3 §8. A remote due date that disagrees with the canonical computation is corrected, not trusted. | 5 |
| **Backups** | Contain serials, purchase prices, receipts, locations, Todoist task ids | Explicit warning at export; optional passphrase encryption (AES-GCM, Argon2id/PBKDF2) as a NEXT feature; tokens never included; user chooses the SAF destination. Automatic snapshots stay in app-private storage. | 1, 7 |
| **Attachments** | Persisted URI grants leak documents to us longer than intended; malicious files | Reference grants are listed and revocable in the Documents settings; managed files are opened through system viewers (`FileProvider` with temporary read grants), never rendered by an embedded WebView. **Amended 2026-09-23 (ServiceTag 1.3.0, spec §4.3): the two controls this row used to claim — sniffing the type on import, and a cap you could configure — are retired. Neither ever shipped.** The **declared** type is trusted (`AttachmentKinds.inferFrom` and `AttachmentLocator.extension` both read it and nothing sniffs), which is defensible because the bytes are never rendered, executed or interpreted and are never opened by an embedded WebView; the cap is the `MAX_ATTACHMENT_BYTES` constant, not a preference (#18 owns preferences). New in 1.3.0: a share's stream URI must be `content://` and must not name one of ServiceTag's own authorities, checked before the stream is opened (I-9). | 4 |
| **Accidental data deletion** | Wrong tap deletes an asset with years of history | Archive-first UX; typed confirmation for hard deletes; automatic pre-delete snapshot; `RESTRICT` on parents with children; undo window for event deletion (soft-hold 10 s before commit). | 1–3 |
| **Database integrity** | Partial writes, FK orphans, corruption | One transaction per use case; Room-enforced foreign keys with explicit `CASCADE`/`SET NULL`/`RESTRICT`; WAL journal; `PRAGMA integrity_check` and a "rebuild derived state" action on the Health screen; derived tables always recomputable. | 1+ |
| **Auto Backup** | Google account backup exposes data | Keep it (it is a survival aid) but exclude the secret file and cache; document that it is best-effort and signature-bound. **Amended 2026-10-03 (#16):** the one secret file lives in `noBackupFilesDir`, which Auto Backup never copies, so no exclusion rule was written for it; the Home Assistant connection and bindings are non-secret Room rows Auto Backup keeps, restored inert (`NEEDS_TOKEN`). | 1 |
| **Logging** | Sensitive values in logcat | No URIs, serials, or tokens in logs at INFO+; debug logging behind `BuildConfig.DEBUG`. | all |
| **Dependencies** | Supply-chain risk | Minimal set (AndroidX, kotlinx, OkHttp); version catalog pinned; no analytics SDKs. | 0 |

## Privacy statement to include in the app

- No account, no server, no telemetry.
- Data leaves the device when the user explicitly exports/shares data or enables a network-backed workflow.
  Historical examples include an exported backup, a cloud-backed attachment destination, or Todoist where configured.
- **Amended 2026-10-04 (#85, #16, #101):** **Save as document** sends an HTTPS request to the reference URL
  the owner chose in order to download that document; **Home Assistant season sync** sends the access token
  and linked entity ID only to the Home Assistant address the owner configured in order to read that entity's
  on/off state. These are optional, user-initiated/configured outbound flows; ordinary NFC identification,
  maintenance history, scheduling and reminders do not require a ServiceTag cloud service.
- NFC tags contain an opaque identifier only.

## Items deliberately not done

- Biometric app lock (nothing here is more sensitive than the phone's own lock screen; can be a
  NEXT setting).
- Encrypting the Room database at rest (SQLCipher adds size and complexity; app-private storage
  plus device encryption is the platform norm).
- Certificate pinning for Todoist (standard TLS is appropriate; pinning breaks on their rotations).

# Local-first design, privacy, and Android permissions

ServiceTag is intentionally useful on one Android phone with no account and no backend. The phone remains the source of truth for the released local product.

This document explains that boundary and the Android capabilities ServiceTag asks for. For the complete Home Assistant workflow, see [Home Assistant season sync](home-assistant-season-sync.md).

## Local-first design

The core rules are:

- NFC tags identify Assets; they do not contain the Asset database.
- Maintenance history remains local and auditable.
- Derived schedule state and health are recomputed from canonical facts.
- Files are ordinary managed files in storage controlled by the owner.
- Backups are explicit, logical, and portable.
- Automation uses the same application use cases as the UI.
- Local scheduling and reminders are canonical; an external reminder provider is not required.

ServiceTag is distinct from [NoteTag](https://github.com/GonzRon/NoteTag). ServiceTag tags identify physical Assets. NoteTag handles the separate tag-to-note/link use case.

## Network use

ServiceTag does not require routine cloud connectivity. The released app has two intentional outbound network workflows:

1. **Save as document** — an explicit HTTPS download of a reference when the owner asks ServiceTag to materialize it as a managed attachment.
2. **Home Assistant season sync** — authenticated reads from the Home Assistant address configured by the owner.

The Developer API is loopback-only, but Android still gates socket creation behind the INTERNET permission.

ServiceTag does not send the Home Assistant token, selected home Wi-Fi name, or connection address through its Developer API, MCP tools, ServiceTag backups, merges, or Transfer Packs.

## Home Assistant credential and network boundary

The Home Assistant access token is kept on the phone using a Keystore-backed secret store and is excluded from ServiceTag backup/export/merge/Transfer Pack data.

For network selection:

- **Any network** is available for an HTTPS endpoint the owner has deliberately made reachable.
- **Only on this home Wi-Fi** verifies the captured Wi-Fi name before sending the bearer token and is the mode that permits private HTTP according to the Home Assistant integration's endpoint rules.
- Background checks on the selected home Wi-Fi are optional. If the required Android background permission is refused or revoked, ServiceTag falls back to foreground/resume checks and **Sync now** rather than disabling the integration.

See [Home Assistant season sync](home-assistant-season-sync.md) for the complete contract.

## Android permissions and grants

### Notifications — `POST_NOTIFICATIONS`

Requested at the point of need rather than at first launch. Used for local maintenance/reminder notifications.

### NFC

Declared for reading and writing ServiceTag NFC records. NFC does not require a runtime permission dialog.

### `RECEIVE_BOOT_COMPLETED`

Declared so ServiceTag can re-arm local reminder scheduling after a device reboot.

### `INTERNET`

Install-time permission used for:

- the loopback Developer API;
- explicit **Save as document** HTTPS downloads;
- Home Assistant Test connection and season-state reads.

There is no ordinary Android runtime prompt for INTERNET. Hardened Android variants may expose additional user controls; affected ServiceTag screens explain when network access is unavailable.

### Precise/approximate location — `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`

Requested only for the Home Assistant **Only on this home Wi-Fi** option.

Android exposes the connected Wi-Fi network name only under the applicable location permission contract. ServiceTag uses that network name to decide whether it may send the Home Assistant token. It does not use the permission to collect the phone's geographic location.

An approximate-only grant is insufficient for this network-name check.

### Background location — `ACCESS_BACKGROUND_LOCATION`

Requested only when the owner explicitly turns **Background checks on this home network** on.

If refused or later withdrawn, ServiceTag pauses those background checks and falls back to foreground/resume checking and **Sync now**.

### `ACCESS_WIFI_STATE`

Install-time permission used with the selected-home-Wi-Fi Home Assistant mode to read the connected Wi-Fi identity exposed by Android.

### `ACCESS_NETWORK_STATE`

Install-time permission used by WorkManager/network-aware flows and the Home Assistant network eligibility check.

### Attachments folder

The attachments folder is not a broad storage permission. ServiceTag uses the Android folder picker and the resulting scoped grant for the folder the owner selected.

### Shared files

A file shared into ServiceTag arrives with Android's temporary URI read grant. This is not a general storage permission.

### Permissions merged from Android libraries

Depending on the built dependency graph, the manifest may include library-provided permissions such as:

- `FOREGROUND_SERVICE`
- `WAKE_LOCK`
- `ACCESS_NETWORK_STATE`
- AndroidX's signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`

They are not requested as ordinary runtime permissions by ServiceTag.

### `ACCESS_LOCAL_NETWORK`

The current Android target/platform contract may surface `ACCESS_LOCAL_NETWORK` as an install-time/local-network capability associated with INTERNET on newer Android versions. ServiceTag does not present a separate in-app runtime request for it.

## Data portability and secrets

ServiceTag backups and Transfer Packs are designed to carry canonical maintenance/equipment data, not device-local secrets.

Device-local integration credentials such as the Home Assistant token are intentionally excluded. A restored installation may therefore require the owner to re-enter a token or re-establish an integration before network work resumes.

## Security-sensitive implementation references

- [Home Assistant season sync](home-assistant-season-sync.md)
- [Developer API v1](api/v1.md)
- [Design/security documentation](design/)
- [Release proofs](release-proofs.md)

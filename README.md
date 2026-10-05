# ServiceTag

**SCAN. MAINTAIN. REMEMBER.**

ServiceTag turns physical equipment into a durable digital maintenance record.

If you’ve ever tried to manage equipment maintenance with ordinary phone reminders or a general-purpose to-do app, you already know how quickly it becomes awkward. Those tools can remind you that something is due, but they don’t really understand the equipment, its service history, what was done last time, what parts or supplies it uses, or when the next service should actually be calculated from. ServiceTag is built specifically for that problem. Instead of maintaining a separate collection of reminders and checklists, each piece of equipment keeps its own maintenance schedule, history, documents, measurements, and service information together, and ServiceTag works out what is due and when. You spend less time managing reminders and more time simply doing the maintenance when it needs to be done.

Put a small NFC tag on a generator, furnace, hot tub, mower, snowblower, water system, UPS, pump, appliance, vehicle, tool, or other serviceable asset. Tap the tag with your Android phone and ServiceTag opens the record for that exact piece of equipment: what it is, where it is, its make, model and serial number, what has been done to it, the measurements that were taken, the documents and photos that belong with it, its current condition and health, and what maintenance is coming next.

The tag itself carries only a stable ServiceTag identity. The useful information stays in ServiceTag's local database, so the equipment record can grow over years without trying to squeeze mutable data onto the NFC tag. Backups preserve those identities, which means the same physical tags can keep working after a phone replacement, reinstall, or restore.

ServiceTag is **local-first**. It does not require an account or a cloud service to identify equipment, keep maintenance history, calculate due work, or deliver local reminders. The phone remains the source of truth. Files can live in storage you control, backups are portable, and a deliberately narrow local Developer API and MCP bridge make larger maintenance inventories automatable without turning the app into a hosted service.

The current **ServiceTag 1.8.0** release includes maintenance scheduling and reminders, operating seasons and maintenance policy, condition and derived health, lending and ownership handoff, SupplyItems, fitted Installed Components and replacement history, documents/references on Assets and their supporting records, and optional Home Assistant operating-season synchronization, in English and nine more languages.

## Learn more

ServiceTag's documentation uses **progressive disclosure**: start here, then open only the level of detail you need.

- **[New to NFC? Start here](docs/nfc-tags.md)** — what NFC tags are, how your phone already uses NFC, and which tag style to choose for indoor, outdoor, or metal equipment.
- **[NFC privacy: what is actually on the tag?](docs/nfc-privacy.md)** — why the tag needs no battery or internet connection, how the phone powers it, and what another person can learn by scanning it.
- **[What ServiceTag can do today](docs/capabilities.md)** — the released feature set and product boundaries.
- **[Local-first design, privacy, and Android permissions](docs/local-first-and-permissions.md)** — where data lives, when ServiceTag uses the network, and why permissions exist.
- **[Home Assistant season sync](docs/home-assistant-season-sync.md)** — setup, behavior, network choices, and security boundaries.
- **[Developer API v1](docs/api/v1.md)** and **[MCP tools](tools/servicetag-mcp/README.md)** — local workstation automation.
- **[Building and testing](docs/building-and-testing.md)** — clone, build, JVM gates, and emulator tests.
- **[Documentation index](docs/README.md)** — architecture, design, specifications, releases, and deeper references.

## Project status

Latest released baseline: **[ServiceTag 1.8.0](https://github.com/GonzRon/ServiceTag/releases/tag/servicetag-v1.8.0)**  
Release details: **[1.8.0 release notes](docs/releases/1.8.0.md)**; the previous release: [1.7.0 release notes](docs/releases/1.7.0.md)  
Current roadmap and release sequencing: **[issue #104](https://github.com/GonzRon/ServiceTag/issues/104)**

ServiceTag is distinct from [NoteTag](https://github.com/GonzRon/NoteTag): ServiceTag tags identify physical equipment and open its maintenance record; NoteTag handles the separate tag-to-note/link use case.


## License

ServiceTag is licensed under the **GNU Affero General Public License v3.0 (AGPLv3)**. See [LICENSE](LICENSE).

AGPLv3 permits use, modification, distribution, and commercial use subject to its copyleft requirements, including source-availability obligations for covered modified versions used over a network. Separate proprietary/commercial licensing may be offered by the copyright holder.

Third-party dependencies and the separately maintained `nfc-tag-core` submodule retain their own licensing status and terms.

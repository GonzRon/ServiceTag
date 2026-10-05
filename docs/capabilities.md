# Current ServiceTag capabilities

The current released baseline is **ServiceTag 1.7.0**. See the [1.7.0 release notes](releases/1.7.0.md) for the exact release delta.

ServiceTag is an NFC-first, local-first equipment maintenance application. The NFC tag is the doorway; the Asset record and its long-lived service history are the product.

## NFC identity and Asset records

ServiceTag can:

- create Assets and bind, write, verify, rebind, replace, and revoke ServiceTag NFC tags;
- scan a physical tag directly into the correct Asset;
- keep make, model, serial number, category, location, purchase and warranty information;
- model parent systems and child Assets;
- archive or retire Assets without erasing their history;
- retain stable identities through backup/restore so the same physical tags continue to resolve after phone replacement or reinstall.

The tag itself stores a stable ServiceTag identity, not the mutable Asset database.

## Service history and measurements

Each Asset has an auditable service record that can include:

- maintenance, repair, replacement, and note events;
- typed measurements and meter readings;
- materials used;
- configurable quick-action/event profiles;
- photos, documents, receipts, manuals, and references.

History is kept as facts. Derived schedule and health state are recomputed rather than stored as mutable historical truth.

## Maintenance schedules, seasons, condition, and health

ServiceTag supports:

- recurring maintenance by elapsed time;
- meter/usage-based maintenance;
- time-or-meter rules;
- maintenance groups;
- due and overdue state;
- snooze and postpone;
- local reminders and notification actions;
- reminder-health diagnostics and repair flows.

Operating season is distinct from maintenance policy. Assets can be year-round, calendar-seasonal, or manually started and ended. Maintenance can be configured to occur before a season, when it starts, while equipment is in service, or whenever the canonical schedule says it is due. Maintenance breaks can suppress routine work without fabricating completion history.

Condition records whether an Asset is **operational, degraded, or down**. Health is a separate derived view based on configured subjects such as age and overdue maintenance.

Details: the [1.2 operational maintenance specification](superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md) and the [1.4 seasons, policy, condition and health specification](superpowers/specs/2026-09-24-servicetag-1.4-seasons-policy-condition-health.md).

### Home Assistant season sync

An Asset can optionally follow one Home Assistant on/off helper for its operating season. Home Assistant owns the automation decision; ServiceTag reads the resulting state and applies ordinary Start season / End season operations when the effective season changes.

The phone supports:

- **Follow Home Assistant**
- **Force in season**
- **Force out of season**
- **Sync now**
- polling every 12 hours, daily, weekly, or monthly;
- HTTPS on any network, or a selected home Wi-Fi mode with optional background checks.

See [Home Assistant season sync](home-assistant-season-sync.md) for setup and security details.

## Supply items

ServiceTag 1.7 added the canonical **SupplyItem** catalog for products and consumables used by equipment, such as filters, batteries, belts, cartridges, chemicals, fluids, and replacement modules.

A SupplyItem can keep:

- name and category;
- manufacturer, model, SKU/part number;
- preferred unit;
- notes;
- generic structured specifications.

Assets can declare which SupplyItems they use and in what role. Quick-action and journal material lines can explicitly link to a SupplyItem while preserving their readable historical snapshot.

SupplyItem identity is deliberately separate from installed physical state.

## Installed components

An Asset can record serviceable physical structures fitted inside it as **Installed Components**.

An Installed Component can have:

- a name and fitted position/parent;
- install date;
- serial or lot;
- notes;
- an optional SupplyItem identity;
- composition made from SupplyItems with quantities;
- nested Installed Components.

Replace or Remove keeps the old fitted instance as history. Installed Components are distinct from child Assets: they do not receive their own NFC identity, journal, or independent Asset lifecycle merely because they are fitted equipment.

Details: SupplyItems and Installed Components shipped in [ServiceTag 1.7.0](releases/1.7.0.md).

## Documents, references, and share intake

Files and web references can belong to:

- an Asset;
- a SupplyItem;
- an Installed Component;
- and, for files, a journal entry where supported.

ServiceTag can store photos, manuals, receipts, service documents, and other attachments; web/reference links; and document roles such as purchase invoice/receipt, user manual, and service manual.

Android Share intake can attach a document, image, or URL to the appropriate owner. Asset share intake uses the same searchable Asset-selection behavior as the app.

SupplyItem documents are stored once and can be reached wherever that SupplyItem is used.

Details: the [share intake specification](superpowers/specs/2026-09-23-servicetag-share-intake.md).

### Save as document

A supported HTTPS reference can be materialized into a managed attachment. ServiceTag:

1. downloads only when explicitly asked;
2. validates the downloaded bytes as a supported document/image/text format;
3. saves the result through the ordinary attachment store;
4. preserves structured source provenance.

A web/login page is not silently saved as a document.

## Lending, replacement, and ownership handoff

### Lending

An Asset can be lent to a person or organization selected through Android Contacts without a contacts permission. An optional due-back reminder can be set. Returning the Asset closes the loan while retaining its history.

### Replace Asset

ServiceTag can replace an Asset with a distinct successor. The predecessor is retired with its history intact, carry-forward items are explicitly reviewed, selected NFC tags may move, and both records retain their predecessor/successor relationship.

### Transfer Packs

Selected Assets can be exported as a portable Transfer Pack containing their applicable records, documents, and NFC identities. Another ServiceTag installation can import that pack additively. The sender can then explicitly mark the Assets transferred out.

Transfer is a deliberate ownership/maintenance-responsibility handoff, not live multi-user synchronization.

Details: lending, Replace Asset, Transfer Packs and Save as document shipped in [ServiceTag 1.5.0](releases/1.5.0.md).

## Backup, restore, and merge foundations

ServiceTag exports versioned logical backups with stable IDs and attachment artifacts. Backups can restore onto another installation, and the codebase includes conflict-safe additive merge machinery rather than silently overwriting existing rows.

Newer backup formats fail closed on older clients rather than dropping unknown data.

## Local automation

ServiceTag includes a deliberately narrow automation surface:

- a loopback-only Developer API, available only while its screen is open and paired;
- a workstation MCP server under [`tools/servicetag-mcp/`](../tools/servicetag-mcp/README.md);
- schedule-loader tooling for controlled bulk schedule workflows.

Automation calls the same application use cases used by the UI rather than maintaining a second business-logic path.

The API can work with Assets, maintenance, documents/references, SupplyItems, Installed Components, replacement workflows, and other released surfaces. Home Assistant connection secrets and writes remain phone-controlled; season-sync state has a read-only API/MCP view.

See [Developer API v1](api/v1.md).

## What ServiceTag deliberately is not

The released app is not:

- a required cloud service or account;
- an ERP, warehouse, accounting, or purchasing system;
- a live multi-user fleet synchronization service;
- a generic IoT/telemetry platform;
- a predictive-maintenance or autonomous AI system.

Those boundaries keep the core product focused on durable equipment identity and trustworthy maintenance memory.

## Related reading

- [Local-first design, privacy, and permissions](local-first-and-permissions.md)
- [Home Assistant season sync](home-assistant-season-sync.md)
- [Developer API v1](api/v1.md)
- [1.7.0 release notes](releases/1.7.0.md)
- [Current roadmap — #104](https://github.com/GonzRon/ServiceTag/issues/104)

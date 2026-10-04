# ServiceTag documentation

This directory is the documentation entry point for readers who need more than the short project introduction in the repository README.

ServiceTag documentation follows **progressive disclosure**: start with the product overview, move into the topic you need, and use the deeper contracts/specifications only when implementation-level detail matters.

## Product

- [Current capabilities](capabilities.md) — what the released app can do today.
- [Local-first design, privacy, and Android permissions](local-first-and-permissions.md) — where data lives, when ServiceTag uses the network, and why Android permissions exist.
- [Home Assistant season sync](home-assistant-season-sync.md) — setup, behavior, network choices, security boundaries, and a fictional example.
- [Release notes](releases/) — release-by-release user-visible changes and upgrade notes.
- [Current roadmap — issue #104](https://github.com/GonzRon/ServiceTag/issues/104) — active release assignments and backlog sequencing.

## Developers and automation

- [Building and testing](building-and-testing.md) — clone, build, JVM gates, and emulator tests.
- [Developer API v1](api/v1.md) — the loopback HTTP contract exposed while the Developer API screen is open.
- [ServiceTag MCP tools](../tools/servicetag-mcp/README.md) — workstation-side MCP bridge built on the Developer API.
- [Versioning and release policy](versioning.md) — ServiceTag's product SemVer rules and supported release history.
- [Release proofs](release-proofs.md) — recorded release/gate evidence.

## Architecture and design record

- [Architecture](architecture/) — architecture evidence and supporting material.
- [Design documentation](design/) — product/domain/security design documents.
- [Specifications and plans](superpowers/) — detailed implementation specifications, plans, and review artifacts.

## Where to start

If you are evaluating ServiceTag, read [Current capabilities](capabilities.md).

If you are installing or reviewing privacy/network behavior, read [Local-first design, privacy, and Android permissions](local-first-and-permissions.md).

If you are integrating a workstation or automation, start with [Developer API v1](api/v1.md) and the [MCP tools](../tools/servicetag-mcp/README.md).

If you are contributing code, start with [Building and testing](building-and-testing.md), then follow the relevant design or API contract for the area you are changing.

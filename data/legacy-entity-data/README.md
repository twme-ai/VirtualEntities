# Legacy entity metadata provenance

VirtualEntities supports metadata before the first kennytv entity-data snapshot by bundling reviewed protocol facts for Minecraft 1.9.4 through 1.14.1. The 1.9.4 through 1.12.2 snapshots are immutable inputs to `tools/merge-entity-data.mjs`; the normal kennytv sync does not replace them.

The reviewed facts are the metadata owner, inherited index, wire serializer, and semantic field name. Textual Java default expressions are intentionally excluded because VirtualEntities does not parse metadata defaults.

## Evidence

- Mojang version metadata and server JARs are the primary wire-behavior evidence. Every reviewed release is pinned by the official server SHA-1 in `sources.json`.
- Version-pinned Spigot BuildData mappings provide stable class and member identities for obfuscated legacy server JARs. The exact BuildData commit comes from `https://hub.spigotmc.org/versions/<version>.json`.
- ViaVersion and ViaBackwards entity-data rewriters identify protocol transition points, including the Arrow owner field added in 1.13.1 and the fields added or changed in 1.14.
- ViaVersion's 1.9 entity-data table identifies the Wolf-specific health value as `WOLF_HEALTH`. The canonical merge keeps that name distinct from inherited `Living Entity.HEALTH`; otherwise flattening by field name would silently replace one of the two protocol entries.
- PacketEntities historical snapshots were used as an independent cross-check for field names, indices, serializers, and inheritance. No PacketEntities source code is included.
- The generated 1.13, 1.13.1, and 1.13.2 snapshots reverse the reviewed 1.14 changes from kennytv's 1.14.4 snapshot. The 1.14 and 1.14.1 snapshots preserve the Villager index transition that occurred before 1.14.4. These differences are encoded explicitly in `tools/merge-entity-data.mjs`.

Each supported PacketEvents entity type is resolved against every legacy protocol boundary in the test suite. Paper and Mineflayer black-box tests additionally exercise 1.9.4, 1.12.2, and 1.13.2 using the same Java 17 core artifact.

## Maintenance policy

These released Minecraft versions cannot gain new protocol fields. Changes to this directory therefore require new primary-source evidence and review; scheduled kennytv updates only affect its upstream version range. Server JARs, mappings, and decompiled sources are verification inputs and are never committed or packaged.

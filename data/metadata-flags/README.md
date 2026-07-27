# Semantic metadata flag provenance

`semantic-flags.json` is a reviewed manifest for individual bits packed into
Minecraft metadata byte fields. It is intentionally separate from
`src/main/resources/entity-data`, which only describes field names, indexes,
and wire serializers.

The manifest covers the requested `1.9.4` through `25.2` audit label. Mojang's
official version manifest currently has no release identifier named `25.2`;
the latest 2025 release identifier is `1.21.11`. The repository also bundles
later numeric snapshots (`26.1`, `26.2`) and the non-release data snapshot
`26w14a`; the verifier checks those snapshots as well.

## Evidence policy

Mojang's official server artifacts are the primary evidence. For releases with
official server mappings, the mapped `Entity` and `Display.TextDisplay`
constants are checked directly. Older artifacts without Mojang mappings are
reviewed using the pinned Mojang server hashes and legacy mapping evidence in
`data/legacy-entity-data`, then cross-checked against independent protocol
implementations.

The independent checks are Minestom's `MetadataDef`, PacketEntities'
`EntitySharedFlagsView`, EntityLib's metadata wrappers, Minecraft Wiki's
protocol table, and ViaVersion's explicit 1.12.2-to-1.13 transition handling.
They are evidence and review aids, not inputs to the generated metadata-key
source.

## Deliberate exclusions

The shared-flags `0x10` bit is not named here: ViaVersion documents it as
previously unused and newly assigned to swimming in 1.13. A single global
descriptor would therefore be misleading for the full 1.9.4+ range. Reserved
and otherwise unreviewed bits are also not exposed.

Changing a mask or adding a flag requires updating this manifest, its source
evidence, and the all-snapshot verifier. No Mojang server JAR, mapping, or
third-party source code is packaged in the library.

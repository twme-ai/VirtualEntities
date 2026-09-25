# Locally reviewed entity-data snapshots

`src/main/resources/entity-data` is normally produced by `tools/sync-entity-data.sh` from
[kennytv.eu/entity-data](https://kennytv.eu/entity-data/). When upstream has not published a
snapshot for an already released Minecraft version, the snapshot for that version is derived from
the official Mojang-mapped server artifact and reviewed here instead. The sync command preserves
such snapshots and re-installs them after each upstream sync, and the upstream document replaces
the local copy as soon as upstream publishes it.

## 26.3

Upstream has no `26.3` document (`https://kennytv.eu/entity-data/26.3.json` returns 404 and its
`versions.json` ends at `26.2`), so `26.3.json` was derived locally.

### Artifacts

| Artifact | sha256 |
| --- | --- |
| `https://repo.papermc.io/repository/maven-public/io/papermc/paper/dev-bundle/26.3.build.41-alpha/dev-bundle-26.3.build.41-alpha.zip` | `173195c7f34dbd368e1170ec0245b7675abbed71b37e46f0fd90c2dbb3bb53db` |
| `https://repo.papermc.io/repository/maven-public/io/papermc/paper/dev-bundle/26.2.build.129-stable/dev-bundle-26.2.build.129-stable.zip` | `fd0d111654d7ae5800b88a944f7cfb2e5936e30f816d0d987fb3f5925640a238` |
| `io.papermc:mache:26.3+build.1` | `f99b1af52652452f65fc1b6c9fb3b038987c2e9170fe1f14a71f84e46b13b3a3` |
| Mojang `server.jar` for `26.3` (`piston-data.mojang.com/.../33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar`) | `d052f14d7a173734fba553711e5b570162e2f2a313267ee31a21b975a679be64` |
| Mojang `server.jar` for `26.2` (`piston-data.mojang.com/.../823e2250d24b3ddac457a60c92a6a941943fcd6a/server.jar`) | `cdacdfb25898de5e4b4b0e5ddcc2722f77067e46605709c2d886c000ebb63ec5` |
| Mojang-mapped `paper-26.3.jar` produced by the dev bundle's paperclip | `0df1f383d3c21b669ed24e3c099aaabf51684f056420a2a972e633436cb2f537` |
| Mojang-mapped `paper-26.2.jar` produced by the dev bundle's paperclip | `09622eeea2dc90b37a5dc8c94d275da910c9a46a4bf6570d8535189334205bff` |

The two Mojang-mapped jars have exactly the hashes recorded in each dev bundle's
`META-INF/versions.list`, and the paperclip build downloads the official Mojang `server.jar`
identified above, so the extraction input is byte-identifiable against Mojang's release.

Modern Mojang version manifests publish only `client` and `server` downloads, with no
`server_mappings` document, which is why the derivation goes through Paper's Mojang-mapped
development artifact.

### Method

Minecraft assigns a synced-data accessor id from `SynchedEntityData.defineId`, which delegates to
`ClassTreeIdRegistry.define`: the id is the previous id cached for the class plus one, or `0` when
neither the class nor an ancestor has claimed one. Because `javac` emits static initializers in
source order, a class's accessor ids are exactly the ordinals of its `defineId` calls inside that
class's `<clinit>`, seeded by its parent's last id. The derivation replays that rule over the
class files of the Mojang-mapped jar and reads each accessor's generic signature for its
`dataType`, plus the `define(accessor, value)` argument in `defineSynchedData` for its
`defaultValue`.

The pipeline was validated three ways before it was used for `26.3`:

1. Extraction from the official `26.2` artifact reproduces the reviewed `26.2.json` with zero
   discrepancies over all 105 accessor-declaring classes and 187 entities (entity set, superclass
   links, indexes, dataTypes, serializer types).
2. A structural comparison of the `26.2` artifact and `26.2.json` reports no problems.
3. Composing the structural result with `26.2.json` as the base reproduces `26.2.json` byte for
   byte, and regenerating `26.3.json` is idempotent.

### Result

`26.3.json` differs from `26.2.json` in exactly two places; all 185 shared classes in the
`EntityType` registry closure have identical layouts and superclass links.

- The `EnderMan` class was renamed to `Enderman` (layout unchanged). The key rename is cosmetic
  for lookups: `EntityMetadataRegistry.normalize()` removes non-letter characters and lowercases,
  so both spellings resolve `minecraft:enderman`.
- The new `Cushion` entity (`minecraft:cushion`) declares one synced field, `COLOR`, at index 8
  with the new `DyeColor` serializer and the bytecode-derived default `DyeColor.WHITE`. Index 8 is
  the first id available after `Entity`'s eight fields (0-7), because its parent
  `BlockAttachedEntity` declares none.
- `minecraft:poplar_boat` and `minecraft:poplar_chest_boat` were added to the `EntityType`
  registry but retarget the existing `Boat` and `ChestBoat` classes, so no metadata entry is
  needed. `AllEntityMetadataSchemaTest` resolves every registered PacketEvents entity type for
  every supported server version through `26.3`, which covers them.

### Remaining uncertainty

- The literal key spellings `Cushion` and `Enderman` are inferred from the class names, because
  upstream has no `26.3` document to compare against. The same derivation reproduces all 187
  pre-existing names exactly, and lookups are normalization-based, so a later upstream snapshot
  would at most change a display-name string.
- `defaultValue` is documentation only and is never parsed by the library; unchanged fields keep
  the reviewed `26.2` text.

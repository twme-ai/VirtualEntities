# VirtualEntities

[![CI](https://github.com/twme-ai/VirtualEntities/actions/workflows/ci.yml/badge.svg)](https://github.com/twme-ai/VirtualEntities/actions/workflows/ci.yml)
[![Legacy E2E](https://github.com/twme-ai/VirtualEntities/actions/workflows/legacy-e2e.yml/badge.svg)](https://github.com/twme-ai/VirtualEntities/actions/workflows/legacy-e2e.yml)
[![JitPack](https://jitpack.io/v/twme-ai/VirtualEntities.svg)](https://jitpack.io/#twme-ai/VirtualEntities)
[![License](https://img.shields.io/github/license/twme-ai/VirtualEntities)](LICENSE)

VirtualEntities is a platform-independent Java library for creating and controlling client-side Minecraft entities with [PacketEvents](https://github.com/retrooper/packetevents). It provides entity identity and lookup, protocol-aware viewer lifecycle management, relative, absolute, and externally synchronized movement state, readable generated metadata, atomic metadata flags and multi-entity packet updates, equipment, attributes, passengers, virtual player profiles, audience tracking, and validated inbound interactions.

Metadata indexes are resolved from reviewed legacy snapshots for Minecraft 1.9.4 through 1.14.1 and data published at [kennytv.eu/entity-data](https://kennytv.eu/entity-data/) for newer releases. The merged dataset is bundled into the artifact, so entity operations never make network requests. A scheduled GitHub Actions workflow checks the upstream data daily, validates every downloaded document, and opens a reviewable update pull request when it changes.

## Requirements

- Java 17 or newer
- PacketEvents 2.13.0 installed by the server/proxy or supplied by your plugin
- Minecraft server 1.9.4 or newer

The entity lifecycle API itself is not tied to a Bukkit, Paper, or Velocity API. It works anywhere PacketEvents exposes a `User`.

### Version support

Java and Minecraft compatibility are independent. VirtualEntities is compiled for Java 17 and will not be downgraded to an older Java bytecode level. A legacy Minecraft server must therefore run on a Java 17 or newer JVM, even when that server release originally defaulted to Java 8.

| Range | Status | Evidence |
|---|---|---|
| Minecraft 1.9.4-1.13.2 | Supported on Java 17 | Reviewed metadata snapshots, protocol-boundary tests, and Paper + Mineflayer E2E on 1.9.4, 1.12.2, and 1.13.2 |
| Minecraft 1.14-current | Supported on Java 17+ | Reviewed 1.14 patch transitions, kennytv entity-data, protocol-boundary tests, and the current Paper + Mineflayer E2E |
| Minecraft 1.8.8 and older | Not supported | Requires a separate boolean-as-byte metadata codec and pre-1.9 single-passenger attach semantics |

The legacy compatibility layer selects the historical living-entity, player, painting, lightning, and experience-orb spawn packets and preserves the pre-1.15 embedded metadata layout. It also converts the logical `Optional<Component>` custom-name API to the pre-1.13 string serializer while retaining the logical value returned by `VirtualMetadata#get`.

`VirtualEntity#supports(viewer)` checks whether PacketEvents assigns the entity type an ID in that viewer's protocol. It is not, by itself, a promise that a modern server plus ViaVersion can encode all retained state for an older client. The 1.9.4+ support range above is verified against servers running the corresponding protocol; mixed server/client protocol translation requires a separate per-viewer schema layer.

## Installation

Releases are available from JitPack:

```kotlin
repositories {
    maven("https://jitpack.io")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    implementation("com.github.twme-ai:VirtualEntities:VERSION")
    compileOnly("com.github.retrooper:packetevents-api:2.13.0")
}
```

PacketEvents is intentionally a `compileOnly` dependency. Do not relocate or bundle a second PacketEvents copy when your platform already provides it.

## Usage

Create one manager for your plugin, then build entities from PacketEvents entity types:

```java
VirtualEntityManager entities = VirtualEntities.create();

VirtualEntity pig = entities.entity(EntityTypes.PIG)
        .metadata()
        .build();

pig.metadata()
        .set(EntityMetadataKeys.CUSTOM_NAME_VISIBLE, true)
        .set(
                EntityMetadataKeys.CUSTOM_NAME,
                Optional.of(Component.text("Market guide"))
        );

pig.addViewer(packetEventsUser)
        .spawn(new Location(10.5, 64, -3.5, 90, 0));

pig.teleport(new Location(12, 64, -3, 90, 0));
pig.updateLocation(new Location(12.5, 64, -3, 90, 0), true);
pig.rotateHead(45);
pig.velocity(new Vector3d(0, 0.25, 0));
pig.setEquipment(
        EquipmentSlot.HELMET,
        ItemStack.builder().type(ItemTypes.DIAMOND_HELMET).amount(1).build()
);
pig.setAttribute(Attributes.MAX_HEALTH, 40.0);

VirtualEntity passenger = entities.entity(EntityTypes.CHICKEN).build()
        .addViewer(packetEventsUser)
        .spawn(new Location(12, 64, -3, 90, 0));
pig.addPassenger(passenger);

pig.metadata().set(GeneratedEntityMetadataKeys.Pig.BOOST_TIME, 40);
pig.syncMetadata();

pig.removeViewer(packetEventsUser);
pig.remove();
```

Generated keys are grouped by their declaring entity-data class and inherit parent keys, so `GeneratedEntityMetadataKeys.Pig` exposes both pig fields and shared entity fields. Use `MetadataKey.of` for custom fields.

`VirtualMetadata#get` and `contains` inspect only values explicitly assigned with `set`. They resolve keys by field name, remain type-safe for fixed and generated versioned keys, and never parse the textual defaults from entity-data. Removing a key makes it absent again:

```java
Optional<Integer> boostTime = pig.metadata().get(GeneratedEntityMetadataKeys.Pig.BOOST_TIME);
boolean explicitlySet = pig.metadata().contains(GeneratedEntityMetadataKeys.Pig.BOOST_TIME);
pig.metadata().remove(GeneratedEntityMetadataKeys.Pig.BOOST_TIME);
```

Byte-backed metadata flags can be changed atomically without overwriting neighboring bits. An unassigned field is read as zero; explicitly disabling a flag stores zero. A zero mask or non-byte key is rejected:

```java
pig.metadata().setFlag(EntityMetadataKeys.SHARED_FLAGS, (byte) 0x40, true);
boolean glowing = pig.metadata().isFlagSet(EntityMetadataKeys.SHARED_FLAGS, (byte) 0x40);

textDisplay.metadata().setFlag(
        GeneratedEntityMetadataKeys.TextDisplay.STYLE_FLAGS,
        (byte) 0x02,
        true
);
```

The default `metadata()` builder resolves both the current PacketEvents server version and the entity-data name. When kennytv has no exact release document, VirtualEntities selects the newest bundled numeric snapshot at or before that release. PacketEvents entity aliases and parent types are resolved automatically. The explicit `metadata(version, entityDataName)` overload remains available for custom mappings.

Field names are resolved through the selected entity's complete inheritance chain. An unsupported version, entity name, or field fails immediately instead of sending a packet with a guessed index.

Equipment, attributes, and passenger lists are retained as entity state. Changes are sent immediately while spawned and replayed to late viewers or after a despawn/spawn cycle. Passenger entities must belong to the same manager; removing either side safely detaches the relationship.

Use `setLocationSnapshot` for an already spawned entity whose visible movement is driven by an external vehicle or packet source. It defensively replaces only the retained spawn location, sends no movement packet, and leaves `onGround` unchanged. Current viewers may temporarily differ from that snapshot; viewers added afterward spawn at the new position:

```java
nameTag.setLocationSnapshot(vehiclePosition);
nameTag.addViewer(packetEventsUser);
```

### Atomic multi-entity updates

Use `VirtualEntityManager#bundle` when several visible entity changes must be applied by each client as one update. Packets are collected independently for each viewer, retain operation order, and include only entities visible to that viewer:

```java
Vector3f previous = child.metadata()
        .get(GeneratedEntityMetadataKeys.Display.TRANSLATION)
        .orElseThrow();

entities.bundle(() -> {
    child.metadata().set(
            GeneratedEntityMetadataKeys.Display.TRANSLATION,
            new Vector3f(previous.getX() - deltaX, previous.getY() - deltaY, previous.getZ() - deltaZ)
    );
    child.syncMetadata();
    rootAnchor.teleport(newOrigin);
});
```

Each affected 1.19.4 or newer viewer receives exactly one opening and closing bundle delimiter. Older clients receive the same packets unbundled and in order. Nested calls on the same thread join the outer scope; operations on other threads are not captured. Entity state changes immediately, so viewers added later receive the final snapshot.

Bundles do not roll back state. If the callback throws, packets queued before the failure are flushed and the original exception is rethrown. Transport failures do not prevent the manager from attempting the remaining affected viewers. The first transport failure is thrown after flushing; if the callback also failed, it is attached to the callback failure as a suppressed exception.

### Virtual players

Player info and spawn packet transitions are handled automatically across protocol versions:

```java
UserProfile profile = new UserProfile(npcUuid, "Guide");
profile.setTextureProperties(List.of(textureProperty));

VirtualEntity guide = entities.player(profile)
        .metadata()
        .build()
        .setGameMode(GameMode.CREATIVE)
        .setLatency(20)
        .setListed(false)
        .addViewer(packetEventsUser)
        .spawn(new Location(8, 64, 8, 180, 0));
```

`setPlayerProfile` safely refreshes names and skins for current viewers. A profile UUID is the entity UUID and cannot change after construction.

### Audience tracking and interactions

`VirtualAudienceTracker<C>` bridges platform-specific player or chunk contexts without adding a Bukkit or proxy dependency. Supply a viewer adapter and a visibility rule, then call `update` from movement/chunk events or `reconcile` with the current online candidates:

```java
VirtualAudienceTracker<PlayerContext> tracker = VirtualAudienceTracker.of(
        pig,
        context -> VirtualViewer.of(context.user()),
        context -> context.sameWorld(pig) && context.isTrackingChunk(pig)
);

tracker.reconcile(currentPlayers);
```

Viewer membership is also guarded by the PacketEvents entity-type mapping for each client protocol. `addViewer` silently skips unsupported viewers and retains no membership; query `supports` when selecting a downstream fallback. The audience tracker returns `false` and does not claim ownership for unsupported candidates:

```java
VirtualViewer viewer = VirtualViewer.of(packetEventsUser);
if (textDisplay.supports(viewer)) {
    textDisplay.addViewer(viewer);
} else {
    // Select a platform-specific fallback representation.
}
```

Forward PacketEvents interact packets to `VirtualEntityManager#handleInteraction`. The manager accepts only spawned entities visible to that actor, then notifies listeners registered with `onInteraction`:

```java
VirtualEntityInteraction.Subscription clicks = pig.onInteraction(interaction -> {
    if (interaction.action() == VirtualEntityInteraction.Action.ATTACK) {
        // Handle the virtual entity attack.
    }
});
```

For custom transports and tests, use `VirtualViewer.of(UUID, Consumer<PacketWrapper<?>>)` instead of a PacketEvents `User`. It defaults to the server protocol version; use `VirtualViewer.of(UUID, ClientVersion, Consumer<PacketWrapper<?>>)` when the custom transport targets another client version. Viewers created from a PacketEvents `User` automatically use that user's client version for bundle fallback and version-specific teleport packets.

Call `entities.close()` during plugin shutdown. It despawns and unregisters every managed virtual entity.

## Testing

The normal verification gate runs unit, protocol-boundary, generated-source, and concurrency tests:

```bash
./gradlew clean build
```

The black-box gate starts a temporary Paper 1.21.11 server with PacketEvents 2.13.0 and drives it with Mineflayer. It validates spawn decoding, metadata-backed entity identity, relative movement, an attack routed back through `handleInteraction`, and an atomic Text Display translation plus root-anchor relocation bundle:

```bash
./gradlew mineflayerE2e
```

The E2E task requires Java 21 or newer, Node.js 22 or newer, `curl`, `jq`, and network access. It downloads checksummed server/plugin artifacts into `build`, accepts the Minecraft EULA only inside a temporary test server, and removes the generated world and server process on exit. The same task is available from the repository's manually triggered `Mineflayer E2E` workflow.

The legacy black-box workflow builds the core and fixture as Java 17 bytecode, switches the runner to a Java 17 JVM, then tests Paper 1.9.4, 1.12.2, and 1.13.2. It verifies living spawn packets, legacy string metadata, equipment, passengers, relative movement, and inbound attacks. Run one matrix entry locally with:

```bash
./gradlew legacyIntegrationPluginJar
VE_LEGACY_VERSION=1.9.4 \
VE_LEGACY_PAPER_BUILD=775 \
VE_E2E_JAVA=/path/to/java17/bin/java \
./integration/mineflayer/run-legacy-e2e.sh
```

## Updating entity data

Run the same validated sync used by automation:

```bash
./tools/sync-entity-data.sh
./gradlew test
```

The source JSON is retained under `src/main/resources/entity-data` for auditability. Immutable legacy inputs and their pinned Mojang server hashes and Spigot BuildData commits live under `data/legacy-entity-data`. Run `./tools/verify-legacy-entity-data-sources.sh` to revalidate those pins. The sync command merges both data sources and regenerates `GeneratedEntityMetadataKeys`; CI rejects stale generated code or an unmapped upstream data type. Runtime schemas are loaded lazily and cached by Minecraft version.

## Scope

The core API is based entirely on PacketEvents and does not register global listeners or depend on server internals. Platform plugins remain responsible for forwarding their lifecycle, chunk-tracking, and packet-listener events into the provided manager and audience tracker. Dedicated Paper or Velocity convenience modules may be added separately without coupling the core artifact to either platform.

## Credits

- [PacketEvents](https://github.com/retrooper/packetevents) by retrooper and contributors provides the cross-version packet and protocol API used by this library.
- [ViaVersion](https://github.com/ViaVersion/ViaVersion) and [ViaBackwards](https://github.com/ViaVersion/ViaBackwards) provide independently maintained protocol transition evidence used to review legacy metadata boundaries.
- [Spigot BuildData](https://hub.spigotmc.org/stash/projects/SPIGOT/repos/builddata) provides version-pinned class and member identities for legacy Mojang server artifacts.
- [EntityLib](https://github.com/Tofaa2/EntityLib) by Tofaa2 informed the wrapper entity lifecycle and metadata API direction.
- [PacketEntities](https://github.com/3add/PacketEntities) by 3add informed the builder, viewer-management, and data-generation design.
- [TextDisplayShapes](https://github.com/TWME-TW/TextDisplayShapes) provided the renderer use case for readable metadata and atomic multi-entity updates.
- [DisplayNameTags](https://github.com/Matt-MX/DisplayNameTags) provided the use cases for metadata flags, external movement snapshots, and old-client entity fallbacks.
- [kennytv entity-data](https://kennytv.eu/entity-data/) and its [source repository](https://github.com/kennytv/kennytv.eu) provide the decompiled Minecraft metadata layouts bundled by VirtualEntities.
- [Mineflayer](https://github.com/PrismarineJS/mineflayer) is the recommended black-box client for integration testing entity behavior.

VirtualEntities does not copy source code from the referenced entity libraries. Their licenses and authorship remain with their respective projects.

## License

VirtualEntities is licensed under the [MIT License](LICENSE). PacketEvents and all credited projects remain subject to their own licenses.

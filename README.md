# VirtualEntities

[![CI](https://github.com/twme-ai/VirtualEntities/actions/workflows/ci.yml/badge.svg)](https://github.com/twme-ai/VirtualEntities/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/twme-ai/VirtualEntities.svg)](https://jitpack.io/#twme-ai/VirtualEntities)
[![License](https://img.shields.io/github/license/twme-ai/VirtualEntities)](LICENSE)

VirtualEntities is a small, platform-independent Java library for creating and controlling client-side Minecraft entities with [PacketEvents](https://github.com/retrooper/packetevents). It provides entity identity and lookup, viewer lifecycle management, spawn/despawn, teleportation, rotation, head rotation, velocity, object data, and version-aware metadata.

Metadata indexes are resolved from data published at [kennytv.eu/entity-data](https://kennytv.eu/entity-data/). The dataset is bundled into the artifact, so entity operations never make network requests. A scheduled GitHub Actions workflow checks the upstream data daily, validates every downloaded document, and opens a reviewable update pull request when it changes.

## Requirements

- Java 17 or newer
- PacketEvents 2.13.0 installed by the server/proxy or supplied by your plugin
- A Minecraft version present in `EntityMetadataRegistry#versions()` when using metadata

The entity lifecycle API itself is not tied to a Bukkit, Paper, or Velocity API. It works anywhere PacketEvents exposes a `User`.

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
        .metadata("1.21.11", "Pig")
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
pig.rotateHead(45);
pig.velocity(new Vector3d(0, 0.25, 0));

MetadataKey<Integer> boostTime = MetadataKey.of("BOOST_TIME", EntityDataTypes.INT);
pig.metadata().set(boostTime, 40);
pig.syncMetadata();

pig.removeViewer(packetEventsUser);
pig.remove();
```

Define entity-specific fields with `MetadataKey.of`, as shown with `BOOST_TIME`. Glowing is one bit within `SHARED_FLAGS`, not an independent protocol field, so callers should use a small bitmask helper when changing that flag.

The field name is resolved through the selected entity's complete inheritance chain. An unsupported version, entity name, or field fails immediately instead of sending a packet with a guessed index.

For custom transports and tests, use `VirtualViewer.of(UUID, Consumer<PacketWrapper<?>>)` instead of a PacketEvents `User`.

Call `entities.close()` during plugin shutdown. It despawns and unregisters every managed virtual entity.

## Updating entity data

Run the same validated sync used by automation:

```bash
./tools/sync-entity-data.sh
./gradlew test
```

The source JSON is retained under `src/main/resources/entity-data` for auditability. Runtime schemas are loaded lazily and cached by Minecraft version.

## Scope

The initial API covers generic entities. Player profiles/tab-list handling, equipment, attributes, passengers, interactions, relative movement, platform chunk tracking, and typed keys for every entity family are planned additions. The public API will remain based on PacketEvents rather than server internals.

## Credits

- [PacketEvents](https://github.com/retrooper/packetevents) by retrooper and contributors provides the cross-version packet and protocol API used by this library.
- [EntityLib](https://github.com/Tofaa2/EntityLib) by Tofaa2 informed the wrapper entity lifecycle and metadata API direction.
- [PacketEntities](https://github.com/3add/PacketEntities) by 3add informed the builder, viewer-management, and data-generation design.
- [kennytv entity-data](https://kennytv.eu/entity-data/) and its [source repository](https://github.com/kennytv/kennytv.eu) provide the decompiled Minecraft metadata layouts bundled by VirtualEntities.
- [Mineflayer](https://github.com/PrismarineJS/mineflayer) is the recommended black-box client for integration testing entity behavior.

VirtualEntities does not copy source code from the referenced entity libraries. Their licenses and authorship remain with their respective projects.

## License

VirtualEntities is licensed under the [MIT License](LICENSE). PacketEvents and all credited projects remain subject to their own licenses.

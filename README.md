English | [简体中文](README.zh-CN.md)

# SuperLoader Companion

A NeoForge-side companion mod that bridges gaps between Sinytra Connector and
Fabric-native mods on Minecraft 1.21.1. It can be used in any neoforge modpack. SuperLoader is optional.

It does three things:

1. **Refmap repair.** Some Fabric mods declare a refmap in their mixin config
   but do not ship the file. Connector cannot remap their mixins, and a required
   mixin fails with `No refMap loaded`, which aborts Fabric loading for that
   round. Before the game starts, the companion scans `mods/`, synthesizes the
   missing refmap from Connector's `mappings.tiny` (optionally layered with
   Yarn), verifies every entry, and writes the result to a report. Repaired jars
   are backed up to `mods/.superloader-refmap/`.

2. **bclib axe-strip bridge.** NeoForge 21.1 moved axe stripping to a data-map
   path (`BlockState.getToolModifiedState` / `NeoForgeDataMaps.STRIPPABLES`).
   bclib's mixin on `AxeItem.getStripped` became dead code, so BetterEnd and
   BetterNether logs could not be stripped. The companion listens for
   `BlockToolModificationEvent` and re-registers every block implementing
   bclib's `AxeCanStrip` back into the stripping flow. One bridge covers all
   mods that use the interface, not a single mod ID.

3. **Runtime data collection.** During load and gameplay it writes
   `runtime.json` and `events.json` into the instance's
   `logs/superloader-report/`. These are optional diagnostics consumed by
   SuperLoader's report renderer; the mod works without SuperLoader installed.

## Environment

In addition to Minecraft 1.21.1 and NeoForge 21.1.265, this mod requires:

- **Sinytra Connector** (`connector-2.0.0-beta.17+1.21.1-full.jar` or compatible)
- **Forgified Fabric API** (`forgified-fabric-api-0.116.15+2.3.6+1.21.1.jar` or compatible)

Both are hard dependencies: refmap repair reads Connector's `mappings.tiny`,
and the bridges and runtime data collection rely on FFAPI's compatibility
layer. The mod will not function without them.

They are distributed on Modrinth (`connector`, `forgified-fabric-api`) and can
be installed by any launcher. SuperLoader installs them automatically if you
use it.

## Build

Requires JDK 21.

```bat
gradlew.bat build --offline
```

Output: `build/libs/superloader_companion-0.6.3.jar`.

`gradlew.bat build` (online) also works; that is what CI runs.

## Install

Drop the jar into any NeoForge 1.21.1 instance's `mods/` directory. No launcher
or loader integration is required: it is a normal NeoForge mod. SuperLoader's
`install` / `launch` copies it automatically if you use SuperLoader, but that
is a convenience, not a requirement.

## Toggles

| Variable | Effect |
|---|---|
| `SL_NO_REFMAP_REPAIR=1` | Disable pre-launch refmap repair |
| `SL_NO_AXE_BRIDGE=1` | Disable the bclib axe-strip bridge |
| `-Dsuperloader.axeBridge.disabled=true` | Same as above, JVM-arg form |

Every bridge is on by default and can be disabled without removing the mod.

## Log lines

- `[refmap] missing N, repaired N jars, entries N, verified N, unresolved N, yarn=loaded`
- `[refmap-repair] ...` from the repair tool itself
- `[sl-bridge] axe strip bridge installed (bclib AxeCanStrip -> BlockToolModificationEvent)`
- `[sl-bridge] axe strip: Block{betterend:lacugrove_log} -> Block{betterend:lacugrove_stripped_log}`

## Running the refmap repair tool standalone

Normally invoked by SuperLoader before launch. For manual debugging:

```bat
java -cp build\libs\superloader_companion-0.6.3.jar com.superloader.companion.repair.RefmapRepair ^
  --mods  "<instance>\mods" ^
  --connector "<instance>\mods\connector-2.0.0-beta.17+1.21.1-full.jar" ^
  --report "out\refmap_repair.json" ^
  --mc-jar "<instance>\libraries\net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar" ^
  --yarn-version 1.21.1+build.3 ^
  --yarn-sha1 53c8c9729c0dd52e3549c04f16eca89a15818822 ^
  --cache ".cache\mappings" ^
  --dry-run
```

Notes:

- `--dry-run` computes without writing. Drop it to modify jars (backups go to
  `mods/.superloader-refmap/`).
- `--restore` restores jars from backup.
- Every mapping carries `verified: true/false` in the report; unverified
  entries are listed as unresolved and are never silently written.
- `--yarn-version` and `--yarn-sha1` are optional; without them, bare Yarn
  names cannot be resolved.

## Known limitations

- Refmap repair only fixes the "refmap declared but missing" case. It cannot
  fix mixins whose NeoForge-side target was moved or changed
  (`Language.loadFromJson`, `CropBlock.getGrowthSpeed`, `AxeItem.getStripped`).
  Those are Connector-level gaps, not refmap gaps.
- The axe-strip bridge only covers blocks implementing bclib `AxeCanStrip`.
  Other mods with their own stripping logic still use NeoForge data maps.
- Versions are hardcoded: `gradle.properties` `mod_version` and
  `SuperLoaderCompanion.VERSION` must be changed together.
- 1.21.1 support only. Sinytra Connector has ended LTS support for 1.21.1, so
  upstream fixes for the remaining gaps are not expected.

## License

MIT, see [LICENSE](LICENSE).
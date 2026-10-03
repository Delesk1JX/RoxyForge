# MODLOG — RoxyForge 1.20.1

Port of [Roxy](https://github.com/RasaNovum/Roxy) (MIT) from NeoForge 1.21.1/1.21.11 to **Forge 1.20.1**,
so that the Fabric-only LOD renderer **Voxy** (All Rights Reserved, never redistributed) can run there.
Working copy of the fork `Delesk1JX/RoxyForge`. No Voxy code or jar is committed here.

Payload decision: **Voxy `0.2.16-beta+1.21.11`** (the same build upstream Roxy 1.21.1 is verified with).
Cost: Minecraft 1.21.11 -> 1.20.1 is ~10 minor releases; 1.20.1 has no data components, a different
render pipeline and SRG runtime names.

## Environment

- JDK 17.0.20 (Temurin) -> `AppData\Local\Programs\jdk-17` — builds and runs the 1.20.1 client.
- JDK 25.0.4 (Temurin) -> `AppData\Local\Programs\jdk-25` — only needed by the upstream NeoForge build
  (`prism`/`dev.prism` requires a Java 25 JVM; already assumed by upstream's `runClientPackaged`).
- Gradle 8.8 for the Forge build (ForgeGradle 6 does not work on Gradle 9).
- Test instance: ElyPrism `1.20.1 Test`, Forge **47.4.10**; worlds backed up with `um backup`
  (`mc1201-Test1`, `mc1201-_________`).
- `Embeddium` is not installed in that instance yet (needed for LOD rendering, later milestone).

## Build-system findings (M0)

The upstream `dev.prism` multi-version plugin cannot host Forge 1.20.1:

1. `prism { version("1.20.1") { legacyForge() } }` -> `LegacyForgeConfigurator` calls
   `JvmVendorSpec.IBMSEMERU`, removed in Gradle 9 (renamed `IBM_SEMERU` in 9.7+). Fails on 9.4 and 9.8.
2. plugin `0.6.1` needs the Gradle plugin API 9.7.0 -> the 9.4 wrapper rejects it.
3. RetroFuturaGradle (what `legacyForge` applies) only publishes 2.0.3-2.0.6 and answers
   **"Unsupported MC version 1.20.1"** for 2.0.3 and 2.0.5. RFG also needs a Java 25 JVM.

So the Forge port gets its own build in `forge-1.20.1/` (ForgeGradle 6.0.24 + `org.spongepowered.mixin`),
leaving the upstream NeoForge build untouched. Build with:

    cd forge-1.20.1
    set JAVA_HOME=C:\Users\Alex\AppData\Local\Programs\jdk-17
    gradlew.bat build

## Milestones

- [x] **M0** Forge 1.20.1 build works: `roxy-0.1.0-forge.jar` builds with FG 6.0.24 + Java 17.
- [x] **M0b** Forge 47.4.10 loads the jar: instance reached "Sound engine started", no crash;
      Forge picked the mod up (`roxy.mixins.json` refmap warning only).
      Note: `System.out` does not reach `latest.log` on 1.20.1, so the mod now logs through
      `com.mojang.logging.LogUtils` — verified only after the next launch.
- [x] **M1** SPI layer on Forge. NeoForge's three SPI classes collapse into one:
  `IDependencyLocator` (Forge 7.0.1 has no `IModFileCandidateLocator` / `IModFileReader`, and its
  `scanMods` returns a list instead of filling a pipeline). `RoxyForgeDependencyLocator` finds
  `voxy*.jar` next to our own mod file, copies it with `FMLModType: LIBRARY` in the manifest and hands
  it to `ModFileFactory.FACTORY.build(jar, this, modFile -> null)` - Forge puts it on the module layer
  without creating a mod container. Builds clean.
- [x] **M2** runtime mappings, built offline by `tools/roxy-mappings/build_mappings.py`.
      Forge 1.20.1 runs with **official class names** (`net/minecraft/world/level/block/state/BlockState`)
      but **SRG members** (`m_7160_`), verified with javap on `forge-1.20.1-47.4.10-client.jar`.
      Chain: Fabric intermediary 1.21.11 -> intermediary 1.20.1 (stable intermediary namespace) ->
      obfuscated -> official (Mojang `client.txt`), members obfuscated -> SRG (Forge `joined.tsrg`).
      Result: 6556 classes, 50 359 members mapped (36 878 exact descriptor matches, 13 481 by name).
- [x] **M3a** coverage measured against the real `voxy-0.2.16-beta+1.21.11.jar` (hash verified against
      Modrinth): **93.96 % of referenced Minecraft classes (280/298)** and **42.45 % of members (163/384)**.
- [ ] **M3b** Java remapper that applies the mapping to Voxy's classes.
- [ ] **M4** structural patches + bridges for 1.20.1, Embeddium instead of Sodium.
- [ ] **M5** mixins for 1.20.1, drop 1.21-only compat (Iris, Chunky, Sable, TFC, PowerGrid).
- [ ] **M6** install Embeddium, verify LODs in game, screenshots.

## M3a verdict: what the unmapped 58 % is

The references that do **not** map are not a mapping defect, they are 1.21-only API:

- classes absent in 1.20.1 entirely: `class_11515`, `class_11630`, `class_11632`, `class_10868`,
  `class_7924` (`net.minecraft.world.level.storage` / storage-layout rework of 1.21).
- `ClientLevel.method_60654/60655` (13 + 6 uses) - 1.21 accessors with no 1.20.1 equivalent.
- `Minecraft.field_1769/1687/1773` (`hitResult`, `level`, `player`) - 1.20.1 has the same fields under
  different intermediary ids, so they cannot be matched by name and need explicit patches.

Upstream Roxy solves the same problem for 1.21.11 -> 1.21.1 with ~20 hand-written bytecode patches
(`RoxyBytecodeRemapper`, 4835 lines). For 1.21.11 -> 1.20.1 the same technique is needed at a larger
scale, so M4 is the bulk of the remaining work.

## Open blocker

The ElyPrism Microsoft session expired, so the 1.20.1 instance no longer starts unattended.
Needs a re-login in the launcher before M0b/M6 can be re-verified in game.
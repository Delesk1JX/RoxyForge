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
- [ ] **M1b** register the copy as a Forge mod file - *in progress, all steps verified one by one*:
  1. done: Forge discovers the locator - `Found Dependency Locators : (RoxyForgeLocator:0.1.0-forge)`.
     Dev runs (`gradlew runClient`) do **not** load it: services are read from the classpath and a dev
     mod lives in the module layer instead, so this has to be verified in the real instance.
  2. done: the Voxy jar is found and copied; own log at `<gamedir>/roxyforge-locator.log`, because mod
     discovery runs before log4j and stdout never reaches `latest.log`.
  3. done: Forge 1.20.1 rejects a mod file with no `mods` list (`InvalidModFileException: Missing
     ModLoader in file`), and `ModFileInfo` cannot express "no mods" - so the copy gets a generated
     `META-INF/mods.toml` plus a shim class `net.voxy.Voxy` carrying `@Mod("voxy")`.
  4. done: the manifest needs `FMLModType`, otherwise FML reports
     `The following classes are missing, but are reported in the mods.toml: [voxy]`.
  5. **open**: the copy still has to become a module whose packages are exported. `SimpleJarMetadata`
     exports nothing (so FML cannot load `net.voxy.Voxy`), and the first `ModuleJarMetadata(target.toUri(),
     Set.of())` attempt throws `IllegalArgumentException: Unsupported class file major version 2056`.
     Next: read how `SecureJar.Provider.fromPath` / FML's own mod file scanning builds the metadata.
- [x] **M2** runtime mappings, built offline by `tools/roxy-mappings/build_mappings.py`.
      Forge 1.20.1 runs with **official class names** (`net/minecraft/world/level/block/state/BlockState`)
      but **SRG members** (`m_7160_`), verified with javap on `forge-1.20.1-47.4.10-client.jar`.
      Chain: Fabric intermediary 1.21.11 -> intermediary 1.20.1 (stable intermediary namespace) ->
      obfuscated -> official (Mojang `client.txt`), members obfuscated -> SRG (Forge `joined.tsrg`).
      Result: 6556 classes, 50 359 members mapped (36 878 exact descriptor matches, 13 481 by name).
      Gotcha worth remembering: Fabric's 1.20.1 tiny labels its left column "official" although those are
      obfuscated names, and tiny v1 member lines start with the **owner**, not the name.
- [x] **M3a** coverage measured against the real `voxy-0.2.16-beta+1.21.11.jar` (hash verified against
      Modrinth): **93.96 % of referenced Minecraft classes (280/298)** and **42.45 % of members (163/384)**.
- [x] **M3b** offline remapper: `RoxyForgeMappings` + `RoxyForgeRemapper` (ASM) and the `remapVoxy`
      Gradle task. Runs outside the game, so it can be iterated on without launching Minecraft:

          gradlew remapVoxy     # -> build/remapped/voxy.jar + build/remapped/remap-report.txt

      Result on Voxy 0.2.16-beta: 350 classes in, 350 out, **306 member renames**, and the leftovers are
      **19 distinct Minecraft classes** plus **40 members** - the 1.21-only API M4 has to bridge.
      The worst offender by far is `net/minecraft/class_11515` (99 references), followed by the
      `class_11630..class_11635` family (36 references), which is the 1.21 storage-layout rework.
      Naming those classes needs Mojang's 1.21.11 `client.txt`: Fabric's `intermediary-1.21.11.jar`
      labels its left column "official" but it actually holds 1.21.11 *obfuscated* names (`hth`, `glw`...),
      so it cannot answer "what is class_11515 in mojmap terms".
- [x] **M3c** static link check, `gradlew linkCheckVoxy` -> `build/remapped/link-report.txt`.
      Walks the remapped jar's bytecode and asks the **real** SRG-named
      `client-1.20.1-20230612.114412-srg.jar` (plus Embeddium) whether every referenced class, method and
      field exists, walking the super/interface chain. No game launch needed, so it can be run on every
      change. On voxy-0.2.16-beta: 350 classes walked, 1024 Minecraft class references, 3281 method and
      1313 field references, and **205 unresolved symbols in total** - 13 classes, 137 methods, 55 fields.
      Those two hundred are the M4 work list, and they fall into two different groups:
      - **API that 1.20.1 simply does not have** (needs a shim class or a patch): `class_10868`,
        `class_10889`, `class_11515`, `class_11630/11631/11632/11635`, `class_11897`, `class_12253`,
        `class_7285`, `class_9259`, `class_9779`, `class_9848`, plus members such as
        `WorldVersion.comp_4026()` (records do not exist in 1.20.1).
      - **Mapping misses**, which are fixable in the generator rather than in bytecode: some members were
        left as `method_1548()` because their descriptor changed between versions, and a few picked the
        wrong overload and now carry an SRG name that 1.20.1 does not have (e.g. `Minecraft.m_271549_()`).
        The link checker is exactly the feedback loop the generator needs: generate -> check -> fix.
- [x] **M3d** the mapping generator now verifies itself, and it changed the picture.
      `build_mappings.py --mc-jar <client-1.20.1-srg.jar>` indexes what Minecraft 1.20.1 really declares
      (`tools/roxy-mappings/mcindex.py`) and uses it twice:
      - **validation** - an entry is only written if 1.20.1 declares that class/member with that
        signature. Before this the generator emitted ~28 700 SRG names that do not exist in 1.20.1; each
        would have been a `NoSuchMethodError` the static check could not see, because the mapping claimed
        the reference was handled.
      - **repair** - when the overload picked by name does not exist in 1.20.1, every other overload of
        the same intermediary member is tried and the first one that exists wins.
      Result: from "50 359 entries, many wrong" to **21 705 verified entries, 0 wrong** (13 486 methods,
      8 219 fields, 6 556 classes). Two matcher tiers were added for signatures that changed shape
      between versions (arity plus primitive/array positions).
- [x] **M3c final numbers** with the verified mapping: 350 classes walked, 1024 Minecraft class, 3285
      method and 1313 field references, **227 unresolved symbols: 13 classes, 151 methods, 63 fields**.
- [x] **M4a** the 1.21-only classes are handled, and unresolved classes are now zero.
      Two mechanisms, both driven by the link checker so they stay honest:
      - **aliases** (`tools/roxy-mappings/aliases.txt`) for classes that exist in 1.20.1 under another
        name: BlockModelPart -> client/model/geom/ModelPart, FogData -> FogRenderer$FogData,
        ChunkSectionLayer -> ChunkRenderDispatcher$RenderChunk. Targets are validated like any other
        entry, so a wrong line is dropped rather than becoming a NoClassDefFoundError.
        Voxy class coverage 93.96% -> 95.30%.
      - **shims** for the rest: `make_shims.py` generates a Java source per missing class declaring
        exactly the surface Voxy touches, compiled into our jar, listed in `roxyforge/shims.txt` and
        injected into the runtime copy of the Voxy jar by the locator. 10 shims today (GlTexture,
        DebugScreenDisplayer/Entries/Entry/EntryStatus, PalettedContainerFactory, MipmapStrategy,
        ChunkResult, DeltaTracker, ARGB and two more). Bodies throw `UnsupportedOperationException` on
        purpose: this milestone is "Voxy links", and the game run is what shows which calls need real
        implementations.
      Result: **UNRESOLVED classes 0**, and the link checker now sees our jar, so it measures what the
      game will see.
- [~] **M4b** state: 0 unresolved classes, and the bridge mechanism works, but the remaining members
      **oscillate** between two values depending on the round (96/40 -> 46/14 -> 96/40) instead of
      converging to zero. Two causes found this round:
      - the link report only **listed** the top 40 entries per section while counting all of them, so
        make_bridges.py was generating bridges for a sample and never the whole set. `SHOWN` now lists
        everything, and a full round takes the remainder from 96/40 down to **46/14**.
      - the joml lead was real and is now wired: third-party libraries ship as separate jars, so they are
        in neither the Minecraft jar nor Mojang's mapping, which is why the renames were being skipped.
        class-renames.txt entries are accepted as hand-curated now (Vector3fc -> Vector3f and friends).
      Open: after a full bridge round the next round regresses, which means the bridges generated for the
      new set do not fire. The prime suspect is the *source* key: it is built by mapping the owner and
      the descriptor classes back through the class mapping, and any descriptor class that has no class
      mapping (again, third-party) leaves the source key in the wrong namespace. Next step: print a few
      source keys next to the actual instruction owners in the remapped jar and compare.
      Fixes this round: bridge keys are written the way the **bytecode** spells the member (name and
      descriptor glued), because that is what the redirect's lookup key is built from; fields also accept
      the older concatenated form so a stale report cannot silently produce broken keys; shim classes
      (`net/minecraft/class_XXXX`) have no class mapping so `source_key` falls back to the owner as-is;
      static calls and static fields get their own `...Static` variants because they have no receiver on
      the stack.
      Next lead, with evidence: the remaining methods are dominated by members whose **signature types**
      were renamed between versions - the remapped output still contains
      `Lorg/joml/Vector3fc;` and `Lorg/joml/Quaternionfc;`, which 1.20.1 spells `Vector3f` and
      `Quaternionf`. JFace/joml gained the `c` (float) variants in 1.21, and third-party libraries are
      never obfuscated, so Fabric's intermediary mapping has no entries for them and the generator cannot
      line them up. `tools/roxy-mappings/class-renames.txt` now carries them, but the entry point is not
      working yet: the rename targets are not found either in the SRG client jar (which does not ship
      third-party libraries) or - unexpectedly - in Mojang's class table, so the entries are being
      skipped. Confirm where `org/joml/Vector3f` actually lives before wiring that in.
      Earlier fixes from the same effort, kept for the record:
      1. member deobfuscation has to be scoped by owner class (obfuscated member names are only unique
         inside their class - `ac` is a class in one place and a field in another). With that, the mojmap
         matching stage became valid: +16 599 verified entries, coverage 11.98% -> 27.86%.
      2. the Minecraft client SRG jar is not the whole API surface - WorldVersion, ChunkResult and
         PalettedContainerFactory are server-side, and the Mojang server jar ships obfuscated, so it has
         to be deobfuscated with the 1.20.1 mojmap. build_mappings.py --emit-index writes the combined
         index (12 896 classes) and the link checker reads it.
      3. the tiny parser converted member descriptors in a single pass, so any descriptor mentioning a
         class listed later in the file kept its obfuscated name, silently breaking descriptor-keyed
         lookups. Two passes fixed it: coverage 27.86% -> 34.64%.
      1. member deobfuscation has to be **scoped by owner class** (obfuscated member names are only
         unique inside their class - `ac` is a class in one place and a field in another). With that,
         the mojmap matching stage became valid: 16 599 extra verified entries, Voxy member coverage
         11.98% -> 27.86%.
      2. the Minecraft **client** SRG jar is not the whole API surface - `WorldVersion`, `ChunkResult`,
         `PalettedContainerFactory` are server-side, and the Mojang server jar ships **obfuscated**, so it
         has to be deobfuscated with the 1.20.1 mojmap. `build_mappings.py --emit-index` writes the
         combined index (12 896 classes) and the link checker reads it.
      3. the tiny parser converted member descriptors in a **single pass**, so any descriptor mentioning a
         class listed later in the file kept its obfuscated name - which silently broke every member
         lookup keyed by descriptor. Two passes fixed it: coverage 27.86% -> **34.64%**.
      Where it stands: **0 unresolved classes, 120 methods, 50 fields**. The remainder is concentrated in
      a few owners - Minecraft (11), ClientLevel (9), Direction (6), RenderChunk, GameRenderer,
      FogRenderer$FogData, LerpingBossEvent, BakedQuad - which is the shape of the work that is left:
      per-member bridges.
- [ ] **M4c** bridges for Embeddium instead of Sodium.
- [ ] **M5** feed the remapped jar into the in-game path (SPI) and get the launch stable.
- [ ] **M6** run in 1.20.1 Forge, verify LODs, screenshots.

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
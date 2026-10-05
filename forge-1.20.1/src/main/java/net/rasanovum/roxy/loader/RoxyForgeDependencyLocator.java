package net.rasanovum.roxy.loader;

import cpw.mods.jarhandling.SecureJar;
import net.minecraftforge.fml.loading.moddiscovery.ModFileParser;
import net.minecraftforge.forgespi.locating.IDependencyLocator;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.ModFileFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.Manifest;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Forge 1.20.1 has no IModFileCandidateLocator / IModFileReader like NeoForge's FML 4, so all three jobs
 * (find the Voxy jar, make it loadable, hand it to the loader) live in this one locator.
 *
 * The copy gets a generated META-INF/mods.toml plus one shim class, so Forge treats it as a mod file whose\r?\n * classes are on the module layer - while Voxy's own Fabric entrypoints stay untouched and are driven by\r?\n * RoxyForge itself.
 */
public final class RoxyForgeDependencyLocator implements IDependencyLocator {
    public static final String VOXY_JAR_PREFIX = "voxy";
    private static final List<String> SHIM_CLASSES = List.of("net/voxy/Voxy.class");
    private boolean done;

    @Override
    public List<IModFile> scanMods(Iterable<IModFile> loadedMods) {
        List<IModFile> found = new ArrayList<>();
        if (done) {
            return found;
        }
        try {
            for (IModFile mod : loadedMods) {
                Path self = mod.getFilePath();
                if (self == null) {
                    continue;
                }
                Path source = findVoxyJar(self.getParent());
                if (source == null) {
                    source = findVoxyJar(workingDirectoryMods());
                }
                if (source == null) {
                    continue;
                }
done = true;
            try {
                installShutdownHook();
                IModFile registered = registerVoxy(source);
                    found.add(registered);
                    trace("registered Voxy " + source.getFileName() + " as a library mod file");
                } catch (Throwable problem) {
                    trace("could not register Voxy " + source.getFileName() + ": " + problem);
                }
                break;
            }
            if (!done) {
                trace("no voxy*.jar next to the RoxyForge jar or in " + Path.of("mods").toAbsolutePath());
            }
        } catch (Throwable failure) {
            trace("scanMods failed: " + failure);
        }
        return found;
    }

    /**
     * Mod discovery runs before log4j exists, so stdout is never flushed to latest.log. Write our own
     * line next to the game directory instead - it is the only way to see why Voxy did or did not load.
     */
    private static boolean hookInstalled;

    /**
     * The game can die without a crash report - a clean System.exit during Mixin transformation leaves no
     * trace in any log. Dumping every thread on shutdown is the only way to see where it stopped.
     */
    static void installShutdownHook() {
        if (hookInstalled) {
            return;
        }
        hookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
            StringBuilder dump = new StringBuilder("shutdown: the JVM is going down\n");
            for (Map.Entry<Thread, StackTraceElement[]> entry : traces.entrySet()) {
                dump.append("  thread ").append(entry.getKey().getName()).append('\n');
                for (StackTraceElement element : entry.getValue()) {
                    dump.append("      ").append(element).append('\n');
                }
            }
            trace(dump.toString());
        }, "roxyforge-shutdown-dump"));
    }

    static void trace(String message) {
        String line = "[roxyforge] " + message;
        System.out.println(line);
        try {
            Path gameDirectory = Path.of("").toAbsolutePath();
            Files.createDirectories(gameDirectory);
            Files.writeString(gameDirectory.resolve("roxyforge-locator.log"),
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    /** Dev runs launch with the project directory as the working directory and keep mods in mods/. */
    private static Path workingDirectoryMods() {
        String override = System.getProperty("roxyforge.voxy");
        if (override != null && !override.isBlank()) {
            Path configured = Path.of(override);
            if (Files.isRegularFile(configured)) {
                return configured.getParent();
            }
        }
        return Path.of("mods");
    }

    @Override
    public String name() {
        return "RoxyForgeLocator";
    }

    @Override
    public void scanFile(IModFile modFile, java.util.function.Consumer<Path> configConsumer) {
    }

    @Override
    public void initArguments(java.util.Map<String, ?> arguments) {
    }

    @Override
    public boolean isValid(IModFile modFile) {
        return true;
    }

    private static Path findVoxyJar(Path modsDirectory) {
        String override = System.getProperty("roxyforge.voxy");
        if (override != null && !override.isBlank() && Files.isRegularFile(Path.of(override))) {
            return Path.of(override);
        }
        // Voxy must live outside mods/: Forge scans the original as well, and two mod files exporting
        // me.cortex.voxy.* is a split package, which makes building the module layer fail. So the game
        // directory gets a roxy/ folder and the jar is expected there.
        Path dedicated = Path.of("roxy");
        Path found = firstVoxyJar(dedicated);
        if (found != null) {
            return found;
        }
        found = firstVoxyJar(modsDirectory);
        if (found != null) {
            trace("found Voxy in mods/ - move it to " + dedicated.toAbsolutePath()
                    + "; leaving it there means the original and our copy are both scanned");
        }
        return found;
    }

    private static Path firstVoxyJar(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return null;
        }
        try (var entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(RoxyForgeDependencyLocator::looksLikeVoxy)
                    .findFirst()
                    .orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean looksLikeVoxy(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") && name.startsWith(VOXY_JAR_PREFIX);
    }

private IModFile registerVoxy(Path source) throws IOException {
        Path working = Files.createTempDirectory("roxyforge");
        // The copy must not be named after the original: two mod files with the same file name resolve to
        // the same module name, and building the module layer then fails with a clean exit and no log.
        Path target = working.resolve("roxyforge-" + source.getFileName().toString().toLowerCase(Locale.ROOT));
        copyAsLibrary(source, target);
        remapInPlace(target);
        SecureJar secureJar = SecureJar.from((name, size) -> true, target);
        trace("secure jar built for " + target.getFileName());
        IModFile file = ModFileFactory.FACTORY.build(secureJar, this, ModFileParser::modsTomlParser);
        trace("mod file ready: " + file.getFilePath() + " type=" + file.getType()
                + " mods=" + file.getModInfos());
        return file;
    }

    /**
     * Rewrite Voxy's intermediary bytecode into Forge 1.20.1 names on the copy we just made. The user
     * keeps dropping in an untouched Voxy jar; the mapping and the bridge list travel inside our own jar,
     * so this is the same approach upstream Roxy takes. Without it the classes keep intermediary names
     * and fail to link in game.
     */
    private static void remapInPlace(Path target) {
        try (InputStream mapping = RoxyForgeDependencyLocator.class.getClassLoader()
                .getResourceAsStream("roxyforge/mapping.txt")) {
            if (mapping == null) {
                trace("no roxyforge/mapping.txt in our jar, the copy is left unremapped");
                return;
            }
            Path mappingFile = Files.createTempFile("roxyforge-mapping", ".txt");
            Files.copy(mapping, mappingFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            RoxyForgeMappings mappings = RoxyForgeMappings.load(mappingFile);
            Path remapped = Files.createTempFile("roxyforge-remapped", ".jar");
            Path bridgeFile = Files.createTempFile("roxyforge-bridges", ".txt");
            try (InputStream bridges = RoxyForgeDependencyLocator.class.getClassLoader()
                    .getResourceAsStream("roxyforge/bridges.txt")) {
                if (bridges != null) {
                    Files.copy(bridges, bridgeFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            RoxyForgeRemapper.Report report = new RoxyForgeRemapper(mappings, bridgeFile)
                    .remapJar(target, remapped);
            // Never write over the jar we are reading: the remapper streams it entry by entry.
            Files.move(remapped, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            trace("remapped " + report.classesIn() + " classes, " + report.memberRenames() + " member renames, "
                    + RoxyBridgeRedirect.totalRedirects() + " call sites redirected into bridges");
            Files.deleteIfExists(mappingFile);
            Files.deleteIfExists(bridgeFile);
        } catch (IOException | RuntimeException problem) {
            trace("remap failed: " + problem);
        }
    }



    private static void copyAsLibrary(Path source, Path target) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("FMLModType", "MOD");
        manifest.getMainAttributes().putValue("RoxyForge-Original", source.getFileName().toString());
        try (ZipFile zip = new ZipFile(source.toFile());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || isSynthetic(entry.getName())) {
                    continue;
                }
                out.putNextEntry(new JarEntry(entry.getName()));
                try (InputStream input = zip.getInputStream(entry)) {
                    input.transferTo(out);
                }
                out.closeEntry();
            }
            addModsToml(out, source);
            // Forge only builds a mod container from classes inside the mod file, and a package that
            // exists in two modules of the layer breaks the module layer, so the entrypoint travels as a
            // resource of our jar and is injected here.
            out.putNextEntry(new JarEntry("net/voxy/Voxy.class"));
            try (InputStream entry = entrypointBytes()) {
                entry.transferTo(out);
            }
            out.closeEntry();
        }
    }

    private static InputStream entrypointBytes() throws IOException {
        InputStream input = RoxyForgeDependencyLocator.class.getClassLoader()
                .getResourceAsStream("roxyforge/shim/net/voxy/Voxy.class");
        if (input == null) {
            throw new IOException("missing the Voxy entrypoint class in roxyforge/shim");
        }
        return input;
    }


    private static Manifest manifestFor(Path source) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("FMLModType", "MOD");
        manifest.getMainAttributes().putValue("RoxyForge-Original", source.getFileName().toString());
        return manifest;
    }

    /** Forge needs a mods.toml with at least one [[mods]] entry before it will load a file. */
    private static void addModsToml(JarOutputStream out, Path source) throws IOException {
        out.putNextEntry(new JarEntry("META-INF/mods.toml"));
        out.write(("modLoader = \"javafml\"\n"
                + "loaderVersion = \"[47,)\"\n"
                + "license = \"All Rights Reserved (Voxy is not redistributed by RoxyForge)\"\n"
                + "\n"
                + "[[mods]]\n"
                + "modId = \"voxy\"\n"
                + "version = \"" + versionOf(source) + "\"\n"
                + "displayName = \"Voxy (loaded by RoxyForge)\"\n"
                + "\n"
                + "[[dependencies.voxy]]\n"
                + "modId = \"minecraft\"\n"
                + "mandatory = true\n"
                + "versionRange = \"[1.20.1,1.20.2)\"\n"
                + "ordering = \"AFTER\"\n"
                + "side = \"CLIENT\"\n").getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static String versionOf(Path source) throws IOException {
        try (ZipFile zip = new ZipFile(source.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) {
                return "0.0.0";
            }
            try (InputStream input = zip.getInputStream(entry)) {
                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                int at = json.indexOf("\"version\"");
                if (at < 0) {
                    return "0.0.0";
                }
                int colon = json.indexOf(':', at);
                int first = json.indexOf('"', colon);
                int second = json.indexOf('"', first + 1);
                return first > 0 && second > first ? json.substring(first + 1, second) : "0.0.0";
            }
        }
    }

    private static boolean isSynthetic(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.equals("META-INF/MANIFEST.MF")
                || upper.equals("META-INF/MODS.TOML")
                || upper.equals("META-INF/VOXY-FABRIC.JSON")
                || upper.startsWith("META-INF/ROXY/")
                || upper.startsWith("NET/VOXY/");
    }
}
package net.rasanovum.roxy.loader;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.jarhandling.impl.Jar;
import cpw.mods.jarhandling.impl.SimpleJarMetadata;
import net.minecraftforge.forgespi.language.ModFileScanData;
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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Forge 1.20.1 has no IModFileCandidateLocator / IModFileReader like NeoForge's FML 4, so all three jobs
 * (find the Voxy jar, make it loadable, hand it to the loader) live in this one locator.
 *
 * The jar is copied to a temp folder with {@code FMLModType: LIBRARY} in its manifest and an empty
 * ModFileScanData, so Forge puts it on the module layer without creating a mod container: Voxy is not a
 * Forge mod, RoxyForge drives its Fabric entrypoints itself.
 */
public final class RoxyForgeDependencyLocator implements IDependencyLocator {
    public static final String VOXY_JAR_PREFIX = "voxy";
    private static final String VOXY_MOD_ID = "voxy";
    private boolean done;

    @Override
    public List<IModFile> scanMods(Iterable<IModFile> loadedMods) {
        List<IModFile> found = new ArrayList<>();
        if (done) {
            return found;
        }
        for (IModFile mod : loadedMods) {
            Path self = mod.getFilePath();
            if (self == null) {
                continue;
            }
            Path source = findVoxyJar(self.getParent());
            if (source == null) {
                continue;
            }
            done = true;
            try {
                IModFile registered = registerVoxy(source);
                found.add(registered);
                System.out.println("RoxyForge: registered Voxy " + source.getFileName()
                        + " as a library mod file");
            } catch (IOException | RuntimeException problem) {
                System.out.println("RoxyForge: could not register Voxy " + source.getFileName() + ": " + problem);
            }
            break;
        }
        return found;
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
        if (modsDirectory == null || !Files.isDirectory(modsDirectory)) {
            return null;
        }
        try (var entries = Files.list(modsDirectory)) {
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
        Path target = working.resolve(source.getFileName().toString().toLowerCase(Locale.ROOT));
        copyAsLibrary(source, target);
        SecureJar secureJar = secureJar(target);
        // No IModFileInfo: the file carries no mod to construct, only classes for the module layer.
        return ModFileFactory.FACTORY.build(secureJar, this, modFile -> null);
    }

    private static SecureJar secureJar(Path jar) throws IOException {
        Manifest manifest = readManifest(jar);
        return new Jar(
                () -> manifest,
                secure -> new SimpleJarMetadata("roxyforge", manifest.getMainAttributes().getValue("RoxyForge-Original"),
                        java.util.Set.of(), List.of()),
                (name, size) -> true,
                jar);
    }

    private static Manifest readManifest(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
            if (entry == null) {
                return new Manifest();
            }
            try (InputStream input = zip.getInputStream(entry)) {
                return new Manifest(input);
            }
        }
    }

    private static void copyAsLibrary(Path source, Path target) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("FMLModType", "LIBRARY");
        manifest.getMainAttributes().putValue("RoxyForge-Original", source.getFileName().toString());

        try (ZipFile zip = new ZipFile(source.toFile());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            out.putNextEntry(new JarEntry("META-INF/voxy-fabric.json"));
            out.write(voxyMarker(source).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
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
        }
    }

    private static boolean isSynthetic(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.equals("META-INF/MANIFEST.MF")
                || upper.equals("META-INF/VOXY-FABRIC.JSON")
                || upper.startsWith("META-INF/ROXY/");
    }

    private static String voxyMarker(Path source) {
        return "{\"schemaVersion\":1,\"id\":\"" + VOXY_MOD_ID + "\",\"source\":\"" + source.getFileName() + "\"}";
    }
}
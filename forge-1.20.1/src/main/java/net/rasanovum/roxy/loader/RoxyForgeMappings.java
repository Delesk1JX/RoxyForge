package net.rasanovum.roxy.loader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * RoxyForge's runtime mapping: Fabric intermediary 1.21.11 -> Forge 1.20.1.
 *
 * Forge 1.20.1 runs with official class names and SRG members, so the file produced by
 * tools/roxy-mappings/build_mappings.py is read here and fed to ASM's remapper. Members are keyed by
 * owner + name + descriptor because overloads share an intermediary name but not an SRG one.
 */
public final class RoxyForgeMappings {
    private final Map<String, String> classes = new HashMap<>();
    private final Map<String, String> members = new HashMap<>();

    public static RoxyForgeMappings load(Path file) throws IOException {
        RoxyForgeMappings mappings = new RoxyForgeMappings();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                String[] parts = line.split("\t");
                switch (parts[0]) {
                    case "C" -> mappings.classes.put(parts[1], parts[2]);
                    case "M", "F" -> mappings.members.put(key(parts[1], parts[2], parts[3]), parts[5]);
                    default -> { }
                }
            }
        }
        return mappings;
    }

    private static String key(String owner, String name, String descriptor) {
        return owner + '.' + name + descriptor;
    }

    public String mapClass(String internalName) {
        return classes.getOrDefault(internalName, internalName);
    }

    /** True when the class has an entry, used to report what could not be mapped. */
    public boolean knowsClass(String internalName) {
        return classes.containsKey(internalName);
    }

    public String mapMember(String owner, String name, String descriptor) {
        return members.getOrDefault(key(owner, name, descriptor), name);
    }

    public boolean knowsMember(String owner, String name, String descriptor) {
        return members.containsKey(key(owner, name, descriptor));
    }

    public int classCount() {
        return classes.size();
    }

    public int memberCount() {
        return members.size();
    }

    static InputStream openDefault(Path file) throws IOException {
        return Files.newInputStream(file);
    }

    static InputStreamReader reader(InputStream stream) {
        return new InputStreamReader(stream, StandardCharsets.UTF_8);
    }
}
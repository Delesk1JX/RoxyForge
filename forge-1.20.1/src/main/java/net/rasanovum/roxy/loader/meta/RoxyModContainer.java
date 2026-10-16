package net.rasanovum.roxy.loader.meta;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A Fabric mod container for a Forge mod file: the version comes from the jar's own fabric.mod.json. */
public final class RoxyModContainer implements ModContainer {
    private final ModMetadata metadata;
    private final List<Path> roots;

    public RoxyModContainer(String modId, Path source) {
        JsonObject json = readMetadata(source);
        this.metadata = new RoxyModMetadata(versionOf(json), customOf(json));
        this.roots = List.of(source.toAbsolutePath());
    }

    @Override
    public ModMetadata getMetadata() {
        return metadata;
    }

    @Override
    public List<Path> getRootPaths() {
        return roots;
    }

    private static JsonObject readMetadata(Path source) {
        try (ZipFile jar = new ZipFile(source.toFile())) {
            ZipEntry entry = jar.getEntry("fabric.mod.json");
            if (entry == null) {
                return new JsonObject();
            }
            try (InputStreamReader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private static String versionOf(JsonObject json) {
        return json.has("version") ? json.get("version").getAsString() : "0.0.0";
    }

    private static JsonObject customOf(JsonObject json) {
        return json.has("custom") && json.get("custom").isJsonObject()
                ? json.getAsJsonObject("custom")
                : new JsonObject();
    }

    private record RoxyModMetadata(String version, JsonObject custom) implements ModMetadata {
        @Override
        public Version getVersion() {
            return () -> version;
        }

        @Override
        public CustomValue getCustomValue(String key) {
            return custom.has(key) ? new RoxyCustomValue(custom.get(key)) : null;
        }
    }

    private record RoxyCustomValue(JsonElement value) implements CustomValue {
        @Override
        public String getAsString() {
            return value.getAsString();
        }
    }
}
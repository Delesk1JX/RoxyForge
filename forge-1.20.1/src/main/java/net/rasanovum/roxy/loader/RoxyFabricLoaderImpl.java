package net.rasanovum.roxy.loader;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.rasanovum.roxy.loader.meta.RoxyModContainer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The FabricLoader Voxy sees, backed by Forge's own mod list.
 *
 * This is the Forge counterpart of upstream Roxy's loader shim. It is deliberately small: the parts of the
 * Fabric loader that Voxy actually calls are the mod checks, the environment type, the config directory and
 * entrypoint lookup, and everything else it needs (entrypoint execution) RoxyForge already does itself.
 * Upstream reads fabric.mod.json into access transformers and metadata; none of that is needed to get the
 * mod to start, so it is left out until something asks for it.
 */
public final class RoxyFabricLoaderImpl implements FabricLoader {
    public static final RoxyFabricLoaderImpl INSTANCE = new RoxyFabricLoaderImpl();

    private final ConcurrentMap<String, ModContainer> containers = new ConcurrentHashMap<>();

    private RoxyFabricLoaderImpl() {
    }

    /** The mod file for a Fabric mod id, taken from the file name Forge saw. */
    public void register(String modId, Path source) {
        containers.put(modId, new RoxyModContainer(modId, source));
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId) || containers.containsKey(modId);
    }

    @Override
    public EnvType getEnvironmentType() {
        return FMLEnvironment.dist.isClient() ? EnvType.CLIENT : EnvType.SERVER;
    }

    @Override
    public Optional<ModContainer> getModContainer(String modId) {
        return Optional.ofNullable(containers.get(modId));
    }

    @Override
    public <T> List<T> getEntrypoints(String key, Class<T> type) {
        return new ArrayList<>();
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }
}
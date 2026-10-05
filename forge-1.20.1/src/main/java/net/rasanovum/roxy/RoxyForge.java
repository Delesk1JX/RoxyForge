package net.rasanovum.roxy;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;

/**
 * RoxyForge for Minecraft 1.20.1 Forge.
 *
 * The loader itself is not a game-time service: the Forge-ready copy of Voxy is assembled at build time
 * by {@code gradlew prepareVoxy} (remap, shims, bridges and the mod entrypoint) and loaded as a normal mod.
 * That is deliberate - FML 1.20.1 only builds a mod container from classes inside a mod file, so handing
 * it a jar from a dependency locator does not work.
 */
@Mod("roxy")
public final class RoxyForge {
    private static final Logger LOGGER = LogUtils.getLogger();

    public RoxyForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        LOGGER.info("RoxyForge: ready on Minecraft {} (Forge); the Voxy copy is loaded as a separate mod",
                net.minecraftforge.versions.mcp.MCPVersion.getMCVersion());
        if (modBus == null) {
            LOGGER.error("RoxyForge: no mod event bus - the mod event system is not wired up");
        }
    }
}
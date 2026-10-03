package net.rasanovum.roxy;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod("roxy")
public final class RoxyForge {
    private static final Logger LOGGER = LogUtils.getLogger();

    public RoxyForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        LOGGER.info("RoxyForge: booting on Minecraft {} (Forge)", net.minecraftforge.versions.mcp.MCPVersion.getMCVersion());
        if (modBus == null) {
            LOGGER.error("RoxyForge: no mod event bus - the mod event system is not wired up");
        }
    }
}

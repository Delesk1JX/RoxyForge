package net.voxy;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModList;
import net.rasanovum.roxy.loader.RoxyFabricLoaderImpl;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.lang.reflect.Modifier;

/**
 * Forge entrypoint for the Voxy copy. Not Voxy's code and not shipped in Voxy's jar.
 *
 * Voxy is a Fabric mod: its own entrypoints are declared in fabric.mod.json and Forge knows nothing about
 * them, so this class drives them. They are reached by reflection on purpose - their interfaces
 * (net.fabricmc.api.ModInitializer, ClientModInitializer) are Fabric API types that do not exist on Forge,
 * and a compile-time dependency on them would only move the problem.
 */
@Mod("voxy")
public final class Voxy {
    private static final Logger LOGGER = LoggerFactory.getLogger("VoxyForge/voxy");

    private static final String COMMON = "me.cortex.voxy.commonImpl.VoxyCommon";
    private static final String CLIENT = "me.cortex.voxy.client.VoxyClient";

    public Voxy() {
        announce("voxy");
        run(COMMON, "onInitialize");
    }

    /**
     * Voxy asks the Fabric loader about itself, so the loader has to know this mod file before Voxy runs.
     */
    private static void announce(String modId) {
        try {
            Path source = ModList.get().getModFileById(modId).getFile().getFilePath();
            RoxyFabricLoaderImpl.INSTANCE.register(modId, source);
            LOGGER.info("VoxyForge: registered the Fabric loader view of {} at {}", modId, source.getFileName());
        } catch (Throwable failure) {
            LOGGER.error("VoxyForge: could not register {} with the loader: {}", modId, failure.toString());
        }
    }

    @SubscribeEvent
    public void onClientSetup(FMLClientSetupEvent event) {
        run(CLIENT, "onInitializeClient");
    }

    private static void run(String className, String methodName) {
        try {
            Class<?> type = Class.forName(className);
            Method target = type.getMethod(methodName);
            Object receiver = null;
            if (!Modifier.isStatic(target.getModifiers())) {
                receiver = type.getDeclaredConstructor().newInstance();
            }
            target.invoke(receiver);
            LOGGER.info("VoxyForge: {} ran", className);
        } catch (Throwable failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            LOGGER.error("VoxyForge: {} could not start: {}", className, cause.toString());
            for (StackTraceElement frame : cause.getStackTrace()) {
                LOGGER.error("VoxyForge:   at {}", frame);
            }
        }
    }
}
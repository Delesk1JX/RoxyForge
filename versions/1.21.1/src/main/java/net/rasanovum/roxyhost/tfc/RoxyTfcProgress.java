package net.rasanovum.roxyhost.tfc;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.world.BossEvent;
import net.rasanovum.roxy.compat.RoxyVoxyRendererReloadCompat;
import net.rasanovum.roxy.tfc.TfcCompatConfig;
import net.rasanovum.roxy.tfc.TfcVoxyBridge;

public final class RoxyTfcProgress {
    private static final UUID ID = UUID.fromString("eab352d0-4f29-4ade-9832-3a0fa9ee208b");
    private static Object world;
    private static boolean visible;
    private static int ticks;
    private static long hideAt = Long.MAX_VALUE;
    private static boolean reloadQueued;
    private static Object reloadWorld;
    private static Object reloadLevelRenderer;
    private static Object rendererBeforeReload;
    private static boolean queuedEnabled;
    private static final Object RENDERER_UNAVAILABLE = new Object();

    private RoxyTfcProgress() {}

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        pollRendererReload(minecraft);
        if (!TfcCompatConfig.enabled()) {
            RoxyTfcBackfill.disable();
            if (visible) minecraft.gui.getBossOverlay().update(ClientboundBossEventPacket.createRemovePacket(ID));
            visible = false;
            world = minecraft.level;
            return;
        }
        RoxyTfcBackfill.resetWorld(minecraft.level);
        if (world == minecraft.level && ++ticks % 5 != 0) return;
        update(minecraft.level, minecraft.gui.getBossOverlay(), TfcVoxyBridge.refreshProgress(), System.currentTimeMillis());
    }

    private static void pollRendererReload(Minecraft minecraft) {
        boolean requested = TfcVoxyBridge.updateEnabledState(minecraft.level);
        if (reloadQueued) {
            if (minecraft.level != reloadWorld || minecraft.levelRenderer != reloadLevelRenderer) {
                clearReloadState(true);
                return;
            }
            boolean enabledNow = TfcCompatConfig.enabled();
            if (enabledNow != queuedEnabled) {
                Object before = voxyRenderer(reloadLevelRenderer);
                if (before == RENDERER_UNAVAILABLE) return;
                if (RoxyVoxyRendererReloadCompat.deferVoxyReload(reloadLevelRenderer)) {
                    queuedEnabled = enabledNow;
                    rendererBeforeReload = before;
                }
                return;
            }
            Object current = voxyRenderer(reloadLevelRenderer);
            if (current != RENDERER_UNAVAILABLE && current != rendererBeforeReload) clearReloadState(true);
            return;
        }
        if (!requested) return;
        if (minecraft.level == null) {
            TfcVoxyBridge.rendererReloaded();
            return;
        }
        if (minecraft.levelRenderer == null) return;
        Object before = voxyRenderer(minecraft.levelRenderer);
        if (before == RENDERER_UNAVAILABLE) return;
        if (!RoxyVoxyRendererReloadCompat.deferVoxyReload(minecraft.levelRenderer)) return;
        reloadQueued = true;
        reloadWorld = minecraft.level;
        reloadLevelRenderer = minecraft.levelRenderer;
        rendererBeforeReload = before;
        queuedEnabled = TfcCompatConfig.enabled();
    }

    private static Object voxyRenderer(Object levelRenderer) {
        try {
            return levelRenderer.getClass().getMethod("voxy$getRenderSystem").invoke(levelRenderer);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return RENDERER_UNAVAILABLE;
        }
    }

    private static void clearReloadState(boolean completed) {
        reloadQueued = false;
        reloadWorld = null;
        reloadLevelRenderer = null;
        rendererBeforeReload = null;
        queuedEnabled = false;
        if (completed) TfcVoxyBridge.rendererReloaded();
    }

    public static void update(Object currentWorld, net.minecraft.client.gui.components.BossHealthOverlay overlay,
                              long[] progress, long now) {
        if (!TfcCompatConfig.enabled()) {
            overlay.update(ClientboundBossEventPacket.createRemovePacket(ID));
            visible = false;
            world = currentWorld;
            return;
        }
        if (world != currentWorld) {
            overlay.update(ClientboundBossEventPacket.createRemovePacket(ID));
            world = currentWorld;
            visible = false;
            hideAt = Long.MAX_VALUE;
        }
        if (world == null) return;
        boolean active = progress[4] != 0 || RoxyTfcBackfill.pendingCount() > 0;
        if (!active && !visible) return;
        if (active) hideAt = Long.MAX_VALUE;
        else if (hideAt == Long.MAX_VALUE) hideAt = now + 3000;
        if (now >= hideAt) {
            overlay.update(ClientboundBossEventPacket.createRemovePacket(ID));
            visible = false;
            return;
        }
        long total = progress[2] + progress[3];
        Component title = Component.translatable("roxy.tfc.progress", progress[1], total);
        if (RoxyTfcBackfill.pendingCount() > 0) title = title.copy().append(Component.translatable("roxy.tfc.progress.reads", RoxyTfcBackfill.pendingCount()));
        if (progress[6] > 0) title = title.copy().append(Component.translatable("roxy.tfc.progress.meshes", progress[6]));
        if (progress[3] > 0) title = title.copy().append(Component.translatable("roxy.tfc.progress.missing", progress[3]));
        float fraction = total <= 0 ? 0 : Math.min(1, progress[1] / (float) total);
        var event = new LerpingBossEvent(ID, title, fraction,
                progress[3] > 0 ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.GREEN,
                BossEvent.BossBarOverlay.PROGRESS, false, false, false);
        // Update only the local HUD; no boss event is sent to the server or other players.
        overlay.update(ClientboundBossEventPacket.createAddPacket(event));
        visible = true;
    }
}

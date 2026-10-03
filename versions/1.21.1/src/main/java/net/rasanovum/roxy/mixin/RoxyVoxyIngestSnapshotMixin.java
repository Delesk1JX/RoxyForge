package net.rasanovum.roxy.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;


@Pseudo
@Mixin(targets = "me.cortex.voxy.common.world.service.VoxelIngestService", remap = false)
public final class RoxyVoxyIngestSnapshotMixin {
    private static final String INGEST_SECTION_CONSTRUCTOR =
            "Lme/cortex/voxy/common/world/service/VoxelIngestService$IngestSection;" +
                    "<init>(IIILme/cortex/voxy/common/world/WorldEngine;" +
                    "Lnet/minecraft/world/level/chunk/LevelChunkSection;" +
                    "Lnet/minecraft/world/level/chunk/DataLayer;" +
                    "Lnet/minecraft/world/level/chunk/DataLayer;)V";

    @ModifyArg(
            method = {
                    "enqueueIngest(Lme/cortex/voxy/common/world/WorldEngine;Lnet/minecraft/world/level/chunk/LevelChunk;)Z",
                    "rawIngest0(Lme/cortex/voxy/common/world/WorldEngine;Lnet/minecraft/world/level/chunk/LevelChunkSection;IIILnet/minecraft/world/level/chunk/DataLayer;Lnet/minecraft/world/level/chunk/DataLayer;)Z"
            },
            at = @At(
                    value = "INVOKE",
                    target = INGEST_SECTION_CONSTRUCTOR,
                    remap = false
            ),
            index = 4,
            require = 3,
            remap = false
    )
    private static LevelChunkSection roxy$snapshotSection(LevelChunkSection section) {
        return new LevelChunkSection(section.getStates().copy(), copyBiomes(section.getBiomes()));
    }

    @SuppressWarnings("unchecked")
    private static PalettedContainerRO<Holder<Biome>> copyBiomes(PalettedContainerRO<Holder<Biome>> source) {
        if (source instanceof PalettedContainer<?> palette) {
            return ((PalettedContainer<Holder<Biome>>) palette).copy();
        }

        PalettedContainer<Holder<Biome>> copy = source.recreate();
        if (copy == null) {
            throw new IllegalStateException("Unable to snapshot Voxy biome container");
        }
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    copy.set(x, y, z, source.get(x, y, z));
                }
            }
        }
        return copy;
    }
}

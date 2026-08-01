package com.mamiyaotaru.voxelmap.mixins;

import com.mamiyaotaru.voxelmap.chunkanalysis.ChunkAnalysisRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.structures.EndCityPieces;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EndCityPieces.EndCityPiece.class)
public abstract class MixinEndCityPieceChunkAnalysis {
    @Inject(method = "handleDataMarker", at = @At("HEAD"), cancellable = true)
    private void voxelmap$skipEntityMarkers(String markerId, BlockPos position, ServerLevelAccessor level,
                                             RandomSource random, BoundingBox bounds, CallbackInfo ci) {
        if (ChunkAnalysisRuntime.active() && (markerId.startsWith("Sentry") || markerId.startsWith("Elytra"))) {
            ci.cancel();
        }
    }
}

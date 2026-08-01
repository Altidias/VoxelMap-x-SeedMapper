package com.mamiyaotaru.voxelmap.mixins;

import com.mamiyaotaru.voxelmap.chunkanalysis.ChunkAnalysisRuntime;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityType.class)
public abstract class MixinEntityTypeChunkAnalysis<T extends Entity> {
    @Inject(method = "create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnRequest;)Lnet/minecraft/world/entity/Entity;",
            at = @At("HEAD"), cancellable = true)
    private void voxelmap$skipMemoryWorldEntities(Level level, EntitySpawnRequest request, CallbackInfoReturnable<T> cir) {
        if (level == null && ChunkAnalysisRuntime.active()) cir.setReturnValue(null);
    }
}

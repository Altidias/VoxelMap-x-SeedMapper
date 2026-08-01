package com.mamiyaotaru.voxelmap.mixins;

import com.mamiyaotaru.voxelmap.chunkanalysis.ChunkAnalysisRuntime;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.CappedProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.stream.IntStream;

@Mixin(CappedProcessor.class)
public abstract class MixinCappedProcessorChunkAnalysis {
    @Shadow @Final private StructureProcessor delegate;
    @Shadow @Final private IntProvider limit;

    @Inject(method = "finalizeProcessing", at = @At("HEAD"), cancellable = true)
    private void voxelmap$processWithoutServerLevel(ServerLevelAccessor level, BlockPos position, BlockPos referencePos,
                                                     List<StructureTemplate.StructureBlockInfo> original,
                                                     List<StructureTemplate.StructureBlockInfo> processed,
                                                     StructurePlaceSettings settings,
                                                     CallbackInfoReturnable<List<StructureTemplate.StructureBlockInfo>> cir) {
        if (!ChunkAnalysisRuntime.active()) return;
        if (this.limit.maxInclusive() == 0 || processed.isEmpty() || original.size() != processed.size()) {
            cir.setReturnValue(processed);
            return;
        }
        RandomSource random = RandomSource.createThreadLocalInstance(ChunkAnalysisRuntime.seed()).forkPositional().at(position);
        int max = Math.min(this.limit.sample(random), processed.size());
        IntArrayList indices = Util.toShuffledList(IntStream.range(0, processed.size()), random);
        IntIterator iterator = indices.intIterator();
        int replaced = 0;
        while (iterator.hasNext() && replaced < max) {
            int index = iterator.nextInt();
            StructureTemplate.StructureBlockInfo current = processed.get(index);
            StructureTemplate.StructureBlockInfo altered = this.delegate.processBlock(
                    level, position, referencePos, original.get(index).pos(), current, settings);
            if (altered != null && !current.equals(altered)) {
                replaced++;
                processed.set(index, altered);
            }
        }
        cir.setReturnValue(processed);
    }
}

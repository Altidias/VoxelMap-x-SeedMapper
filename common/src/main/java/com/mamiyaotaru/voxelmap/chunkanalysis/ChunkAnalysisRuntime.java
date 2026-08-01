package com.mamiyaotaru.voxelmap.chunkanalysis;

import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/** Thread-local bridge for vanilla features that normally reach back through ServerLevel. */
public final class ChunkAnalysisRuntime {
    private static final ThreadLocal<Context> ACTIVE = new ThreadLocal<>();

    private ChunkAnalysisRuntime() { }

    static void enter(long seed, ChunkGenerator generator, StructureTemplateManager templates) {
        ACTIVE.set(new Context(seed, generator, templates));
    }

    static void exit() { ACTIVE.remove(); }
    public static boolean active() { return ACTIVE.get() != null; }
    public static long seed() { return ACTIVE.get().seed(); }
    public static ChunkGenerator generator() { return ACTIVE.get().generator(); }
    public static StructureTemplateManager templates() { return ACTIVE.get().templates(); }

    private record Context(long seed, ChunkGenerator generator, StructureTemplateManager templates) { }
}

package com.mamiyaotaru.voxelmap.chunkanalysis;

import com.mamiyaotaru.voxelmap.VoxelConstants;
import com.mamiyaotaru.voxelmap.util.AppChatMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkAnalysisService {
    public static final int DEFAULT_RADIUS = 4; // 9x9 chunks
    public static final int MAX_RADIUS = 8;
    private static final int MAX_RENDER_DIFFERENCES_STORED = 100_000;
    private static final int MAX_VOID_CANDIDATES = 250_000;
    private static final long COMPARISON_TICK_BUDGET_NANOS = 6_000_000L;
    private static final ChunkAnalysisService INSTANCE = new ChunkAnalysisService();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "VoxelMap ChunkAnalysis");
            thread.setDaemon(true);
            return thread;
        }
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong scanEpoch = new AtomicLong();
    private volatile CompletableFuture<ChunkAnalysisWorldgenContext> context;
    private volatile ChunkAnalysisSnapshot snapshot = ChunkAnalysisSnapshot.empty();
    private volatile ComparisonJob comparisonJob;
    private volatile String status = "idle";
    private volatile long overlayExpiresAtMillis;

    private ChunkAnalysisService() { }

    public static ChunkAnalysisService get() { return INSTANCE; }
    public ChunkAnalysisSnapshot snapshot() { return snapshot; }
    public boolean isRunning() { return running.get(); }
    public String status() { return status; }

    public boolean scan(int radius) {
        return scan(radius, ScanMode.FULL);
    }

    public boolean scanVoids(int radius) {
        return scan(radius, ScanMode.VOIDS_ONLY);
    }

    private boolean scan(int radius, ScanMode mode) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            message("ChunkAnalysis requires an active world.");
            return false;
        }
        if (!running.compareAndSet(false, true)) {
            message("ChunkAnalysis is already scanning.");
            return false;
        }

        int checkedRadius = Math.max(0, Math.min(MAX_RADIUS, radius));
        long runId = scanEpoch.incrementAndGet();
        overlayExpiresAtMillis = 0L;
        long seed;
        try {
            seed = VoxelConstants.getVoxelMapInstance().getSeedMapperOptions()
                    .resolveSeed(VoxelConstants.getVoxelMapInstance().getWorldSeed());
        } catch (IllegalArgumentException exception) {
            running.set(false);
            message("ChunkAnalysis needs the world seed. Use /seedmap seed <seed> first.");
            return false;
        }

        ChunkPos center = minecraft.player.chunkPosition();
        ResourceKey<Level> dimension = level.dimension();
        status = "loading vanilla worldgen";
        message("ChunkAnalysis: preparing " + (mode == ScanMode.VOIDS_ONLY ? "fast terrain/carver baseline" : "vanilla worldgen")
                + " for " + (checkedRadius * 2 + 1) + "x" + (checkedRadius * 2 + 1) + " chunks...");

        CompletableFuture<ChunkAnalysisWorldgenContext> loaded = context;
        if (loaded == null) {
            synchronized (this) {
                loaded = context;
                if (loaded == null) context = loaded = ChunkAnalysisWorldgenContext.load(executor);
            }
        }

        long started = System.nanoTime();
        loaded.thenApplyAsync(worldgen -> {
            status = "generating memory chunks";
            return worldgen.generate(seed, dimension, center, checkedRadius, mode == ScanMode.FULL);
        }, executor).thenAccept(area -> minecraft.execute(() -> {
            if (runId != scanEpoch.get() || minecraft.level != level) return;
            status = "comparing loaded chunks (0%)";
            comparisonJob = new ComparisonJob(level, seed, dimension, center, checkedRadius, area, started, mode);
        })).exceptionally(failure -> {
            if (runId != scanEpoch.get()) return null;
            VoxelConstants.getLogger().error("ChunkAnalysis scan failed", failure);
            status = "failed: " + rootMessage(failure);
            running.set(false);
            minecraft.execute(() -> message("ChunkAnalysis failed: " + rootMessage(failure)));
            return null;
        });
        return true;
    }

    public void clear() {
        snapshot = ChunkAnalysisSnapshot.empty();
        overlayExpiresAtMillis = 0L;
        if (!running.get()) status = "idle";
    }

    public void cancel() {
        scanEpoch.incrementAndGet();
        comparisonJob = null;
        running.set(false);
        status = "idle";
    }

    /** Called on the client tick; compares chunks until the small frame-time budget is exhausted. */
    public void tick() {
        long expiresAt = overlayExpiresAtMillis;
        if (expiresAt != 0L && System.currentTimeMillis() >= expiresAt) {
            clear();
        }
        ComparisonJob job = comparisonJob;
        if (job == null) return;
        if (Minecraft.getInstance().level != job.level) {
            comparisonJob = null;
            running.set(false);
            status = "cancelled: world changed";
            return;
        }
        long deadline = System.nanoTime() + COMPARISON_TICK_BUDGET_NANOS;
        boolean complete;
        do {
            complete = job.compareNextChunk();
        } while (!complete && System.nanoTime() < deadline);
        if (complete) {
            snapshot = job.finish();
            ChunkAnalysisSettingsManager settings = VoxelConstants.getVoxelMapInstance().getChunkAnalysisOptions();
            int autoClearMinutes = settings == null ? 3 : settings.autoClearMinutes;
            overlayExpiresAtMillis = System.currentTimeMillis() + autoClearMinutes * 60_000L;
            comparisonJob = null;
            status = "complete";
            running.set(false);
            message(summary(snapshot));
        } else {
            status = "comparing loaded chunks (" + job.percent() + "%)";
        }
    }

    public static String summary(ChunkAnalysisSnapshot value) {
        long total = value.missingCount() + value.unexpectedCount() + value.changedCount();
        ChunkAnalysisSettingsManager settings = VoxelConstants.getVoxelMapInstance().getChunkAnalysisOptions();
        int visibleLimit = settings == null ? value.differences().size() : settings.renderLimit;
        String renderNote = total > Math.min(value.differences().size(), visibleLimit)
                ? "; overlay sampled to protect frame time"
                : "";
        return "ChunkAnalysis complete in " + value.generationMillis() + "ms: "
                + value.count(ChunkAnalysisDifference.Kind.MISSING_EXPECTED) + " missing (red), "
                + value.count(ChunkAnalysisDifference.Kind.UNEXPECTED) + " unexpected (blue), "
                + value.count(ChunkAnalysisDifference.Kind.CHANGED) + " changed (yellow)"
                + (value.excavationCount() == 0 ? "" : "; " + value.excavationCount() + " player-shaped voids ("
                + value.excavationBlockCount() + " blocks, deep red)")
                + (value.skippedChunks() == 0 ? "" : "; " + value.skippedChunks() + " unloaded chunks skipped")
                + (value.unreliableChunks() == 0 ? "" : "; " + value.unreliableChunks() + " baseline-incompatible chunks filtered")
                + renderNote + ".";
    }

    public void shutdown() {
        cancel();
        CompletableFuture<ChunkAnalysisWorldgenContext> loaded = context;
        if (loaded != null && loaded.isDone() && !loaded.isCompletedExceptionally()) {
            try { loaded.join().close(); } catch (RuntimeException ignored) { }
        }
        executor.shutdownNow();
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static void message(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui != null) minecraft.gui.hud.getChat()
                .addClientSystemMessage(AppChatMessages.prefixed("ChunkAnalysis", text));
    }

    private static final class ComparisonJob {
        private final ClientLevel level;
        private final long seed;
        private final ResourceKey<Level> dimension;
        private final ChunkPos center;
        private final int radius;
        private final ChunkAnalysisWorldgenContext.GeneratedArea area;
        private final long started;
        private final ScanMode mode;
        private final boolean highConfidenceOnly;
        private final boolean voidScan;
        private final int width;
        private final int totalChunks;
        private final List<ChunkAnalysisDifference> differences = new ArrayList<>();
        private final List<ChunkAnalysisVoidDetector.Candidate> voidCandidates = new ArrayList<>();
        private int nextChunk;
        private int compared;
        private int skipped;
        private int unreliable;
        private long missing;
        private long unexpected;
        private long changed;
        private long excavationBlocks;
        private int excavations;

        private ComparisonJob(ClientLevel level, long seed, ResourceKey<Level> dimension, ChunkPos center,
                              int radius, ChunkAnalysisWorldgenContext.GeneratedArea area, long started, ScanMode mode) {
            this.level = level;
            this.seed = seed;
            this.dimension = dimension;
            this.center = center;
            this.radius = radius;
            this.area = area;
            this.started = started;
            this.mode = mode;
            ChunkAnalysisSettingsManager settings = VoxelConstants.getVoxelMapInstance().getChunkAnalysisOptions();
            this.highConfidenceOnly = settings == null || settings.highConfidenceOnly;
            this.voidScan = mode == ScanMode.VOIDS_ONLY;
            this.width = radius * 2 + 1;
            this.totalChunks = width * width;
        }

        private boolean compareNextChunk() {
            if (nextChunk >= totalChunks) return true;
            int chunkX = center.x() - radius + nextChunk % width;
            int chunkZ = center.z() - radius + nextChunk / width;
            nextChunk++;
            LevelChunk actualChunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (actualChunk == null) {
                skipped++;
                return nextChunk >= totalChunks;
            }
            ProtoChunk expectedChunk = area.get(new ChunkPos(chunkX, chunkZ));
            if (highConfidenceOnly && !biomeBaselineMatches(expectedChunk, actualChunk)) {
                unreliable++;
                return nextChunk >= totalChunks;
            }
            int chunkStorageStart = differences.size();
            int chunkStorageLimit = Math.max(1, MAX_RENDER_DIFFERENCES_STORED / totalChunks);
            int chunkDifferenceCount = 0;
            long chunkAcceptedMissing = 0;
            int rawMissingTerrain = 0;
            int rawUnexpectedTerrain = 0;
            List<ChunkAnalysisVoidDetector.Candidate> chunkVoidCandidates = voidScan ? new ArrayList<>() : List.of();
            RandomSource sampleRandom = RandomSource.create(ChunkPos.pack(chunkX, chunkZ) ^ seed);
            int minX = chunkX << 4;
            int minZ = chunkZ << 4;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = area.minY(); y < area.minY() + area.height(); y++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        pos.set(minX + localX, y, minZ + localZ);
                        BlockState expected = expectedChunk.getBlockState(pos);
                        BlockState actual = actualChunk.getBlockState(pos);
                        compared++;
                        // Neighbor updates can legitimately change properties such as stair shape,
                        // connections, age, or waterlogging after generation. The seed establishes
                        // the block type; exact live BlockState identity is not evidence of editing.
                        if (expected.getBlock() == actual.getBlock()) continue;
                        // Structure processors frequently carve or replace blocks without passing
                        // through the feature-write hook used by the memory level. The seed still
                        // tells us the structure piece footprint, so conservatively exclude it.
                        if (highConfidenceOnly && area.isInsideStructurePiece(pos)) continue;
                        boolean featureWritten = area.wasFeatureWritten(pos);
                        if (!featureWritten && !expected.isAir() && actual.isAir() && isNaturalTerrain(expected)) {
                            rawMissingTerrain++;
                            if (voidScan && voidCandidates.size() + chunkVoidCandidates.size() < MAX_VOID_CANDIDATES) {
                                chunkVoidCandidates.add(new ChunkAnalysisVoidDetector.Candidate(pos.immutable(), expected, false));
                            }
                        } else if (!featureWritten && expected.isAir() && !actual.isAir() && isNaturalTerrain(actual)) {
                            rawUnexpectedTerrain++;
                        }
                        if (mode == ScanMode.VOIDS_ONLY) continue;
                        if (!isSeedComparable(expected, actual, expectedChunk, pos, dimension,
                                featureWritten, highConfidenceOnly)) continue;
                        chunkDifferenceCount++;
                        ChunkAnalysisDifference.Kind kind;
                        if (!expected.isAir() && actual.isAir()) {
                            kind = ChunkAnalysisDifference.Kind.MISSING_EXPECTED;
                            missing++;
                            chunkAcceptedMissing++;
                        } else if (expected.isAir() && !actual.isAir()) {
                            kind = ChunkAnalysisDifference.Kind.UNEXPECTED;
                            unexpected++;
                        } else {
                            kind = ChunkAnalysisDifference.Kind.CHANGED;
                            changed++;
                        }
                        BlockState displayState = kind == ChunkAnalysisDifference.Kind.UNEXPECTED ? null : expected;
                        ChunkAnalysisDifference difference = new ChunkAnalysisDifference(pos.immutable(), kind, displayState);
                        if (chunkDifferenceCount <= chunkStorageLimit) {
                            differences.add(difference);
                        } else {
                            int replacement = sampleRandom.nextInt(chunkDifferenceCount);
                            if (replacement < chunkStorageLimit) differences.set(chunkStorageStart + replacement, difference);
                        }
                    }
                }
            }
            if (highConfidenceOnly && incompatibleCaveBaseline(rawMissingTerrain, rawUnexpectedTerrain)) {
                unreliable++;
                missing -= chunkAcceptedMissing;
                differences.subList(chunkStorageStart, differences.size())
                        .removeIf(difference -> difference.kind() == ChunkAnalysisDifference.Kind.MISSING_EXPECTED);
                if (voidScan) {
                    chunkVoidCandidates.forEach(candidate -> voidCandidates.add(
                            new ChunkAnalysisVoidDetector.Candidate(candidate.pos(), candidate.expectedState(), true)));
                }
            } else if (voidScan) {
                voidCandidates.addAll(chunkVoidCandidates);
            }
            return nextChunk >= totalChunks;
        }

        private static boolean incompatibleCaveBaseline(int missingTerrain, int unexpectedTerrain) {
            if (missingTerrain < 24 || unexpectedTerrain < 24) return false;
            int smaller = Math.min(missingTerrain, unexpectedTerrain);
            int larger = Math.max(missingTerrain, unexpectedTerrain);
            return smaller * 4 >= larger;
        }

        private boolean biomeBaselineMatches(ProtoChunk expected, LevelChunk actual) {
            int minQuartY = QuartPos.fromBlock(area.minY());
            int maxQuartY = QuartPos.fromBlock(area.minY() + area.height() - 1);
            for (int quartY = minQuartY; quartY <= maxQuartY; quartY++) {
                for (int quartZ = 0; quartZ < 4; quartZ++) {
                    for (int quartX = 0; quartX < 4; quartX++) {
                        if (!expected.getNoiseBiome(quartX, quartY, quartZ).unwrapKey()
                                .equals(actual.getNoiseBiome(quartX, quartY, quartZ).unwrapKey())) return false;
                    }
                }
            }
            return true;
        }

        /**
         * Only compare states whose present-day value remains a defensible seed-derived baseline.
         * Fluids and random-ticking blocks evolve through ordinary simulation without player input.
         */
        private static boolean isSeedComparable(BlockState expected, BlockState actual, ProtoChunk expectedChunk,
                                                BlockPos pos, ResourceKey<Level> dimension,
                                                boolean featureWritten, boolean highConfidenceOnly) {
            if (!expected.getFluidState().isEmpty() || !actual.getFluidState().isEmpty()) return false;
            if (!highConfidenceOnly) return true;

            // Missing volume is the strongest signal. A grass block may naturally become dirt,
            // but it cannot naturally become air; do not discard surface excavation merely
            // because the expected block has random ticks.
            if (actual.isAir()) return !featureWritten && !isNaturalDecoration(expected)
                    && !isUnstableNaturalFeature(expected, dimension);

            if (expected.isAir()) {
                if (actual.isRandomlyTicking() || isNaturalDecoration(actual)) return false;
                if (!isNaturalWorldgenBlock(actual, dimension)) return true;
                if (featureWritten) return false;
                // A seed-sensitive cave edge or feature can leave a natural block where this
                // baseline produced air. Natural blocks are accepted only at the expected
                // surface, which retains deliberate dirt/scaffolding shapes without cave noise.
                int expectedSurface = expectedChunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG,
                        pos.getX() & 15, pos.getZ() & 15);
                return isLooseSurfaceMaterial(actual) && pos.getY() >= expectedSurface - 1;
            }

            if (expected.isRandomlyTicking() || actual.isRandomlyTicking()
                    || isNaturalDecoration(expected) || isNaturalDecoration(actual)
                    || isUnstableNaturalFeature(expected, dimension)
                    || isUnstableNaturalFeature(actual, dimension)) return false;

            // A block which cannot naturally generate in this dimension is evidence regardless
            // of whether the expected position happened to be touched by a vanilla feature.
            if (!isNaturalWorldgenBlock(actual, dimension)) return true;
            return actual.is(BlockTags.LOGS) && isNaturalTerrain(expected) && !featureWritten;
        }

        private static boolean isNaturalWorldgenBlock(BlockState state, ResourceKey<Level> dimension) {
            if (isNaturalTerrain(state) || isNaturalDecoration(state)
                    || isUnstableNaturalFeature(state, dimension)) return true;
            Block block = state.getBlock();
            if (dimension == Level.OVERWORLD) {
                return state.is(BlockTags.LOGS)
                        || block == Blocks.AMETHYST_BLOCK
                        || block == Blocks.BUDDING_AMETHYST
                        || block == Blocks.AMETHYST_CLUSTER
                        || block == Blocks.LARGE_AMETHYST_BUD
                        || block == Blocks.MEDIUM_AMETHYST_BUD
                        || block == Blocks.SMALL_AMETHYST_BUD
                        || block == Blocks.DRIPSTONE_BLOCK
                        || block == Blocks.POINTED_DRIPSTONE;
            }
            if (dimension == Level.NETHER) {
                return state.is(BlockTags.LOGS)
                        || block == Blocks.GLOWSTONE
                        || block == Blocks.SHROOMLIGHT;
            }
            return false;
        }

        /**
         * Feature blocks whose exact footprint cannot safely be attributed to the seed alone.
         * Sculk patches are feature-stage output and can subsequently spread from catalysts, so
         * neither their presence nor absence is reliable evidence of player modification.
         */
        private static boolean isUnstableNaturalFeature(BlockState state, ResourceKey<Level> dimension) {
            if (dimension != Level.OVERWORLD) return false;
            Block block = state.getBlock();
            return block == Blocks.SCULK || block == Blocks.SCULK_VEIN
                    || block == Blocks.SCULK_CATALYST || block == Blocks.SCULK_SENSOR
                    || block == Blocks.SCULK_SHRIEKER;
        }

        private static boolean isLooseSurfaceMaterial(BlockState state) {
            Block block = state.getBlock();
            return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                    || block == Blocks.GRAVEL || block == Blocks.CLAY;
        }

        private static boolean isNaturalDecoration(BlockState state) {
            if (state.isAir()) return false;
            return state.canBeReplaced()
                    || state.is(BlockTags.FLOWERS)
                    || state.is(BlockTags.LEAVES)
                    || state.is(BlockTags.CROPS)
                    || state.is(BlockTags.CAVE_VINES)
                    || state.is(BlockTags.CORALS)
                    || state.is(BlockTags.SNOW)
                    || state.getBlock() == Blocks.AMETHYST_CLUSTER
                    || state.getBlock() == Blocks.LARGE_AMETHYST_BUD
                    || state.getBlock() == Blocks.MEDIUM_AMETHYST_BUD
                    || state.getBlock() == Blocks.SMALL_AMETHYST_BUD;
        }

        private static boolean isNaturalTerrain(BlockState state) {
            if (state.isAir()) return false;
            if (state.is(BlockTags.BASE_STONE_OVERWORLD)
                    || state.is(BlockTags.BASE_STONE_NETHER)
                    || state.is(BlockTags.DIRT)
                    || state.is(BlockTags.SAND)
                    || state.is(BlockTags.TERRACOTTA)
                    || state.is(BlockTags.BADLANDS_TERRACOTTA)
                    || state.is(BlockTags.NYLIUM)
                    || state.is(BlockTags.MOSS_BLOCKS)
                    || state.is(BlockTags.ICE)) return true;

            Block block = state.getBlock();
            if (block == Blocks.GRAVEL || block == Blocks.CLAY || block == Blocks.TUFF
                    || block == Blocks.CALCITE || block == Blocks.END_STONE
                    || block == Blocks.SOUL_SAND || block == Blocks.SOUL_SOIL
                    || block == Blocks.BASALT || block == Blocks.SMOOTH_BASALT
                    || block == Blocks.BLACKSTONE || block == Blocks.MAGMA_BLOCK
                    || block == Blocks.ANCIENT_DEBRIS || block == Blocks.RAW_COPPER_BLOCK
                    || block == Blocks.RAW_IRON_BLOCK || block == Blocks.AMETHYST_BLOCK
                    || block == Blocks.BUDDING_AMETHYST) return true;

            String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
            return path.endsWith("_ore");
        }

        private int percent() { return nextChunk * 100 / totalChunks; }

        private ChunkAnalysisSnapshot finish() {
            if (voidScan) applyVoidDetection();
            if (mode == ScanMode.VOIDS_ONLY) missing = excavationBlocks;
            return new ChunkAnalysisSnapshot(seed, dimension, center, radius, List.copyOf(differences),
                    missing, unexpected, changed, excavationBlocks, excavations, compared, skipped, unreliable,
                    (System.nanoTime() - started) / 1_000_000L);
        }

        private void applyVoidDetection() {
            ChunkAnalysisVoidDetector.Result result = ChunkAnalysisVoidDetector.detect(voidCandidates);
            if (result.blocks().isEmpty()) return;
            java.util.Set<Long> positions = new java.util.HashSet<>(result.blocks().size() * 2);
            result.blocks().forEach(candidate -> positions.add(candidate.pos().asLong()));
            for (int index = 0; index < differences.size(); index++) {
                ChunkAnalysisDifference difference = differences.get(index);
                if (difference.kind() == ChunkAnalysisDifference.Kind.MISSING_EXPECTED
                        && positions.remove(difference.pos().asLong())) {
                    differences.set(index, new ChunkAnalysisDifference(difference.pos(),
                            ChunkAnalysisDifference.Kind.EXCAVATION, difference.displayState()));
                }
            }
            for (ChunkAnalysisVoidDetector.Candidate candidate : result.blocks()) {
                if (differences.size() >= MAX_RENDER_DIFFERENCES_STORED) break;
                if (positions.remove(candidate.pos().asLong())) {
                    differences.add(new ChunkAnalysisDifference(candidate.pos(),
                            ChunkAnalysisDifference.Kind.EXCAVATION, candidate.expectedState()));
                }
            }
            excavationBlocks = result.blocks().size();
            excavations = result.components();
        }
    }

    private enum ScanMode {
        FULL,
        VOIDS_ONLY
    }
}

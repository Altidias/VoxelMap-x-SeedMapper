package com.mamiyaotaru.voxelmap.seedmapper;

import com.github.cubiomes.Cubiomes;
import com.mamiyaotaru.voxelmap.VoxelConstants;
import com.mamiyaotaru.voxelmap.util.GameVariableAccessShim;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Updates Elytra ship markers from item frames in ships the player has actually loaded. */
public final class SeedMapperElytraDetection {
    private static long nextScan;

    private SeedMapperElytraDetection() {
    }

    public static void tick() {
        SeedMapperSettingsManager settings = VoxelConstants.getVoxelMapInstance().getSeedMapperOptions();
        Minecraft minecraft = VoxelConstants.getMinecraft();
        if (!settings.elytraDetection || minecraft.level == null || minecraft.player == null) {
            return;
        }
        long now = VoxelConstants.getElapsedTicks();
        if (now < nextScan) {
            return;
        }
        nextScan = now + 10;

        if (getDimension() != Cubiomes.DIM_END()) {
            return;
        }

        int playerX = GameVariableAccessShim.xCoord();
        int playerZ = GameVariableAccessShim.zCoord();
        long seed;
        try {
            seed = settings.resolveSeed(VoxelConstants.getVoxelMapInstance().getWorldSeed());
        } catch (IllegalArgumentException ignored) {
            return;
        }

        boolean oldLootOnly = settings.showLootableOnly;
        java.util.List<SeedMapperMarker> nearbyMarkers;
        try {
            // Elytra is intentionally not a lootable feature, so detection must still
            // locate ships when the normal marker view is filtered to lootable items.
            settings.showLootableOnly = false;
            nearbyMarkers = SeedMapperLocatorService.get().queryBlocking(
                    seed, Cubiomes.DIM_END(), SeedMapperCompat.getMcVersion(), 0,
                    playerX - 128, playerX + 128, playerZ - 128, playerZ + 128, settings);
        } finally {
            settings.showLootableOnly = oldLootOnly;
        }

        for (SeedMapperMarker marker : nearbyMarkers) {
            if (marker.feature() != SeedMapperFeature.ELYTRA) {
                continue;
            }
            double dx = minecraft.player.getX() - marker.blockX();
            double dz = minecraft.player.getZ() - marker.blockZ();
            if (dx * dx + dz * dz > 96.0D * 96.0D) {
                continue;
            }

            // A loaded frame is proof that this ship has been visited. Empty frames
            // are deliberately treated as missing so taking the Elytra updates live.
            AABB shipBounds = new AABB(marker.blockX() - 32, 0, marker.blockZ() - 32,
                    marker.blockX() + 32, 256, marker.blockZ() + 32);
            boolean sawFrame = false;
            boolean hasElytra = false;
            for (ItemFrame frame : minecraft.level.getEntitiesOfClass(ItemFrame.class, shipBounds)) {
                sawFrame = true;
                if (frame.getItem().is(Items.ELYTRA)) {
                    hasElytra = true;
                    break;
                }
            }
            if (sawFrame) {
                settings.setElytraMissing(currentWorldKey(), marker.blockX(), marker.blockZ(), !hasElytra);
            }
        }
    }

    private static int getDimension() {
        Minecraft minecraft = VoxelConstants.getMinecraft();
        return minecraft.level != null && minecraft.level.dimension() == Level.END
                ? Cubiomes.DIM_END() : Integer.MIN_VALUE;
    }

    private static String currentWorldKey() {
        var waypointManager = VoxelConstants.getVoxelMapInstance().getWaypointManager();
        String world = waypointManager.getCurrentWorldName();
        String sub = waypointManager.getCurrentSubworldDescriptor(false);
        return (world == null ? "unknown" : world) + "|" + (sub == null ? "" : sub) + "|minecraft:the_end";
    }
}

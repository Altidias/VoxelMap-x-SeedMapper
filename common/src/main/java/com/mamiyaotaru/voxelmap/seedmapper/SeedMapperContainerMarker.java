package com.mamiyaotaru.voxelmap.seedmapper;

import net.minecraft.resources.Identifier;

public record SeedMapperContainerMarker(int blockX, int blockZ, Identifier texture, String label, SeedMapperMarkerOption option) {
}

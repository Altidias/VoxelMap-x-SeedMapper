package com.mamiyaotaru.voxelmap.chunksync;

public record PeerPresence(String name, String dimension, double x, double y, double z, float yaw, long ageMs) {
}

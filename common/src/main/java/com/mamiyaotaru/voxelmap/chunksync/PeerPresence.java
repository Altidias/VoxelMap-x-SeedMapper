package com.mamiyaotaru.voxelmap.chunksync;

// account is the cartobase login; playerName is the minecraft character, which is
// what resolves a skin and what other players recognise
public record PeerPresence(String account, String playerName, String dimension, double x, double y, double z,
                           float yaw, long ageMs, boolean ownAccount) {
    public String label() {
        return playerName == null || playerName.isBlank() ? account : playerName;
    }
}

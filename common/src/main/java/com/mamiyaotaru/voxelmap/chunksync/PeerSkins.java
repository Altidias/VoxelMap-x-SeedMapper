package com.mamiyaotaru.voxelmap.chunksync;

import com.mamiyaotaru.voxelmap.VoxelConstants;
import com.mamiyaotaru.voxelmap.util.ImageUtils;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class PeerSkins {
    private record Cached(Identifier location, Identifier sourceSkin) {
    }

    private static final Map<String, Cached> cache = new HashMap<>();

    private PeerSkins() {
    }

    public static Identifier headFor(PeerPresence member) {
        GameProfile profile = profileFor(member.label());
        if (profile == null) {
            return null;
        }
        Optional<PlayerSkin> skin = Minecraft.getInstance().getSkinManager().get(profile).getNow(Optional.empty());
        if (skin.isEmpty()) {
            return null;
        }
        Identifier skinTexture = skin.get().body().texturePath();
        String slug = ChunkShareService.slugFor(member.label()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
        Cached cached = cache.get(slug);
        if (cached != null && cached.sourceSkin().equals(skinTexture)) {
            return cached.location();
        }
        Identifier location = build(slug, skinTexture);
        if (location != null) {
            cache.put(slug, new Cached(location, skinTexture));
        }
        return location;
    }

    private static Identifier build(String slug, Identifier skinTexture) {
        BufferedImage skinImage = ImageUtils.createBufferedImageFromIdentifier(skinTexture);
        if (skinImage == null) {
            return null;
        }
        BufferedImage head = ImageUtils.addImages(
                ImageUtils.loadImage(skinImage, 8, 8, 8, 8),
                ImageUtils.loadImage(skinImage, 40, 8, 8, 8),
                0.0F, 0.0F, 8, 8);
        float scale = head.getWidth() / 8.0F;
        head = ImageUtils.fillOutline(ImageUtils.pad(ImageUtils.scaleImage(head, 2.0F / scale)), true, 1);
        Identifier location = Identifier.fromNamespaceAndPath(VoxelConstants.MOD_ID, "peer_head/" + slug);
        DynamicTexture texture = new DynamicTexture(() -> "Voxelmap peer " + slug, ImageUtils.nativeImageFromBufferedImage(head));
        texture.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        return location;
    }

    private static GameProfile profileFor(String name) {
        var player = VoxelConstants.getPlayer();
        if (player == null || player.connection == null) {
            return null;
        }
        for (PlayerInfo info : player.connection.getOnlinePlayers()) {
            GameProfile profile = info.getProfile();
            if (profile != null && profile.name() != null && profile.name().equalsIgnoreCase(name)) {
                return profile;
            }
        }
        return null;
    }
}

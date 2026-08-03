package com.mamiyaotaru.voxelmap.chunksync;

import com.mamiyaotaru.voxelmap.VoxelConstants;
import com.mamiyaotaru.voxelmap.chunksync.ChunkShareTransport.Host;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ChunkShareConfig {
    private static final String FILE_NAME = "share.properties";
    private static final String KEY_PASSPHRASE = "passphrase";
    private static final String KEY_HOST = "host";
    private static final String KEY_HIDE_PUBLIC_SHARE_WARNING = "hidePublicShareWarning";
    private static final String KEY_CARTOBASE_URL = "cartobaseUrl";
    private static final String KEY_CARTOBASE_AUTH_URL = "cartobaseAuthUrl";
    private static final String KEY_CARTOBASE_SESSION = "cartobaseSession";
    private static final String KEY_CARTOBASE_USERNAME = "cartobaseUsername";
    private static final String KEY_CARTOBASE_ENABLED = "cartobaseEnabled";
    private static final String KEY_CARTOBASE_UPLOADED_PREFIX = "cartobaseUploaded.";

    private ChunkShareConfig() {
    }

    public static Path baseDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("voxelmap").resolve("chunk_share");
    }

    private static Path configFile() {
        return baseDir().resolve(FILE_NAME);
    }

    private static Properties load() {
        Properties props = new Properties();
        Path file = configFile();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                VoxelConstants.getLogger().warn("Failed to read chunk-share config", e);
            }
        }
        return props;
    }

    private static void save(Properties props) {
        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "VoxelMap chunk-share settings (passphrase is stored in plaintext)");
            }
        } catch (IOException e) {
            VoxelConstants.getLogger().warn("Failed to write chunk-share config", e);
        }
    }

    public static String getPassphrase() {
        String value = load().getProperty(KEY_PASSPHRASE);
        return value == null || value.isEmpty() ? null : value;
    }

    public static void setPassphrase(String passphrase) {
        Properties props = load();
        props.setProperty(KEY_PASSPHRASE, passphrase == null ? "" : passphrase);
        save(props);
    }

    /** the chosen host, defaulting to {@link Host#LITTERBOX}. */
    public static Host getHost() {
        return Host.fromId(load().getProperty(KEY_HOST));
    }

    public static void setHost(Host host) {
        Properties props = load();
        props.setProperty(KEY_HOST, host.id);
        save(props);
    }

    public static String getCartobaseUrl() {
        String value = load().getProperty(KEY_CARTOBASE_URL);
        return value == null || value.isEmpty() ? null : normalizeUrl(value);
    }

    public static void setCartobaseUrl(String url) {
        Properties props = load();
        props.setProperty(KEY_CARTOBASE_URL, url == null ? "" : normalizeUrl(url));
        save(props);
    }

    public static String getCartobaseAuthUrl() {
        String value = load().getProperty(KEY_CARTOBASE_AUTH_URL);
        return value == null || value.isEmpty() ? null : normalizeUrl(value);
    }

    public static void setCartobaseAuthUrl(String url) {
        Properties props = load();
        props.setProperty(KEY_CARTOBASE_AUTH_URL, url == null ? "" : normalizeUrl(url));
        save(props);
    }

    public static String getCartobaseSession() {
        String value = load().getProperty(KEY_CARTOBASE_SESSION);
        return value == null || value.isEmpty() ? null : value;
    }

    public static void setCartobaseSession(String session) {
        Properties props = load();
        props.setProperty(KEY_CARTOBASE_SESSION, session == null ? "" : session.trim());
        save(props);
    }

    public static String getCartobaseUsername() {
        String value = load().getProperty(KEY_CARTOBASE_USERNAME);
        return value == null || value.isEmpty() ? null : value;
    }

    public static void setCartobaseUsername(String username) {
        Properties props = load();
        props.setProperty(KEY_CARTOBASE_USERNAME, username == null ? "" : username.trim());
        save(props);
    }

    public static boolean isCartobaseEnabled() {
        return Boolean.parseBoolean(load().getProperty(KEY_CARTOBASE_ENABLED, "false"));
    }

    public static void setCartobaseEnabled(boolean enabled) {
        Properties props = load();
        props.setProperty(KEY_CARTOBASE_ENABLED, Boolean.toString(enabled));
        save(props);
    }

    // tracks worlds whose existing on-disk chunks have already been uploaded once
    public static boolean isCartobaseUploaded(String worldKey) {
        return Boolean.parseBoolean(load().getProperty(KEY_CARTOBASE_UPLOADED_PREFIX + worldKey, "false"));
    }

    public static void setCartobaseUploaded(String worldKey, boolean uploaded) {
        Properties props = load();
        if (uploaded) {
            props.setProperty(KEY_CARTOBASE_UPLOADED_PREFIX + worldKey, "true");
        } else {
            props.remove(KEY_CARTOBASE_UPLOADED_PREFIX + worldKey);
        }
        save(props);
    }

    private static String normalizeUrl(String url) {
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public static boolean isPublicShareWarningHidden() {
        return Boolean.parseBoolean(load().getProperty(KEY_HIDE_PUBLIC_SHARE_WARNING, "false"));
    }

    public static void setPublicShareWarningHidden(boolean hidden) {
        Properties props = load();
        props.setProperty(KEY_HIDE_PUBLIC_SHARE_WARNING, Boolean.toString(hidden));
        save(props);
    }
}

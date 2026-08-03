package com.mamiyaotaru.voxelmap.chunksync;

import com.mamiyaotaru.voxelmap.VoxelConstants;
import com.mamiyaotaru.voxelmap.util.AppChatMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

public final class ChunkSyncCommandHandler {
    private static final String USAGE = "Usage: /chunksync share [to <player>] | get <code> [as <player>] | key <pass> "
            + "| host <litterbox|file.io> | export [name] | import [name] [as <player>] | players | remove <player> "
            + "| cartobase <status | url <url> | login <user> <pass> | logout | on | off | share <user> | accept <user> | revoke <user> | peers>";
    private static Consumer<String> statusSink;

    private ChunkSyncCommandHandler() {
    }

    public static boolean handleChatCommand(String rawCommand) {
        handle(rawCommand.trim().split("\\s+"));
        return true;
    }

    public static void runFromGui(String arguments) {
        handle(("chunksync " + (arguments == null ? "" : arguments.trim())).trim().split("\\s+"));
    }

    public static void setStatusSink(Consumer<String> sink) {
        statusSink = sink;
    }

    private static void handle(String[] args) {
        if (args.length < 2) {
            sendChunkSync(USAGE);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "export" -> {
                Path dir = chunkShareBundle(args.length >= 3 ? args[2] : null);
                try {
                    int n = ChunkShareService.exportBundle(dir, playerName());
                    sendChunkSync("Exported " + n + " chunks from this game to " + dir);
                } catch (IOException e) {
                    sendChunkSync("Chunk export failed: " + e.getMessage());
                }
            }
            case "import" -> {
                String name = null;
                String asPlayer = null;
                for (int i = 2; i < args.length; i++) {
                    if (args[i].equalsIgnoreCase("as")) {
                        if (i + 1 < args.length) {
                            asPlayer = args[i + 1];
                        }
                        break;
                    } else if (name == null) {
                        name = args[i];
                    }
                }
                Path path = chunkShareBundle(name);
                try {
                    int n = asPlayer != null
                            ? ChunkShareService.importBundleAsPlayer(path, asPlayer)
                            : ChunkShareService.importBundle(path);
                    sendChunkSync("Imported " + n + " chunks from " + path + (asPlayer != null ? " as layer '" + asPlayer + "'" : ""));
                } catch (IOException e) {
                    sendChunkSync("Chunk import failed: " + e.getMessage());
                }
            }
            case "players" -> {
                var explored = VoxelConstants.getVoxelMapInstance().getExploredChunksManager().playerLayerSlugs();
                var newOld = VoxelConstants.getVoxelMapInstance().getNewerNewChunksManager().playerLayerSlugs();
                if (explored.isEmpty() && newOld.isEmpty()) {
                    sendChunkSync("No imported player layers in this game.");
                } else {
                    sendChunkSync("Player layers (this game) - explored: ["
                            + String.join(", ", explored) + "]  new/old: [" + String.join(", ", newOld) + "]");
                }
            }
            case "remove" -> {
                if (args.length < 3) {
                    sendChunkSync("Usage: /chunksync remove <player>");
                    return;
                }
                String slug = ChunkShareService.slugFor(args[2]);
                boolean a = VoxelConstants.getVoxelMapInstance().getExploredChunksManager().removePlayerLayer(slug);
                boolean b = VoxelConstants.getVoxelMapInstance().getNewerNewChunksManager().removePlayerLayer(slug);
                ChunkSharePlayerSettings.remove(slug);
                sendChunkSync(a || b ? "Removed player layer '" + slug + "'." : "No player layer '" + slug + "' found.");
            }
            case "key" -> {
                if (args.length < 3) {
                    sendChunkSync("Usage: /chunksync key <passphrase>  (shared secret used to encrypt/decrypt shares)");
                    return;
                }
                // Rejoin remaining args so spaces in the passphrase are allowed.
                StringBuilder pass = new StringBuilder(args[2]);
                for (int i = 3; i < args.length; i++) {
                    pass.append(' ').append(args[i]);
                }
                ChunkShareConfig.setPassphrase(pass.toString());
                sendChunkSync("Share passphrase set. Anyone you share with must set the same passphrase to read your data.");
            }
            case "host" -> {
                if (args.length < 3) {
                    sendChunkSync("Current host: " + ChunkShareConfig.getHost().id + ". Usage: /chunksync host <litterbox|file.io>");
                    return;
                }
                ChunkShareTransport.Host host = ChunkShareTransport.Host.fromId(args[2]);
                ChunkShareConfig.setHost(host);
                sendChunkSync("Share host set to " + host.id + (host == ChunkShareTransport.Host.FILE_IO
                        ? " (one-time download — first fetch deletes it)."
                        : " (temporary, 72h, multi-download)."));
            }
            case "share" -> {
                String target = (args.length >= 4 && args[2].equalsIgnoreCase("to")) ? args[3] : null;
                handleShare(target);
            }
            case "get" -> {
                if (args.length < 3) {
                    sendChunkSync("Usage: /chunksync get <code> [as <player>]");
                    return;
                }
                String code = args[2];
                String asPlayer = null;
                for (int i = 3; i + 1 < args.length; i++) {
                    if (args[i].equalsIgnoreCase("as")) {
                        asPlayer = args[i + 1];
                        break;
                    }
                }
                handleGet(code, asPlayer);
            }
            case "cartobase" -> handleCartobase(args);
            default -> sendChunkSync(USAGE);
        }
    }

    private static void handleCartobase(String[] args) {
        if (args.length < 3 || args[2].equalsIgnoreCase("status")) {
            String url = ChunkShareConfig.getCartobaseUrl();
            String user = ChunkShareConfig.getCartobaseUsername();
            boolean enabled = ChunkShareConfig.isCartobaseEnabled();
            var service = RemoteSyncService.instance();
            sendChunkSync("Cartobase: " + (enabled ? "ON" : "OFF")
                    + " | server=" + (url == null ? "(unset)" : url)
                    + " | account=" + (user == null ? "(not logged in)" : user)
                    + " | " + service.getStatus());
            return;
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "url" -> {
                if (args.length < 4) {
                    sendChunkSync("Usage: /chunksync cartobase url <https://host:port>");
                    return;
                }
                ChunkShareConfig.setCartobaseUrl(args[3]);
                sendChunkSync("Cartobase server set to " + ChunkShareConfig.getCartobaseUrl());
            }
            case "login" -> {
                if (args.length < 5) {
                    sendChunkSync("Usage: /chunksync cartobase login <username> <password>");
                    return;
                }
                String username = args[3];
                StringBuilder password = new StringBuilder(args[4]);
                for (int i = 5; i < args.length; i++) {
                    password.append(' ').append(args[i]);
                }
                handleCartobaseLogin(username, password.toString());
            }
            case "logout" -> {
                ChunkShareConfig.setCartobaseSession(null);
                ChunkShareConfig.setCartobaseUsername(null);
                ChunkShareConfig.setCartobaseEnabled(false);
                RemoteSyncService.instance().noteLoggedOut();
                sendChunkSync("Cartobase: logged out.");
            }
            case "on", "enable" -> {
                if (ChunkShareConfig.getCartobaseUrl() == null || ChunkShareConfig.getCartobaseSession() == null) {
                    sendChunkSync("Log in first: /chunksync cartobase login <username> <password> (after setting the server url).");
                    return;
                }
                ChunkShareConfig.setCartobaseEnabled(true);
                sendChunkSync("Cartobase sync enabled.");
            }
            case "off", "disable" -> {
                ChunkShareConfig.setCartobaseEnabled(false);
                sendChunkSync("Cartobase sync disabled.");
            }
            case "share" -> {
                if (args.length < 4) {
                    sendChunkSync("Usage: /chunksync cartobase share <username>");
                    return;
                }
                String target = args[3];
                withClient(client -> {
                    CartobaseClient.Share share = client.requestShare(target);
                    if ("active".equals(share.status())) {
                        sendAsync("Sharing with " + share.peerUsername() + " is now active.");
                    } else {
                        sendAsync("Share request sent to " + share.peerUsername() + " - waiting for them to accept.");
                    }
                    RemoteSyncService.instance().refreshSharesNow(null);
                });
            }
            case "accept" -> {
                if (args.length < 4) {
                    sendChunkSync("Usage: /chunksync cartobase accept <username>");
                    return;
                }
                String target = args[3];
                withClient(client -> {
                    CartobaseClient.ShareList shares = client.listShares();
                    CartobaseClient.Share match = shares.incoming().stream()
                            .filter(s -> s.peerUsername().equalsIgnoreCase(target))
                            .findFirst()
                            .orElse(null);
                    if (match == null) {
                        sendAsync("No pending share request from '" + target + "'.");
                        return;
                    }
                    client.acceptShare(match.id());
                    sendAsync("Sharing with " + match.peerUsername() + " is now active.");
                    RemoteSyncService.instance().refreshSharesNow(null);
                });
            }
            case "revoke" -> {
                if (args.length < 4) {
                    sendChunkSync("Usage: /chunksync cartobase revoke <username>");
                    return;
                }
                String target = args[3];
                withClient(client -> {
                    CartobaseClient.ShareList shares = client.listShares();
                    CartobaseClient.Share match = java.util.stream.Stream.of(shares.active(), shares.incoming(), shares.outgoing())
                            .flatMap(java.util.List::stream)
                            .filter(s -> s.peerUsername().equalsIgnoreCase(target))
                            .findFirst()
                            .orElse(null);
                    if (match == null) {
                        sendAsync("No share with '" + target + "'.");
                        return;
                    }
                    client.deleteShare(match.id());
                    sendAsync("Share with " + match.peerUsername() + " removed.");
                    RemoteSyncService.instance().refreshSharesNow(null);
                });
            }
            case "peers" -> withClient(client -> {
                CartobaseClient.ShareList shares = client.listShares();
                sendAsync("Active: [" + names(shares.active()) + "]  incoming: [" + names(shares.incoming())
                        + "]  outgoing: [" + names(shares.outgoing()) + "]");
            });
            default -> sendChunkSync("Usage: /chunksync cartobase <status | url <url> | login <user> <pass> | logout | on | off | share <user> | accept <user> | revoke <user> | peers>");
        }
    }

    private static String names(java.util.List<CartobaseClient.Share> shares) {
        return shares.stream().map(CartobaseClient.Share::peerUsername).reduce((a, b) -> a + ", " + b).orElse("");
    }

    private interface ClientTask {
        void run(CartobaseClient client) throws Exception;
    }

    private static void withClient(ClientTask task) {
        String url = ChunkShareConfig.getCartobaseUrl();
        String session = ChunkShareConfig.getCartobaseSession();
        if (url == null || session == null) {
            sendChunkSync("Not logged in to a cartobase server. Set the url and log in first.");
            return;
        }
        runOffThread("cartobase-request", () -> {
            try {
                task.run(new CartobaseClient(url, session));
            } catch (CartobaseClient.AuthException e) {
                sendAsync("Cartobase session expired - log in again from the sharing settings.");
            } catch (Exception e) {
                sendAsync("Cartobase request failed: " + e.getMessage());
            }
        });
    }

    private static void handleCartobaseLogin(String username, String password) {
        String url = ChunkShareConfig.getCartobaseUrl();
        if (url == null) {
            sendChunkSync("Set the server first: /chunksync cartobase url <https://host:port>");
            return;
        }
        sendChunkSync("Logging in to " + url + " as " + username + "...");
        runOffThread("cartobase-login", () -> {
            try {
                String authUrl = CartobaseClient.fetchAuthServerUrl(url);
                CartobaseClient.LoginResult result = CartobaseClient.login(authUrl, username, password);
                ChunkShareConfig.setCartobaseAuthUrl(authUrl);
                ChunkShareConfig.setCartobaseSession(result.sessionId());
                ChunkShareConfig.setCartobaseUsername(result.username());
                ChunkShareConfig.setCartobaseEnabled(true);
                RemoteSyncService.instance().noteLoggedIn();
                sendAsync("Cartobase: logged in as " + result.username() + ". Sync enabled.");
            } catch (CartobaseClient.AuthException e) {
                sendAsync("Login failed: invalid username or password.");
            } catch (Exception e) {
                sendAsync("Login failed: " + e.getMessage());
            }
        });
    }

    private static void handleShare(String target) {
        String passphrase = ChunkShareConfig.getPassphrase();
        if (passphrase == null) {
            sendChunkSync("No passphrase set. Run /chunksync key <passphrase> first (you and your friends must match).");
            return;
        }
        ChunkShareTransport.Host host = ChunkShareConfig.getHost();
        byte[] bundle;
        try {
            bundle = ChunkShareService.exportBundleBytes(playerName());
        } catch (IOException e) {
            sendChunkSync("Share failed while reading your data: " + e.getMessage());
            return;
        }
        sendChunkSync("Encrypting and uploading " + (bundle.length / 1024) + " KB to " + host.id + "...");
        runOffThread("chunk-share-upload", () -> {
            String code;
            try {
                byte[] blob = ChunkShareCrypto.encrypt(bundle, passphrase);
                code = ChunkShareTransport.upload(host, blob);
            } catch (Exception e) {
                sendAsync("Share upload failed: " + e.getMessage());
                return;
            }
            String token = ChunkShareChat.encode(playerName(), code, passphrase);
            final String finalCode = code;
            Minecraft.getInstance().execute(() -> {
                postShareToChat(token, target);
                sendChunkSync("Shared. " + (target != null ? "Whispered to " + target + ". " : "Posted to chat. ")
                        + "Friends with the key get a one-tap import. Manual code: /chunksync get " + finalCode);
            });
        });
    }

    private static void postShareToChat(String token, String target) {
        if (token == null) {
            sendChunkSync("Couldn't build the chat token; share it with the manual code instead.");
            return;
        }
        var player = VoxelConstants.getPlayer();
        if (player == null || player.connection == null) {
            sendChunkSync("Not connected to a server, so nothing was posted to chat. Use the manual code.");
            return;
        }
        if (target != null && !target.isBlank()) {
            player.connection.sendCommand("msg " + target + " " + token);
        } else {
            player.connection.sendChat(token);
        }
    }

    private static void handleGet(String code, String asPlayer) {
        String passphrase = ChunkShareConfig.getPassphrase();
        if (passphrase == null) {
            sendChunkSync("No passphrase set. Run /chunksync key <passphrase> first (must match the sender's).");
            return;
        }
        ChunkShareTransport.Host host = ChunkShareConfig.getHost();
        sendChunkSync("Downloading and decrypting from " + host.id + "...");
        runOffThread("chunk-share-download", () -> {
            Path temp;
            try {
                byte[] blob = ChunkShareTransport.download(host, code);
                byte[] bundle = ChunkShareCrypto.decrypt(blob, passphrase);
                temp = Files.createTempFile("vmchunkshare", ".zip");
                Files.write(temp, bundle);
            } catch (Exception e) {
                sendAsync("Get failed: " + e.getMessage());
                return;
            }
            Minecraft.getInstance().execute(() -> {
                try {
                    int n = asPlayer != null
                            ? ChunkShareService.importBundleAsPlayer(temp, asPlayer)
                            : ChunkShareService.importBundle(temp);
                    sendChunkSync("Imported " + n + " chunks into this game" + (asPlayer != null ? " as layer '" + asPlayer + "'" : "") + ".");
                } catch (IOException e) {
                    sendChunkSync("Import of downloaded data failed: " + e.getMessage());
                } finally {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException ignored) {
                        // temp file cleanup is best-effort
                    }
                }
            });
        });
    }

    private static void runOffThread(String name, Runnable task) {
        Thread thread = new Thread(task, "VoxelMap " + name);
        thread.setDaemon(true);
        thread.start();
    }

    private static void sendAsync(String text) {
        Minecraft.getInstance().execute(() -> sendChunkSync(text));
    }

    private static Path chunkShareBundle(String arg) {
        Path base = Minecraft.getInstance().gameDirectory.toPath().resolve("voxelmap").resolve("chunk_share");
        if (arg == null || arg.isBlank()) {
            return base.resolve("export");
        }
        Path path = Path.of(arg);
        return path.isAbsolute() ? path : base.resolve(arg);
    }

    private static String playerName() {
        try {
            return Minecraft.getInstance().getUser().getName();
        } catch (RuntimeException e) {
            return "self";
        }
    }

    private static void sendChunkSync(String text) {
        if (statusSink != null) {
            statusSink.accept(text);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui != null) {
            minecraft.gui.hud.getChat().addClientSystemMessage(AppChatMessages.prefixed("ChunkSync", text));
        }
    }
}

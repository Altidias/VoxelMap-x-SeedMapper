package com.mamiyaotaru.voxelmap.chunksync;

import com.mamiyaotaru.voxelmap.VoxelConstants;
import com.mamiyaotaru.voxelmap.util.GameVariableAccessShim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RemoteSyncService {
    private static final long PUSH_INTERVAL_MS = 5000L;
    private static final long PULL_INTERVAL_MS = 6000L;
    private static final long PRESENCE_INTERVAL_MS = 1500L;
    private static final long PEERS_INTERVAL_MS = 15000L;
    private static final long SESSION_REFRESH_INTERVAL_MS = 30L * 60L * 1000L;
    private static final int MAX_PULL_PAGES = 64;
    private static final int UPLOAD_BATCH_CHUNKS = 20000;

    private static final RemoteSyncService INSTANCE = new RemoteSyncService();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VoxelMap Cartobase Sync");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean pushInFlight = new AtomicBoolean(false);
    private final AtomicBoolean pullInFlight = new AtomicBoolean(false);
    private final AtomicBoolean whoamiInFlight = new AtomicBoolean(false);
    private final AtomicBoolean peersInFlight = new AtomicBoolean(false);
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
    private final AtomicBoolean presenceReportInFlight = new AtomicBoolean(false);
    private final AtomicBoolean presencePollInFlight = new AtomicBoolean(false);
    private final AtomicBoolean uploadInFlight = new AtomicBoolean(false);
    private final Map<String, Long> pullCursors = new ConcurrentHashMap<>();

    private volatile boolean active = false;
    private volatile boolean loginRequired = false;
    private volatile CartobaseClient client;
    private volatile String clientUrl;
    private volatile String clientSession;
    private volatile String selfUserId;
    private volatile String selfUsername;
    private volatile List<CartobaseClient.Share> activePeers = List.of();
    private volatile CartobaseClient.ShareList lastShares;
    private volatile List<PeerPresence> peerPresence = List.of();
    private volatile String status = "idle";

    private long lastPushMs;
    private long lastPullMs;
    private long lastPeersMs;
    private long lastPresenceReportMs;
    private long lastPresencePollMs;
    private long lastSessionRefreshMs = System.currentTimeMillis();

    private RemoteSyncService() {
    }

    public static RemoteSyncService instance() {
        return INSTANCE;
    }

    public void onClientTick() {
        boolean configured = ChunkShareConfig.isCartobaseEnabled()
                && ChunkShareConfig.getCartobaseUrl() != null
                && ChunkShareConfig.getCartobaseSession() != null;
        if (configured != active) {
            active = configured;
            if (!configured) {
                RemoteOutbox.setEnabled(false);
                resetState();
                status = "off";
            }
        }
        if (!active) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.getCurrentServer() == null) {
            RemoteOutbox.setEnabled(false);
            return;
        }
        if (loginRequired) {
            RemoteOutbox.setEnabled(false);
            return;
        }
        RemoteOutbox.setEnabled(true);
        ensureClient();
        if (selfUserId == null) {
            requestWhoami();
            return;
        }

        if (!ChunkShareConfig.isCartobaseUploaded(serverName()) && uploadInFlight.compareAndSet(false, true)) {
            startExistingDataUpload(serverName());
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastPeersMs >= PEERS_INTERVAL_MS && peersInFlight.compareAndSet(false, true)) {
            lastPeersMs = now;
            worker.submit(this::runPeersRefresh);
        }
        if (now - lastPushMs >= PUSH_INTERVAL_MS && !RemoteOutbox.isEmpty() && pushInFlight.compareAndSet(false, true)) {
            lastPushMs = now;
            String world = serverName();
            Map<String, Map<String, List<int[]>>> delta = RemoteOutbox.drain();
            worker.submit(() -> runPush(world, delta));
        }
        if (now - lastPullMs >= PULL_INTERVAL_MS && !activePeers.isEmpty() && pullInFlight.compareAndSet(false, true)) {
            lastPullMs = now;
            String world = serverName();
            String dim = dimTag();
            if (dim == null) {
                pullInFlight.set(false);
            } else {
                List<CartobaseClient.Share> peers = activePeers;
                worker.submit(() -> runPull(world, dim, peers));
            }
        }
        if (now - lastSessionRefreshMs >= SESSION_REFRESH_INTERVAL_MS && refreshInFlight.compareAndSet(false, true)) {
            lastSessionRefreshMs = now;
            worker.submit(this::runSessionRefresh);
        }

        net.minecraft.client.player.LocalPlayer player = minecraft.player;
        if (player != null) {
            if (now - lastPresenceReportMs >= PRESENCE_INTERVAL_MS && presenceReportInFlight.compareAndSet(false, true)) {
                lastPresenceReportMs = now;
                String world = serverName();
                String dim = dimTag();
                double px = player.getX();
                double py = player.getY();
                double pz = player.getZ();
                float yaw = player.getYRot();
                if (dim == null) {
                    presenceReportInFlight.set(false);
                } else {
                    worker.submit(() -> runReportPresence(world, dim, px, py, pz, yaw));
                }
            }
            if (now - lastPresencePollMs >= PRESENCE_INTERVAL_MS && presencePollInFlight.compareAndSet(false, true)) {
                lastPresencePollMs = now;
                String world = serverName();
                worker.submit(() -> runPollPresence(world));
            }
        }
    }

    public List<PeerPresence> getPeerPresence() {
        return active && !loginRequired ? peerPresence : List.of();
    }

    public CartobaseClient.ShareList getLastShares() {
        return lastShares;
    }

    public String getStatus() {
        return status;
    }

    public boolean isLoginRequired() {
        return loginRequired;
    }

    public String getSelfUsername() {
        return selfUsername;
    }

    public void noteLoggedIn() {
        loginRequired = false;
        resetState();
        status = "logged in";
    }

    public void noteLoggedOut() {
        loginRequired = false;
        resetState();
        status = "logged out";
    }

    public void refreshSharesNow(Runnable onDone) {
        worker.submit(() -> {
            runPeersRefreshBlocking();
            if (onDone != null) {
                Minecraft.getInstance().execute(onDone);
            }
        });
    }

    private void resetState() {
        client = null;
        clientUrl = null;
        clientSession = null;
        selfUserId = null;
        selfUsername = null;
        activePeers = List.of();
        lastShares = null;
        peerPresence = List.of();
        pullCursors.clear();
    }

    private void ensureClient() {
        String url = ChunkShareConfig.getCartobaseUrl();
        String session = ChunkShareConfig.getCartobaseSession();
        if (client == null || !url.equals(clientUrl) || !session.equals(clientSession)) {
            client = new CartobaseClient(url, session);
            clientUrl = url;
            clientSession = session;
            selfUserId = null;
            lastSessionRefreshMs = System.currentTimeMillis();
        }
    }

    private void requestWhoami() {
        if (!whoamiInFlight.compareAndSet(false, true)) {
            return;
        }
        CartobaseClient current = client;
        worker.submit(() -> {
            try {
                if (current != null) {
                    CartobaseClient.WhoAmI me = current.whoami();
                    selfUserId = me.userId();
                    selfUsername = me.username();
                    status = "syncing as " + me.username();
                }
            } catch (CartobaseClient.AuthException e) {
                handleAuthFailure();
            } catch (Exception e) {
                status = "cartobase unreachable";
                VoxelConstants.getLogger().warn("Cartobase whoami failed: " + e.getMessage());
            } finally {
                whoamiInFlight.set(false);
            }
        });
    }

    // first run for a world: ship everything already on disk, not just new discoveries.
    // the export reads the chunk stores, so it has to happen on the client thread.
    private void startExistingDataUpload(String world) {
        status = "reading local map data...";
        Map<String, long[]> explored;
        Map<String, Map<String, long[]>> newold;
        try {
            explored = VoxelConstants.getVoxelMapInstance().getExploredChunksManager().exportAllDimensionsExplored();
            newold = VoxelConstants.getVoxelMapInstance().getNewerNewChunksManager().exportAllDimensionsNewOld();
        } catch (Exception e) {
            VoxelConstants.getLogger().warn("Cartobase could not read local map data: " + e.getMessage());
            status = "could not read local map data";
            uploadInFlight.set(false);
            return;
        }
        worker.submit(() -> runExistingDataUpload(world, explored, newold));
    }

    private void runExistingDataUpload(String world, Map<String, long[]> explored, Map<String, Map<String, long[]>> newold) {
        CartobaseClient current = client;
        try {
            if (current == null) {
                return;
            }
            long total = 0;
            for (long[] coords : explored.values()) {
                total += coords.length;
            }
            for (Map<String, long[]> byCategory : newold.values()) {
                for (long[] coords : byCategory.values()) {
                    total += coords.length;
                }
            }
            if (total == 0) {
                ChunkShareConfig.setCartobaseUploaded(world, true);
                status = "no existing map data to upload";
                return;
            }

            VoxelConstants.getLogger().info("Cartobase uploading " + total + " existing chunks for " + world);
            long sent = 0;
            for (Map.Entry<String, long[]> dim : explored.entrySet()) {
                sent = uploadCategory(current, world, dim.getKey(), RemoteOutbox.CATEGORY_NAMES[RemoteOutbox.EXPLORED],
                        dim.getValue(), sent, total);
            }
            for (Map.Entry<String, Map<String, long[]>> dim : newold.entrySet()) {
                for (Map.Entry<String, long[]> cat : dim.getValue().entrySet()) {
                    sent = uploadCategory(current, world, dim.getKey(), cat.getKey(), cat.getValue(), sent, total);
                }
            }

            ChunkShareConfig.setCartobaseUploaded(world, true);
            status = "uploaded " + total + " existing chunks";
            VoxelConstants.getLogger().info("Cartobase finished uploading existing chunks for " + world);
        } catch (CartobaseClient.AuthException e) {
            handleAuthFailure();
        } catch (Exception e) {
            status = "upload of existing data failed";
            VoxelConstants.getLogger().warn("Cartobase upload of existing data failed: " + e.getMessage());
        } finally {
            uploadInFlight.set(false);
        }
    }

    private long uploadCategory(CartobaseClient current, String world, String dim, String category,
                                long[] coords, long sent, long total) throws Exception {
        List<int[]> batch = new ArrayList<>(Math.min(UPLOAD_BATCH_CHUNKS, coords.length));
        for (long packed : coords) {
            batch.add(new int[] {(int) (packed >> 32), (int) packed});
            if (batch.size() >= UPLOAD_BATCH_CHUNKS) {
                current.pushChunks(world, dim, Map.of(category, batch));
                sent += batch.size();
                status = "uploading existing map data " + (sent * 100 / Math.max(1, total)) + "%";
                batch = new ArrayList<>(UPLOAD_BATCH_CHUNKS);
            }
        }
        if (!batch.isEmpty()) {
            current.pushChunks(world, dim, Map.of(category, batch));
            sent += batch.size();
            status = "uploading existing map data " + (sent * 100 / Math.max(1, total)) + "%";
        }
        return sent;
    }

    public void resetExistingDataUpload() {
        ChunkShareConfig.setCartobaseUploaded(serverName(), false);
    }

    private void runPeersRefresh() {
        try {
            runPeersRefreshBlocking();
        } finally {
            peersInFlight.set(false);
        }
    }

    private void runPeersRefreshBlocking() {
        CartobaseClient current = client;
        try {
            if (current == null) {
                return;
            }
            CartobaseClient.ShareList shares = current.listShares();
            lastShares = shares;
            activePeers = shares.active();
            String self = selfUsername;
            status = "syncing as " + (self == null ? "?" : self) + " - " + shares.active().size() + " peer(s)"
                    + (shares.incoming().isEmpty() ? "" : ", " + shares.incoming().size() + " pending request(s)");
        } catch (CartobaseClient.AuthException e) {
            handleAuthFailure();
        } catch (Exception e) {
            VoxelConstants.getLogger().warn("Cartobase share list failed: " + e.getMessage());
        }
    }

    private void runPush(String world, Map<String, Map<String, List<int[]>>> delta) {
        CartobaseClient current = client;
        try {
            if (current == null) {
                return;
            }
            for (Map.Entry<String, Map<String, List<int[]>>> dim : delta.entrySet()) {
                current.pushChunks(world, dim.getKey(), dim.getValue());
            }
        } catch (CartobaseClient.AuthException e) {
            handleAuthFailure();
        } catch (Exception e) {
            VoxelConstants.getLogger().warn("Cartobase push failed: " + e.getMessage());
        } finally {
            pushInFlight.set(false);
        }
    }

    private void runPull(String world, String dim, List<CartobaseClient.Share> peers) {
        CartobaseClient current = client;
        try {
            if (current == null) {
                return;
            }
            for (CartobaseClient.Share peer : peers) {
                pullPeer(current, world, dim, peer);
            }
        } catch (CartobaseClient.AuthException e) {
            handleAuthFailure();
        } catch (Exception e) {
            VoxelConstants.getLogger().warn("Cartobase pull failed: " + e.getMessage());
        } finally {
            pullInFlight.set(false);
        }
    }

    private void pullPeer(CartobaseClient current, String world, String dim, CartobaseClient.Share peer) throws Exception {
        String key = world + "|" + dim + "|" + peer.peerId();
        long cursor = pullCursors.getOrDefault(key, 0L);
        List<Long> explored = new ArrayList<>();
        Map<String, List<Long>> newold = new LinkedHashMap<>();
        int pages = 0;
        while (pages++ < MAX_PULL_PAGES) {
            CartobaseClient.PullResult result = current.pullChunks(world, dim, peer.peerId(), cursor);
            for (CartobaseClient.Container container : result.containers()) {
                long[] coords = CartobaseClient.decodeContainer(container.cx(), container.cz(), container.bitmap());
                if (coords.length == 0) {
                    continue;
                }
                if (RemoteOutbox.CATEGORY_NAMES[RemoteOutbox.EXPLORED].equals(container.category())) {
                    appendAll(explored, coords);
                } else {
                    appendAll(newold.computeIfAbsent(container.category(), k -> new ArrayList<>()), coords);
                }
            }
            cursor = result.cursor();
            if (result.complete()) {
                break;
            }
        }
        pullCursors.put(key, cursor);
        if (!explored.isEmpty() || !newold.isEmpty()) {
            String slug = ChunkShareService.slugFor(peer.peerUsername());
            List<Long> exploredFinal = explored;
            Map<String, List<Long>> newoldFinal = newold;
            Minecraft.getInstance().execute(() -> applyPulled(dim, slug, exploredFinal, newoldFinal));
        }
    }

    private void applyPulled(String dim, String slug, List<Long> explored, Map<String, List<Long>> newold) {
        var exploredManager = VoxelConstants.getVoxelMapInstance().getExploredChunksManager();
        var newoldManager = VoxelConstants.getVoxelMapInstance().getNewerNewChunksManager();
        if (!explored.isEmpty()) {
            exploredManager.importPlayerExplored(slug, dim, toArray(explored));
        }
        if (!newold.isEmpty()) {
            Map<String, long[]> byCategory = new LinkedHashMap<>();
            for (Map.Entry<String, List<Long>> cat : newold.entrySet()) {
                byCategory.put(cat.getKey(), toArray(cat.getValue()));
            }
            newoldManager.importPlayerNewOld(slug, dim, byCategory);
        }
        ChunkSharePlayerSettings.register(slug);
    }

    private static void appendAll(List<Long> target, long[] coords) {
        for (long c : coords) {
            target.add(c);
        }
    }

    private static long[] toArray(List<Long> list) {
        long[] array = new long[list.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    private void runReportPresence(String world, String dim, double x, double y, double z, float yaw) {
        CartobaseClient current = client;
        try {
            if (current != null) {
                current.reportPresence(world, dim, x, y, z, yaw);
            }
        } catch (CartobaseClient.AuthException e) {
            handleAuthFailure();
        } catch (Exception e) {
            VoxelConstants.getLogger().warn("Cartobase presence report failed: " + e.getMessage());
        } finally {
            presenceReportInFlight.set(false);
        }
    }

    private void runPollPresence(String world) {
        CartobaseClient current = client;
        try {
            if (current == null) {
                return;
            }
            List<CartobaseClient.Presence> list = current.listPresence(world);
            List<PeerPresence> members = new ArrayList<>(list.size());
            for (CartobaseClient.Presence p : list) {
                members.add(new PeerPresence(p.username(), p.dimension(), p.x(), p.y(), p.z(), p.yaw(), p.ageMs()));
            }
            peerPresence = members;
        } catch (CartobaseClient.AuthException e) {
            handleAuthFailure();
        } catch (Exception e) {
            VoxelConstants.getLogger().warn("Cartobase presence poll failed: " + e.getMessage());
        } finally {
            presencePollInFlight.set(false);
        }
    }

    private void runSessionRefresh() {
        try {
            rotateSession();
        } finally {
            refreshInFlight.set(false);
        }
    }

    private void handleAuthFailure() {
        if (!rotateSession()) {
            loginRequired = true;
            status = "login required";
            VoxelConstants.getLogger().warn("Cartobase session expired; log in again from the sharing settings");
        }
    }

    private boolean rotateSession() {
        String authUrl = ChunkShareConfig.getCartobaseAuthUrl();
        String session = ChunkShareConfig.getCartobaseSession();
        if (authUrl == null || session == null) {
            return false;
        }
        try {
            String rotated = CartobaseClient.refreshSession(authUrl, session);
            ChunkShareConfig.setCartobaseSession(rotated);
            client = new CartobaseClient(ChunkShareConfig.getCartobaseUrl(), rotated);
            clientSession = rotated;
            lastSessionRefreshMs = System.currentTimeMillis();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String serverName() {
        Minecraft minecraft = Minecraft.getInstance();
        ServerData serverData = minecraft.getCurrentServer();
        return serverData != null && serverData.ip != null && !serverData.ip.isBlank()
                ? serverData.ip.replace(':', '_')
                : minecraft.hasSingleplayerServer() ? "singleplayer" : "unknown";
    }

    private static String dimTag() {
        Level level = GameVariableAccessShim.getWorld();
        return level == null ? null : level.dimension().identifier().toString().replace(':', '_');
    }
}

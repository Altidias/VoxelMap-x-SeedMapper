package com.mamiyaotaru.voxelmap.chunksync;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public final class CartobaseClient {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private static final Gson GSON = new Gson();
    private static final int CONTAINER_CHUNKS = 32;

    private final String baseUrl;
    private final String session;

    public CartobaseClient(String baseUrl, String session) {
        this.baseUrl = baseUrl;
        this.session = session;
    }

    public static final class AuthException extends IOException {
        public AuthException(String message) {
            super(message);
        }
    }

    public record LoginResult(String sessionId, String username, String role) {
    }

    public record WhoAmI(String userId, String username, String role) {
    }

    public record Share(String id, String peerId, String peerUsername, String status, boolean requestedByMe) {
    }

    public record ShareList(List<Share> active, List<Share> incoming, List<Share> outgoing) {
    }

    public record Container(String category, int cx, int cz, byte[] bitmap) {
    }

    public record PullResult(List<Container> containers, long cursor, boolean complete) {
    }

    public record Presence(String username, String playerName, String dimension, double x, double y, double z,
                           float yaw, long ageMs, boolean ownAccount) {
    }

    public static String fetchAuthServerUrl(String cartobaseUrl) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(cartobaseUrl + "/api/v1/meta"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = sendRaw(req);
        JsonObject object = JsonParser.parseString(response.body()).getAsJsonObject();
        String url = getString(object, "auth_server_url");
        if (url.isEmpty()) {
            throw new IOException("cartobase meta did not include an auth server url");
        }
        return url.replaceAll("/+$", "");
    }

    public static LoginResult login(String authServerUrl, String username, String password) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        HttpRequest req = HttpRequest.newBuilder(URI.create(authServerUrl + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();
        HttpResponse<String> response = sendRaw(req);
        JsonObject object = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject user = object.getAsJsonObject("user");
        return new LoginResult(
                getString(object, "session_id"),
                user == null ? username : getString(user, "username"),
                user == null ? "" : getString(user, "role"));
    }

    public static String refreshSession(String authServerUrl, String session) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(authServerUrl + "/api/v1/auth/refresh"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + session)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = sendRaw(req);
        JsonObject object = JsonParser.parseString(response.body()).getAsJsonObject();
        String rotated = getString(object, "session_id");
        if (rotated.isEmpty()) {
            throw new IOException("auth server refresh did not return a session id");
        }
        return rotated;
    }

    public WhoAmI whoami() throws IOException, InterruptedException {
        HttpResponse<String> response = send(request("/api/v1/whoami").GET().build());
        JsonObject object = JsonParser.parseString(response.body()).getAsJsonObject();
        return new WhoAmI(getString(object, "user_id"), getString(object, "username"), getString(object, "role"));
    }

    public ShareList listShares() throws IOException, InterruptedException {
        HttpResponse<String> response = send(request("/api/v1/shares").GET().build());
        JsonObject object = JsonParser.parseString(response.body()).getAsJsonObject();
        return new ShareList(
                parseShares(object.getAsJsonArray("active")),
                parseShares(object.getAsJsonArray("incoming")),
                parseShares(object.getAsJsonArray("outgoing")));
    }

    public Share requestShare(String username) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        HttpResponse<String> response = send(request("/api/v1/shares")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build());
        return parseShare(JsonParser.parseString(response.body()).getAsJsonObject());
    }

    public void acceptShare(String shareId) throws IOException, InterruptedException {
        send(request("/api/v1/shares/" + enc(shareId) + "/accept")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
    }

    public void deleteShare(String shareId) throws IOException, InterruptedException {
        send(request("/api/v1/shares/" + enc(shareId)).DELETE().build());
    }

    public long pushChunks(String world, String dim, Map<String, List<int[]>> categories) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("world", world);
        body.addProperty("dimension", dim);
        JsonObject cats = new JsonObject();
        for (Map.Entry<String, List<int[]>> entry : categories.entrySet()) {
            JsonArray arr = new JsonArray();
            for (int[] coord : entry.getValue()) {
                JsonArray pair = new JsonArray();
                pair.add(coord[0]);
                pair.add(coord[1]);
                arr.add(pair);
            }
            cats.add(entry.getKey(), arr);
        }
        body.add("categories", cats);
        HttpResponse<String> response = send(request("/api/v1/sync/chunks")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build());
        JsonObject object = JsonParser.parseString(response.body()).getAsJsonObject();
        return object.has("applied") ? object.get("applied").getAsLong() : 0L;
    }

    public PullResult pullChunks(String world, String dim, String ownerId, long since) throws IOException, InterruptedException {
        String path = "/api/v1/sync/chunks?world=" + enc(world) + "&dim=" + enc(dim)
                + "&owner=" + enc(ownerId) + "&since=" + since;
        HttpResponse<String> response = send(request(path).GET().build());
        JsonObject object = JsonParser.parseString(response.body()).getAsJsonObject();
        List<Container> containers = new ArrayList<>();
        JsonArray arr = object.getAsJsonArray("containers");
        if (arr != null) {
            for (JsonElement element : arr) {
                JsonObject c = element.getAsJsonObject();
                containers.add(new Container(
                        getString(c, "category"),
                        c.get("cx").getAsInt(),
                        c.get("cz").getAsInt(),
                        Base64.getDecoder().decode(getString(c, "bitmap"))));
            }
        }
        long cursor = object.has("cursor") ? object.get("cursor").getAsLong() : since;
        boolean complete = !object.has("complete") || object.get("complete").getAsBoolean();
        return new PullResult(containers, cursor, complete);
    }

    public void reportPresence(String world, String dim, String instanceId, String playerName,
                               double x, double y, double z, float yaw) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("world", world);
        body.addProperty("dimension", dim);
        body.addProperty("instance_id", instanceId);
        body.addProperty("player_name", playerName);
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("z", z);
        body.addProperty("yaw", yaw);
        send(request("/api/v1/presence")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build());
    }

    public List<Presence> listPresence(String world, String instanceId) throws IOException, InterruptedException {
        HttpResponse<String> response = send(request(
                "/api/v1/presence?world=" + enc(world) + "&instance_id=" + enc(instanceId)).GET().build());
        JsonObject object = JsonParser.parseString(response.body()).getAsJsonObject();
        List<Presence> out = new ArrayList<>();
        JsonArray arr = object.getAsJsonArray("players");
        if (arr != null) {
            for (JsonElement element : arr) {
                JsonObject p = element.getAsJsonObject();
                out.add(new Presence(
                        getString(p, "username"),
                        getString(p, "player_name"),
                        getString(p, "dimension"),
                        p.get("x").getAsDouble(),
                        p.get("y").getAsDouble(),
                        p.get("z").getAsDouble(),
                        p.has("yaw") ? p.get("yaw").getAsFloat() : 0f,
                        p.has("age_ms") ? p.get("age_ms").getAsLong() : 0L,
                        p.has("own_account") && p.get("own_account").getAsBoolean()));
            }
        }
        return out;
    }

    public static long[] decodeContainer(int cx, int cz, byte[] bitmap) {
        int bits = CONTAINER_CHUNKS * CONTAINER_CHUNKS;
        List<Long> out = new ArrayList<>();
        for (int idx = 0; idx < bits; idx++) {
            int byteIndex = idx >> 3;
            if (byteIndex >= bitmap.length) {
                break;
            }
            if ((bitmap[byteIndex] & (1 << (idx & 7))) == 0) {
                continue;
            }
            int x = cx * CONTAINER_CHUNKS + (idx % CONTAINER_CHUNKS);
            int z = cz * CONTAINER_CHUNKS + (idx / CONTAINER_CHUNKS);
            out.add(((long) x << 32) ^ (z & 0xFFFFFFFFL));
        }
        long[] result = new long[out.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = out.get(i);
        }
        return result;
    }

    private static List<Share> parseShares(JsonArray arr) {
        List<Share> out = new ArrayList<>();
        if (arr != null) {
            for (JsonElement element : arr) {
                out.add(parseShare(element.getAsJsonObject()));
            }
        }
        return out;
    }

    private static Share parseShare(JsonObject object) {
        return new Share(
                getString(object, "id"),
                getString(object, "peer_id"),
                getString(object, "peer_username"),
                getString(object, "status"),
                object.has("requested_by_me") && object.get("requested_by_me").getAsBoolean());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + session)
                .timeout(Duration.ofSeconds(60));
    }

    private HttpResponse<String> send(HttpRequest req) throws IOException, InterruptedException {
        return sendRaw(req);
    }

    private static HttpResponse<String> sendRaw(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> response = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 401) {
            throw new AuthException("HTTP 401: " + truncate(response.body()));
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
        return response;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String getString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static String truncate(String value) {
        if (value == null) {
            return "(no body)";
        }
        String stripped = value.strip();
        return stripped.length() > 200 ? stripped.substring(0, 200) + "..." : stripped;
    }
}

package com.mamiyaotaru.voxelmap.chunksync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class RemoteOutbox {
    public static final String[] CATEGORY_NAMES =
            {"explored", "new", "old", "block_exploit", "being_updated", "old_generation"};
    public static final int EXPLORED = 0;

    private static final int MAX_PENDING = 2_000_000;

    private record Entry(String dim, int category, int x, int z) {
    }

    private static volatile boolean enabled = false;
    private static final ConcurrentLinkedQueue<Entry> queue = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger pending = new AtomicInteger();

    private RemoteOutbox() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            clear();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isEmpty() {
        return queue.isEmpty();
    }

    public static void recordExplored(String dim, int x, int z) {
        record(dim, EXPLORED, x, z);
    }

    public static void recordNewOld(String dim, int category, int x, int z) {
        record(dim, category, x, z);
    }

    private static void record(String dim, int category, int x, int z) {
        if (!enabled || dim == null) {
            return;
        }
        if (pending.get() >= MAX_PENDING) {
            return;
        }
        queue.add(new Entry(dim.intern(), category, x, z));
        pending.incrementAndGet();
    }

    public static void clear() {
        queue.clear();
        pending.set(0);
    }

    public static Map<String, Map<String, List<int[]>>> drain() {
        Map<String, Map<String, List<int[]>>> out = new LinkedHashMap<>();
        Entry e;
        while ((e = queue.poll()) != null) {
            pending.decrementAndGet();
            String category = CATEGORY_NAMES[e.category()];
            out.computeIfAbsent(e.dim(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(category, k -> new ArrayList<>())
                    .add(new int[] {e.x(), e.z()});
        }
        return out;
    }
}

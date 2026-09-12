package com.moneyakshaders.render;

final class SnapshotQueueRoutingTest {
    static void run() {
        var urgent = new java.util.PriorityQueue<Integer>();
        var streaming = new java.util.PriorityQueue<Integer>();
        var lanes = new java.util.HashMap<Integer, Integer>();
        java.util.function.ToIntFunction<Integer> lane = key -> lanes.getOrDefault(key, -1);
        for (int key = 0; key < 128; key++) { streaming.add(key); lanes.put(key, 1); }
        // First slice starts in the ordinary lane; completion-time promotion changes its live job.
        Integer first = SnapshotQueueRouting.poll(urgent, streaming, false, lane);
        lanes.put(first, 0);
        streaming.add(first);
        for (int key = 1; key < 128; key++) lanes.put(key, 0);
        if (SnapshotQueueRouting.poll(urgent, streaming, false, lane) != null)
            throw new AssertionError("Promoted job executed in wrong lane");
        if (urgent.size() != 128) throw new AssertionError("Promotion lost live work");
        int completed = 0;
        while (SnapshotQueueRouting.poll(urgent, streaming, true, lane) != null) completed++;
        if (completed != 128) throw new AssertionError("Stationary next frame failed to drain full window");
        urgent.add(200); lanes.put(200, 1);
        urgent.add(201); // cancelled entry
        if (SnapshotQueueRouting.poll(urgent, streaming, true, lane) != null || streaming.size() != 1)
            throw new AssertionError("Demotion or cancellation routing failed");
        if (SnapshotQueueRouting.poll(urgent, streaming, false, lane) != 200)
            throw new AssertionError("Demoted work lost");
        System.out.println("SnapshotQueueRouting tests PASS (stationary 128-job promotion)");
    }
}

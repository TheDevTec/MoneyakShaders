package com.moneyakshaders.render;

import java.util.concurrent.*;

final class BlockEditLaneTest {
    static void run() {
        ExecutorService streaming = Executors.newSingleThreadExecutor();
        ThreadPoolExecutor edits = BlockEditLane.executor();
        CountDownLatch unblock = new CountDownLatch(1), streamingStarted = new CountDownLatch(1);
        CountDownLatch editStarted = new CountDownLatch(1), unblockEdit = new CountDownLatch(1);
        try {
            streaming.submit(() -> { streamingStarted.countDown(); await(unblock); });
            if (!streamingStarted.await(5, TimeUnit.SECONDS)) throw new AssertionError("streaming setup timeout");
            if (edits.submit(() -> 42).get(5, TimeUnit.SECONDS) != 42)
                throw new AssertionError("edit failed while streaming worker blocked");
            edits.submit(() -> { editStarted.countDown(); await(unblockEdit); });
            if (!editStarted.await(5, TimeUnit.SECONDS)) throw new AssertionError("edit setup timeout");
            java.util.List<Future<Integer>> queued = new java.util.ArrayList<>();
            for (int i = 0; i < BlockEditLane.QUEUED; i++) {
                int value = i;
                queued.add(edits.submit(() -> value));
            }
            try { edits.submit(() -> 0); throw new AssertionError("edit queue grew beyond its bound"); }
            catch (RejectedExecutionException expected) { }
            unblockEdit.countDown();
            for (int i = 0; i < queued.size(); i++)
                if (queued.get(i).get(5, TimeUnit.SECONDS) != i) throw new AssertionError("queued edit lost");
            System.out.println("BlockEditLane tests PASS (blocked streaming, bounded edit queue)");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new AssertionError(error);
        } catch (ExecutionException | TimeoutException error) { throw new AssertionError(error); }
        finally { unblock.countDown(); unblockEdit.countDown(); edits.shutdownNow(); streaming.shutdownNow(); }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}

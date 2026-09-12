package com.moneyakshaders.render;

public final class RenderSchedulingTest {
    public static void main(String[] args) {
		ArenaAllocatorTest.main(args);
		OpaqueDrawOrderTest.run();
		SnapshotQueueRoutingTest.run();
		BlockEditLaneTest.run();
		check(SceneAppearance.visibility(0, 32 * 32) == 1, "near terrain appears immediately");
		check(SceneAppearance.visibility(0, 64 * 64) == 0, "distant terrain starts at fog colour");
		check(SceneAppearance.visibility(90_000_000, 64 * 64) == 0.5f, "appearance is halfway after 90ms");
		check(SceneAppearance.visibility(180_000_000, 64 * 64) == 1, "appearance completes after 180ms");
		var dependencies = new UploadDependencyGraph();
		check(dependencies.add(1, 2) && dependencies.add(2, 3), "dependency chain accepted");
		check(!dependencies.add(3, 1) && !dependencies.add(1, 1), "cycles rejected");
		check(!dependencies.ready(1, ignored -> false), "waiter retains prerequisite");
		dependencies.published(3);
		check(dependencies.ready(2, ignored -> false), "published root releases direct waiter");
		check(!dependencies.ready(1, ignored -> false), "transitive waiter still waits for intermediate mesh");
		dependencies.published(2);
		check(dependencies.ready(1, ignored -> false), "later lighting cannot resurrect satisfied edge");
		dependencies.add(1, 2);
		check(dependencies.ready(1, key -> key == 2), "evicted prerequisite cannot block progress");
		dependencies.add(1, 2);
		dependencies.remove(1);
		dependencies.published(2);
		check(dependencies.ready(1, ignored -> false), "cancelled waiter removes reverse edge");
		var fastBudget = new com.moneyakshaders.client.StreamingFrameBudget();
		fastBudget.sample(0.5, 0, false);
		check(fastBudget.preparationNanos(120, 1, 0, 2) == 850_000, "idle consumers borrow bounded snapshot slice");
		check(fastBudget.preparationNanos(120, 1, 0, 12) == 350_000, "slow frame cancels starvation loan");
		check(fastBudget.preparationNanos(120, 1, 0, 6) >= 899_999, "165 FPS still feeds idle mesh workers");
		check(fastBudget.preparationNanos(120, 1, 0, 8) <= 1_200_000, "starvation loan has an absolute ceiling");
		int urgentFrames = 0, streamFrames = 0;
		for (int frame = 0; frame < 1000; frame++) {
			if (StreamingOrder.urgentFirst(frame, true, true)) urgentFrames++; else streamFrames++;
		}
		check(urgentFrames == 500 && streamFrames == 500, "continuous remesh traffic cannot starve initial terrain");
		check(StreamingOrder.urgentFirst(1, true, false), "empty stream gives all time to edits");
		check(!StreamingOrder.urgentFirst(0, false, true), "empty edit lane gives all time to terrain");
		check(StreamingOrder.locality(1, 1, 0, 0) < StreamingOrder.locality(2, 0, 0, 0),
				"near envelope precedes a farther column top");
		check(StreamingOrder.locality(-1, -1, 0, 0) == StreamingOrder.locality(1, 1, 0, 0),
				"column priority is symmetric around viewer");
		check(fastBudget.preparationNanos(120, 1, 1, 2) == 350_000, "upload pressure cancels loan");
		check(fastBudget.preparationNanos(120, 3, 0, 2) == 350_000, "busy workers cancel loan");
		var streamingBudget = new com.moneyakshaders.client.StreamingFrameBudget();
		streamingBudget.sample(2.5, 0, false);
		check(streamingBudget.preparationNanos() == 750_000, "400 FPS reserves less than one millisecond for streaming");
		for (int i = 0; i < 10000; i++) streamingBudget.sample(10, 6, true);
		check(streamingBudget.preparationNanos() == 750_000 && streamingBudget.targetMs(14) < 4,
				"100 FPS loading plateau never becomes the learned target");
		streamingBudget.sample(Double.NaN, 0, false);
		check(streamingBudget.preparationNanos() == 750_000, "invalid frame sample ignored");
		for (int i = 0; i < 1000; i++) streamingBudget.sample(20, 0, false);
		check(streamingBudget.preparationNanos() == 2_000_000, "slower scene learns bounded budget");
		for (int i = 0; i < 100; i++) streamingBudget.sample(3, 0, true);
		check(streamingBudget.preparationNanos() < 910_000, "faster observed rendering promptly contracts streaming allowance");
		PassCadence cadence = new PassCadence();
		for (int frame = 0; frame < 400; frame += 4) { cadence.invoke(frame); cadence.invoke(frame); }
		check(Math.abs(cadence.amortized(8) - 2) < 0.001, "four-frame pass charges one quarter of its cost");
		for (int frame = 400; frame < 500; frame++) cadence.invoke(frame);
		check(Math.abs(cadence.amortized(8) - 8) < 0.001, "cadence recovers when every-frame work resumes");
		DeferredDisposal<Integer> disposal = new DeferredDisposal<>();
		java.util.ArrayList<Integer> oldWorld = new java.util.ArrayList<>();
		for (int i = 0; i < 1000; i++) oldWorld.add(i);
		disposal.detach(oldWorld);
		java.util.HashSet<Integer> freed = new java.util.HashSet<>();
		java.util.concurrent.atomic.AtomicLong disposalClock = new java.util.concurrent.atomic.AtomicLong();
		check(disposal.drain(100, 500, () -> disposalClock.getAndAdd(100), freed::add) == 5,
				"world teardown obeys time slice");
		disposal.detach(java.util.List.of(1000, 1001));
		while (disposal.pending() > 0) disposal.drain(17, 1000, () -> 0, freed::add);
		check(freed.size() == 1002, "multiple detached worlds release exactly once without dropping pending ranges");
        ShadowOrigin origin = new ShadowOrigin();
        double x = 25_000_000.125, y = 80.25, z = -24_000_000.375;
        for (int i = 0; i < 24000; i++) {
            double angle = i * 0.0001;
            origin.update(x, y, z, 0.02, Math.cos(angle), 0, Math.sin(angle), 0, 1, 0);
            check(origin.x == x && origin.y == y && origin.z == z,
                    "stationary camera must not translate as the sun rotates, including far coordinates");
        }
        origin.reset();
        origin.update(0, 0, 0, 0.02, 1, 0, 0, 0, 1, 0);
        for (int i = 0; i < 10000; i++) {
            double cx = i * 0.001;
            origin.update(cx, 0, 0, 0.02, 1, 0, 0, 0, 1, 0);
            check(Math.abs(origin.x - cx) <= 0.0100001, "translation error bounded by half texel");
        }
        origin.update(50000, 10, 20, 0.02, 1, 0, 0, 0, 1, 0);
        check(origin.x == 50000, "teleport resets origin");
        MeshWorkerBudget budget = new MeshWorkerBudget();
        int workers = 2;
        for (int i = 0; i < 3; i++) workers = budget.update(i * 1000, workers, 6, 12, false, 8, 16);
        check(workers == 3, "sustained backlog and headroom add one worker");
        workers = budget.update(2100, workers, 6, 12, false, 8, 16);
        check(workers == 3, "FPS does not accelerate scaling");
        for (int i = 3; i < 5; i++) workers = budget.update(i * 1000, workers, 6, 12, true, 8, 16);
        check(workers == 2, "RAM backpressure reduces workers");
        for (int i = 5; i < 20; i++) workers = budget.update(i * 1000, workers, 6, 12, false, 30, 16);
        check(workers == 1, "overload retains progress with one worker");
        System.out.println("RenderScheduling tests PASS");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

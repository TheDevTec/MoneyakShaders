package com.moneyakshaders.render;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class SceneResidencySchedulerTest {
    public static void main(String[] args) {
        SceneResidencyScheduler scheduler = new SceneResidencyScheduler();
        for (int i = 0; i < 10000; i++) scheduler.register(i);
        HashSet<Long> visited = new HashSet<>();
        AtomicLong clock = new AtomicLong();
        int first = scheduler.process(128, 500, () -> clock.getAndAdd(100), visited::add);
        check(first == 5, "elapsed budget stops work before count limit");
        while (scheduler.pending() > 0) {
            int count = scheduler.process(128, 500, () -> 0, visited::add);
            check(count <= 128, "count bound");
        }
        check(visited.size() == 10000, "no keys lost between frame slices");
        check(scheduler.process(128, 500, () -> 0, visited::add) == 0, "unchanged scene idle");
        scheduler.beginSweep();
        scheduler.prioritize(7000);
        ArrayList<Long> order = new ArrayList<>();
        scheduler.process(2, 500, () -> 0, order::add);
        check(order.get(0) == 7000 && order.size() == 2, "visible work first with background progress");
        scheduler.prioritize(6000);
        scheduler.prioritizeNear(123);
        order.clear();
        scheduler.process(2, 500, () -> 0, order::add);
        check(order.get(0) == 123, "camera neighborhood bypasses older visibility requests");
        scheduler.beginSweep();
        scheduler.process(128, 500, () -> 0, scheduler::remove);
        while (scheduler.pending() > 0) scheduler.process(128, 500, () -> 0, scheduler::remove);
        check(scheduler.pending() == 0, "removal during a slice is safe");
        scheduler.register(0);
        scheduler.sleep(0, 100);
        scheduler.sleep(0, 150);
        check(scheduler.sleepingBytes() == 150 && scheduler.sleepingCount() == 1, "exact replacement accounting");
        scheduler.register(0);
        check(scheduler.sleepingBytes() == 0, "publishing replacement clears sleeping accounting");
        check(!scheduler.shouldSleep(0, false, 1000, 750), "grace starts");
        check(!scheduler.shouldSleep(0, false, 1749, 750), "short occlusion retains geometry");
        check(!scheduler.shouldSleep(0, true, 1750, 750), "relevance cancels grace");
        check(!scheduler.shouldSleep(0, false, 1800, 750), "fresh grace after boundary reversal");
        check(scheduler.shouldSleep(0, false, 2550, 750), "stable irrelevance sleeps");
        scheduler.sleep(0, 100);
        scheduler.clear();
        check(scheduler.sleepingBytes() == 0 && scheduler.pending() == 0, "world change clears everything");
        System.out.println("SceneResidencyScheduler tests PASS");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

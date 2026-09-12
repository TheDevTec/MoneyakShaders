package com.moneyakshaders.render;

import java.util.concurrent.atomic.AtomicLong;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

public final class SceneVisibilityGraphTest {
    public static void main(String[] args) {
        SceneVisibilityGraph graph = new SceneVisibilityGraph(10000);
        graph.begin(0, 0, 0, 3, -1, 1, true);
        AtomicLong time = new AtomicLong();
        LongOpenHashSet discovered = new LongOpenHashSet();
        check(!graph.step(200, 500, () -> time.getAndAdd(100), (x,y,z) -> -1, discovered::add), "time slice yields");
        check(discovered.size() == 5, "five nodes fit fake clock budget");
        while (graph.running()) graph.step(13, 1000, () -> 0, (x,y,z) -> -1, discovered::add);
        LongOpenHashSet published = graph.publish(new LongOpenHashSet());
        check(published.size() == 147, "open volume fully reachable");
        graph.begin(0, 0, 0, 4, -1, 1, true);
        SceneVisibilityGraph.Connectivity detour = (x,y,z) -> {
            if (x == 2 && y == 0 && z == 1) return 1L << (4 * 6 + 2);
            if (x == 2 && y == 0 && z == 0) return 1L << (3 * 6 + 5);
            return 0L;
        };
        while (graph.running()) graph.step(7, 1000, () -> 0, detour, ignored -> {});
        check(published.size() == 147, "old graph is untouched during construction");
        published = graph.publish(published);
        check(published.contains(SceneVisibilityGraph.key(3,0,0)), "revisit from south reveals exit hidden from west");
        check(!published.contains(SceneVisibilityGraph.key(4,0,0)), "closed wall still culls");
        graph.begin(-100, -10, -200, 2, -11, -9, false);
        while (graph.running()) graph.step(9, 1000, () -> 0, (x,y,z) -> { throw new AssertionError("disabled culling reads connectivity"); }, ignored -> {});
        published = graph.publish(published);
        check(published.size() == 75 && published.contains(SceneVisibilityGraph.key(-102,-11,-202)), "signed coordinates and disabled culling");
        SceneVisibilityGraph small = new SceneVisibilityGraph(27);
        small.begin(0,0,0,3,-1,1,true);
        check(small.step(30,1000,() -> 0,(x,y,z) -> -1, ignored -> {}), "overflow terminates");
        check(small.overflow() && small.publish(new LongOpenHashSet()).isEmpty(), "overflow publishes unknown, never partial culling");
        graph.reset();
        check(!graph.running() && graph.pending() == 0, "world reset cancels unfinished work");
        System.out.println("SceneVisibilityGraph tests PASS");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

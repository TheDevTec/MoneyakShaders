package com.moneyakshaders.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Random;

final class OpaqueDrawOrderTest {
    private record Draw(int identity, float distance) {}

    static void run() {
        var order = new OpaqueDrawOrder<Draw>();
        var expected = new ArrayList<Draw>();
        var random = new Random(924831);
        for (int frame = 0; frame < 80; frame++) {
            order.clear(); expected.clear();
            int count = frame == 0 ? 0 : frame == 1 ? 1 : random.nextInt(12000);
            for (int i = 0; i < count; i++) {
                float distance = switch (i % 7) {
                    case 0 -> 0;
                    case 1 -> 65536;
                    case 2 -> Float.MIN_VALUE;
                    case 3 -> Float.MAX_VALUE;
                    default -> random.nextFloat() * 1_000_000;
                };
                Draw draw = new Draw(i, distance);
                expected.add(draw); order.enqueue(draw, distance);
            }
            expected.sort(Comparator.comparingDouble(Draw::distance));
            order.order();
            if (!expected.equals(order)) throw new AssertionError("Stable draw ordering mismatch, frame " + frame);
            order.order();
            if (!expected.equals(order)) throw new AssertionError("Repeated ordering changed equal-distance ties");
        }
        order.clear();
        if (!order.isEmpty()) throw new AssertionError("Reset retained old scene");
        System.out.println("OpaqueDrawOrder tests PASS (80 changing scenes)");
    }
}

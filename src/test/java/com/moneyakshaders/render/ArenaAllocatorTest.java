package com.moneyakshaders.render;

import java.util.ArrayList;
import java.util.Random;

public final class ArenaAllocatorTest {
    public static void main(String[] args) {
        int capacity = 8192;
        ArenaAllocator allocator = new ArenaAllocator(capacity);
        boolean[] used = new boolean[capacity];
        ArrayList<int[]> allocations = new ArrayList<>();
        Random random = new Random(8148);
        for (int step = 0; step < 30000; step++) {
            if (!allocations.isEmpty() && random.nextBoolean()) {
                int[] block = allocations.remove(random.nextInt(allocations.size()));
                allocator.free(block[0], block[1]);
                for (int i = block[0]; i < block[0] + block[1]; i++) used[i] = false;
            } else {
                int size = 1 + random.nextInt(400);
                long offset = allocator.alloc(size);
                if (offset >= 0) {
                    check(offset + size <= capacity, "allocation within capacity");
                    for (int i = (int) offset; i < offset + size; i++) {
                        check(!used[i], "no overlapping allocations"); used[i] = true;
                    }
                    allocations.add(new int[] {(int) offset, size});
                } else check(largest(used) < size, "failure only when no contiguous space fits");
            }
            int free = 0;
            for (boolean occupied : used) if (!occupied) free++;
            check(allocator.freeBytes() == free, "exact free-byte accounting");
            check(allocator.largestFreeBlock() == largest(used), "coalescing matches independent bitmap");
        }
        for (int[] block : allocations) allocator.free(block[0], block[1]);
        check(allocator.alloc(capacity) == 0 && allocator.freeBytes() == 0, "full coalescing after fragmentation");
        check(allocator.alloc(1) == -1 && allocator.largestFreeBlock() == 0, "full arena");
        System.out.println("ArenaAllocator tests PASS");
    }
    private static int largest(boolean[] used) {
        int best = 0, run = 0;
        for (boolean occupied : used) { run = occupied ? 0 : run + 1; best = Math.max(best, run); }
        return best;
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

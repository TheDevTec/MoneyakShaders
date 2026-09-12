package com.moneyakshaders.client;

import java.util.Arrays;

import org.joml.Vector3f;

import com.mojang.blaze3d.systems.VertexSorter;

import net.minecraft.client.util.math.Vec3fArray;

/** Allocation-light stable descending sort for vanilla translucent-quad float keys. */
public final class StableFloatRadixSort {
	private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
	private static final int TEMPORAL_CACHE_SLOTS = 8;
	private static final int TEMPORAL_SORT_MIN_SIZE = 256;
	private static final int INSERTION_SORT_LIMIT = 64;

	private StableFloatRadixSort() {
	}

	public static int[] sortDescending(VertexSorter.SortKeyMapper mapper, Vec3fArray positions) {
		int size = positions.size();
		int[] order = new int[size];
		if (size < 2) {
			if (size == 1) order[0] = 0;
			return order;
		}

		Scratch scratch = SCRATCH.get();
		scratch.ensureCapacity(size);
		Vector3f position = scratch.position;
		for (int i = 0; i < size; i++) {
			float value = mapper.apply(positions.get(i, position));
			int bits = Float.floatToIntBits(value);
			// Float.compare ascending as an unsigned integer key, then invert for vanilla's
			// descending comparator. Equal keys retain input order across all stable passes.
			int ascending = bits ^ ((bits >> 31) | 0x80000000);
			scratch.keys[i] = ~ascending;
			scratch.indices[i] = i;
		}
		if (size < INSERTION_SORT_LIMIT) {
			System.arraycopy(scratch.indices, 0, order, 0, size);
			repairPreviousOrder(order, scratch.keys, size, Long.MAX_VALUE);
			DebugStats.translucentSortSmall.incrementAndGet();
			return order;
		}
		long geometrySignature = geometrySignature(positions, position);

		// ModelCommandRenderer rebuilds the same large resource-pack blended buffers every frame,
		// but vanilla sorts them from scratch. Camera translation normally changes only a small
		// fraction of the prior order, so repair that exact permutation with bounded insertion sort.
		// A teleport or materially different buffer crosses the move budget and falls back to radix;
		// this is always an exact sort, never a stale-order frame throttle.
		TemporalOrder cached = scratch.temporal[(int) geometrySignature & (TEMPORAL_CACHE_SLOTS - 1)];
		if (size >= TEMPORAL_SORT_MIN_SIZE && cached.matches(geometrySignature, size)) {
			System.arraycopy(cached.order, 0, order, 0, size);
			long moves = repairPreviousOrder(order, scratch.keys, size, Math.max(64L, size * 2L));
			if (moves >= 0L) {
				cached.store(geometrySignature, order, size);
				DebugStats.translucentSortReused.incrementAndGet();
				DebugStats.translucentSortMoves.addAndGet(moves);
				return order;
			}
			DebugStats.translucentSortFallback.incrementAndGet();
		}

		// Three wider stable passes move 25% less index traffic than four byte passes. Start in
		// reusable storage so the odd pass count naturally finishes in the caller-owned array.
		int[] source = scratch.indices;
		int[] target = order;
		for (int pass = 0; pass < 3; pass++) {
			int shift = pass * 11;
			int bucketCount = pass == 2 ? 1024 : 2048;
			int mask = bucketCount - 1;
			Arrays.fill(scratch.counts, 0, bucketCount, 0);
			for (int i = 0; i < size; i++) {
				scratch.counts[(scratch.keys[source[i]] >>> shift) & mask]++;
			}
			int offset = 0;
			for (int bucket = 0; bucket < scratch.counts.length; bucket++) {
				int count = scratch.counts[bucket];
				scratch.counts[bucket] = offset;
				offset += count;
			}
			for (int i = 0; i < size; i++) {
				int index = source[i];
				int bucket = (scratch.keys[index] >>> shift) & mask;
				target[scratch.counts[bucket]++] = index;
			}
			int[] swap = source;
			source = target;
			target = swap;
		}
		if (source != order) throw new AssertionError("radix pass parity");
		if (size >= TEMPORAL_SORT_MIN_SIZE) cached.store(geometrySignature, order, size);
		DebugStats.translucentSortRadix.incrementAndGet();
		return order;
	}

	private static long geometrySignature(Vec3fArray positions, Vector3f scratch) {
		int size = positions.size();
		if (size == 0) return 0L;
		positions.get(0, scratch);
		float anchorX = scratch.x;
		float anchorY = scratch.y;
		float anchorZ = scratch.z;
		long hash = mix64(size * 0x9E3779B97F4A7C15L);
		for (int sample = 1; sample <= 7; sample++) {
			int index = (int) ((long) (size - 1) * sample / 7L);
			positions.get(index, scratch);
			// Relative, lightly quantized coordinates survive camera-relative float translation while
			// still separating differently-shaped buffers. A collision affects speed only: the repair
			// pass validates every current key and either fully sorts or falls back.
			hash = mix64(hash ^ Math.round((scratch.x - anchorX) * 64.0f));
			hash = mix64(hash ^ Math.round((scratch.y - anchorY) * 64.0f));
			hash = mix64(hash ^ Math.round((scratch.z - anchorZ) * 64.0f));
		}
		return hash;
	}

	private static long repairPreviousOrder(int[] order, int[] keys, int size, long moveBudget) {
		long moves = 0L;
		for (int i = 1; i < size; i++) {
			int index = order[i];
			int key = keys[index];
			int j = i - 1;
			while (j >= 0) {
				int previous = order[j];
				int comparison = Integer.compareUnsigned(keys[previous], key);
				if (comparison < 0 || (comparison == 0 && previous < index)) break;
				order[j + 1] = previous;
				j--;
				if (++moves > moveBudget) return -1L;
			}
			order[j + 1] = index;
		}
		return moves;
	}

	private static long mix64(long value) {
		value ^= value >>> 33;
		value *= 0xff51afd7ed558ccdl;
		value ^= value >>> 33;
		value *= 0xc4ceb9fe1a85ec53l;
		return value ^ (value >>> 33);
	}

	private static final class Scratch {
		final Vector3f position = new Vector3f();
		final int[] counts = new int[2048];
		final TemporalOrder[] temporal = new TemporalOrder[TEMPORAL_CACHE_SLOTS];
		int[] keys = new int[0];
		int[] indices = new int[0];

		Scratch() {
			Arrays.setAll(temporal, ignored -> new TemporalOrder());
		}

		void ensureCapacity(int size) {
			if (keys.length >= size) return;
			int capacity = Integer.highestOneBit(size - 1) << 1;
			keys = new int[capacity];
			indices = new int[capacity];
		}
	}

	private static final class TemporalOrder {
		long signature;
		int size = -1;
		int[] order = new int[0];

		boolean matches(long candidateSignature, int candidateSize) {
			return signature == candidateSignature && size == candidateSize;
		}

		void store(long newSignature, int[] source, int newSize) {
			if (order.length < newSize) order = new int[newSize];
			System.arraycopy(source, 0, order, 0, newSize);
			signature = newSignature;
			size = newSize;
		}
	}
}

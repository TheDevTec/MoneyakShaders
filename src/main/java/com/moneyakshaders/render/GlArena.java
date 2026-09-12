package com.moneyakshaders.render;




import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL44;

/**
 * Plan C / Phase 5 — a single large GL buffer that many sections sub-allocate from,
 * so the whole terrain lives in one buffer and can be drawn with one
 * {@code glMultiDrawElementsIndirect} call. Simple best-fit free-range allocator
 * with neighbour coalescing on free. Fixed capacity (we trade RAM for fewer draw
 * calls); {@link #alloc} returns -1 when full and the caller skips that section.
 */
final class GlArena {
	private final int target;
	private final int buffer;
	private final long capacity;
	/** free regions as offset -> endExclusive, kept sorted by offset for coalescing. */
	private final ArenaAllocator allocator;

	GlArena(int target, long capacity) {
		this.target = target;
		this.capacity = capacity;
		this.buffer = GL15.glGenBuffers();
		GL15.glBindBuffer(target, buffer);
		// Immutable storage: AMD driver is fastest on this path (avoids mutable-buffer respecification).
		// GL_DYNAMIC_STORAGE_BIT allows glBufferSubData on immutable storage.
		GL44.glBufferStorage(target, capacity, GL44.GL_DYNAMIC_STORAGE_BIT);
		GL15.glBindBuffer(target, 0);
		allocator = new ArenaAllocator(capacity);
	}

	int buffer() {
		return buffer;
	}

	long capacity() {
		return capacity;
	}

	long freeBytes() { return allocator.freeBytes(); }
	long largestFreeBlock() { return allocator.largestFreeBlock(); }
	long alloc(long size) { return allocator.alloc(size); }
	void free(long offset, long size) { allocator.free(offset, size); }
	/** Upload bytes at {@code offset} (must lie inside an allocation made here). */
	void upload(long offset, java.nio.ByteBuffer data) {
		GL15.glBindBuffer(target, buffer);
		GL15.glBufferSubData(target, offset, data);
		GL15.glBindBuffer(target, 0);
	}

	/**
	 * Upload a bounded slice of a larger staging buffer.  Dense resource-pack sections can contain
	 * several MiB of vertices; issuing that as one driver call makes a single section exceed the
	 * frame budget even when the outer queue is time-limited.  The caller keeps the allocation hidden
	 * from draw commands until every slice is present, so partial data can never become visible.
	 */
	void uploadSlice(long offset, java.nio.ByteBuffer source, int sourceOffset, int length) {
		if (length <= 0) return;
		java.nio.ByteBuffer slice = source.duplicate();
		slice.position(sourceOffset);
		slice.limit(sourceOffset + length);
		GL15.glBindBuffer(target, buffer);
		GL15.glBufferSubData(target, offset, slice);
		GL15.glBindBuffer(target, 0);
	}

	/** Upload ints (e.g. indices) at {@code offset}. */
	void uploadInts(long offset, java.nio.IntBuffer data) {
		GL15.glBindBuffer(target, buffer);
		GL15.glBufferSubData(target, offset, data);
		GL15.glBindBuffer(target, 0);
	}

	/** Read {@code dst.remaining()} bytes back from {@code offset} into {@code dst} (GPU→CPU, syncs).
	 *  Used only by the disk section cache at leave-time to serialise a section's meshed bytes. */
	void read(long offset, java.nio.ByteBuffer dst) {
		GL15.glBindBuffer(target, buffer);
		GL15.glGetBufferSubData(target, offset, dst);
		GL15.glBindBuffer(target, 0);
	}
}

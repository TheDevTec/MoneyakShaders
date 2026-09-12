package com.moneyakshaders.render;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * Non-blocking GPU pass timings.
 *
 * Results are consumed only after a small frame delay so profiling never
 * intentionally turns into a CPU/GPU synchronization point.
 */
final class GpuPassProfiler {

	private static final int RING = 6;
	private static final int READ_DELAY = 3;
	private static final int HISTORY = 120;

	/**
	 * After this many frames without a newly submitted measurement the cached
	 * timing is considered irrelevant.
	 */
	private static final int STALE_FRAMES = 120;

	private final Map<String, Slot[]> passes = new HashMap<>();

	private final Map<String, Double> averagesMs = new HashMap<>();
	private final Map<String, Double> latestMs = new HashMap<>();

	private final Map<String, History> histories = new HashMap<>();
	private final Map<String, Integer> lastSubmitted = new HashMap<>();
	private final Map<String, Integer> lastResolved = new HashMap<>();

	private final Map<String, PassCadence> cadences = new HashMap<>();

	private int frame;

	void beginFrame() {
		frame++;

		for (Map.Entry<String, Slot[]> entry : passes.entrySet()) {
			String name = entry.getKey();
			Slot[] ring = entry.getValue();

			/*
			 * Find the oldest query which has been pending long enough.
			 *
			 * Unlike indexing one fixed ring position, this retries the same
			 * query next frame if the GPU had not finished it yet.
			 */
			Slot oldest = null;

			for (Slot slot : ring) {
				if (!slot.pending)
					continue;

				if (frame - slot.submittedFrame < READ_DELAY)
					continue;

				if (oldest == null
						|| slot.submittedFrame < oldest.submittedFrame) {

					oldest = slot;
				}
			}

			if (oldest == null)
				continue;

			/*
			 * Never request GL_QUERY_RESULT unless the driver explicitly says
			 * the query has completed.
			 */
			if (GL15.glGetQueryObjecti(
					oldest.query,
					GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {

				continue;
			}

			double ms =
					GL33.glGetQueryObjectui64(
							oldest.query,
							GL15.GL_QUERY_RESULT)
							/ 1_000_000.0;

			latestMs.put(name, ms);
			lastResolved.put(name, frame);

			double old =
					averagesMs.getOrDefault(
							name,
							ms);

			averagesMs.put(
					name,
					old * 0.8
							+ ms * 0.2);

			histories
					.computeIfAbsent(
							name,
							ignored -> new History())
					.add(ms);

			oldest.pending = false;
			oldest.submittedFrame = Integer.MIN_VALUE;
		}
	}

	void begin(String name) {
		cadences
				.computeIfAbsent(
						name,
						ignored -> new PassCadence())
				.invoke(frame);

		Slot slot = slot(name);

		/*
		 * If this ring position is still waiting for the GPU, simply skip this
		 * sample. Never block rendering just to obtain profiler data.
		 */
		if (slot.pending)
			return;

		slot.submittedFrame = frame;

		lastSubmitted.put(
				name,
				frame);

		GL15.glBeginQuery(
				GL33.GL_TIME_ELAPSED,
				slot.query);
	}

	void end(String name) {
		Slot slot = slot(name);

		/*
		 * submittedFrame == frame means begin(name) actually opened this
		 * query during the current frame.
		 *
		 * This is safer than testing only !pending: if begin() skipped because
		 * the selected ring slot was occupied, end() must not accidentally end
		 * some unrelated GL_TIME_ELAPSED query.
		 */
		if (slot.pending
				|| slot.submittedFrame != frame) {

			return;
		}

		GL15.glEndQuery(
				GL33.GL_TIME_ELAPSED);

		slot.pending = true;
	}

	double latestMs(String name) {
		int resolved =
				lastResolved.getOrDefault(
						name,
						Integer.MIN_VALUE);

		if (resolved == Integer.MIN_VALUE
				|| frame - resolved > STALE_FRAMES) {

			return 0.0;
		}

		return latestMs.getOrDefault(
				name,
				0.0);
	}

	double averageMs(String name) {
		int submitted =
				lastSubmitted.getOrDefault(
						name,
						Integer.MIN_VALUE);

		if (submitted == Integer.MIN_VALUE
				|| frame - submitted > STALE_FRAMES) {

			return 0.0;
		}

		return averagesMs.getOrDefault(
				name,
				0.0);
	}

	double workloadMs(String name) {
		PassCadence cadence =
				cadences.get(name);

		if (cadence == null)
			return 0.0;

		return cadence.amortized(
				averageMs(name));
	}

	void resetMeasurements() {
		cadences.clear();

		latestMs.clear();
		averagesMs.clear();

		histories.clear();

		lastSubmitted.clear();
		lastResolved.clear();

		/*
		 * Discard old-world profiler ownership without waiting for the GPU.
		 *
		 * Query objects themselves remain allocated and are reused.
		 */
		for (Slot[] ring : passes.values()) {
			for (Slot slot : ring) {
				slot.pending = false;
				slot.submittedFrame = Integer.MIN_VALUE;
			}
		}
	}

	double p50Ms(String name) {
		return percentileMs(
				name,
				0.50);
	}

	double p95Ms(String name) {
		return percentileMs(
				name,
				0.95);
	}

	double p99Ms(String name) {
		return percentileMs(
				name,
				0.99);
	}

	private double percentileMs(
			String name,
			double percentile) {

		History history =
				histories.get(name);

		return history == null
				? 0.0
				: history.percentile(percentile);
	}

	double shadowMs() {
		return averageMs("shadow-near")
				+ averageMs("shadow-far")
				+ averageMs("shadow-point");
	}

	private Slot slot(String name) {
		Slot[] ring =
				passes.computeIfAbsent(
						name,
						ignored -> {

							Slot[] made =
									new Slot[RING];

							for (int i = 0; i < RING; i++) {
								made[i] =
										new Slot(
												GL15.glGenQueries());
							}

							return made;
						});

		return ring[frame % RING];
	}

	private static final class Slot {

		final int query;

		boolean pending;

		/*
		 * Frame in which glBeginQuery() was issued.
		 *
		 * Also doubles as an "active this frame" marker between begin/end.
		 */
		int submittedFrame =
				Integer.MIN_VALUE;

		Slot(int query) {
			this.query = query;
		}
	}

	/**
	 * Small render-thread history.
	 *
	 * Sorting happens only when percentile diagnostics are requested, never
	 * while consuming ordinary GPU timing results.
	 */
	private static final class History {

		private final double[] values =
				new double[HISTORY];

		private int count;
		private int cursor;

		void add(double value) {
			values[cursor++ % HISTORY] =
					value;

			if (count < HISTORY) {
				count++;
			}
		}

		double percentile(
				double percentile) {

			if (count == 0)
				return 0.0;

			double[] sorted =
					Arrays.copyOf(
							values,
							count);

			Arrays.sort(sorted);

			int index =
					Math.min(
							count - 1,
							(int) Math.floor(
									(count - 1)
											* percentile));

			return sorted[index];
		}
	}
}
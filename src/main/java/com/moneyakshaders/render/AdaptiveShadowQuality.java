package com.moneyakshaders.render;

final class AdaptiveShadowQuality {
	private static final long SAMPLE_MS = 500L;
	private static final long DOWN_COOLDOWN_MS = 1200L;
	private static final long UP_COOLDOWN_MS = 8000L;
	private static final double OVER_THRESHOLD = 1.08;
	private static final double SEVERE_OVER_THRESHOLD = 1.45;
	private static final double UNDER_THRESHOLD = 0.58;
	private static final double FILTER_ALPHA = 0.35;

	private long nextSampleMs = Long.MIN_VALUE;
	private long nextChangeMs;
	private int overSamples;
	private int underSamples;
	private int level;
	private double filteredMs = Double.NaN;

	void reset() {
		nextSampleMs = Long.MIN_VALUE;
		nextChangeMs = 0L;
		overSamples = 0;
		underSamples = 0;
		level = 0;
		filteredMs = Double.NaN;
	}

	int level() {
		return level;
	}

	int update(long nowMs, boolean enabled, double measuredMs, double budgetMs, int maxLevel) {
		if (!enabled) {
			reset();
			return 0;
		}

		maxLevel = Math.max(0, maxLevel);
		level = Math.min(level, maxLevel);

		if (nextSampleMs == Long.MIN_VALUE) {
			nextSampleMs = nowMs + SAMPLE_MS;
			nextChangeMs = nowMs + DOWN_COOLDOWN_MS;
			return level;
		}
		if (nowMs < nextSampleMs) return level;

		if (nowMs - nextSampleMs > 2000L) {
			overSamples = 0;
			underSamples = 0;
			filteredMs = Double.NaN;
		}
		nextSampleMs = nowMs + SAMPLE_MS;

		if (!Double.isFinite(measuredMs) || measuredMs <= 0.0 || !Double.isFinite(budgetMs) || budgetMs <= 0.0) {
			overSamples = 0;
			underSamples = 0;
			return level;
		}

		filteredMs = Double.isFinite(filteredMs) ? mix(filteredMs, measuredMs, FILTER_ALPHA) : measuredMs;
		double ratio = filteredMs / budgetMs;
		boolean severe = ratio > SEVERE_OVER_THRESHOLD;
		boolean overloaded = ratio > OVER_THRESHOLD;
		boolean under = ratio < UNDER_THRESHOLD;

		if (overloaded) {
			overSamples++;
			underSamples = 0;
		} else if (under) {
			underSamples++;
			overSamples = 0;
		} else {
			overSamples = 0;
			underSamples = 0;
		}

		int requiredOverSamples = severe ? 2 : 3;
		if (level < maxLevel && nowMs >= nextChangeMs && overSamples >= requiredOverSamples) {
			level++;
			overSamples = 0;
			underSamples = 0;
			nextChangeMs = nowMs + DOWN_COOLDOWN_MS;
			return level;
		}

		if (level > 0 && nowMs >= nextChangeMs && underSamples >= 12) {
			level--;
			overSamples = 0;
			underSamples = 0;
			nextChangeMs = nowMs + UP_COOLDOWN_MS;
		}
		return level;
	}

	static int resolutionAtLevel(int requested, int floor, int level) {
		requested = Math.max(1, requested);
		floor = Math.max(1, Math.min(floor, requested));
		for (int i = 0; i < level && requested > floor; i++) requested = Math.max(floor, requested >> 1);
		return requested;
	}

	static int maxLevel(int requested, int floor) {
		requested = Math.max(1, requested);
		floor = Math.max(1, Math.min(floor, requested));
		int level = 0;
		while (requested > floor && level < 6) {
			requested = Math.max(floor, requested >> 1);
			level++;
		}
		return level;
	}

	static int maxLevel(int[] requested, int floor) {
		if (requested == null || requested.length == 0) return 0;
		int max = 0;
		for (int resolution : requested) max = Math.max(max, maxLevel(resolution, floor));
		return max;
	}

	private static double mix(double a, double b, double t) {
		return a + (b - a) * t;
	}
}
package com.moneyakshaders.render;

/**
 * Dependency-free regression test; throws on failure (does not require -ea).
 */
public final class AdaptiveShadowQualityTest {
	public static void main(String[] args) {
		testSustainedOverload();
		testSlowRecovery();
		testManualReset();
		testResolutionFloor();
		testIsolatedSpikes();
		testHysteresis();
		System.out.println("AdaptiveShadowQuality tests PASS");
	}

	private static void testSustainedOverload() {
		AdaptiveShadowQuality q = new AdaptiveShadowQuality();

		check(q.update(0, true, 20, 8, 2) == 0, "initial quality");
		check(q.update(500, true, 20, 8, 2) == 0, "first overload sample");
		check(q.update(1000, true, 20, 8, 2) == 0, "cooldown protects first change");
		check(q.update(1500, true, 20, 8, 2) == 1, "first sustained-load downgrade");

		check(q.update(2000, true, 20, 8, 2) == 1, "second level first sample");
		check(q.update(2500, true, 20, 8, 2) == 1, "second level cooldown");
		check(q.update(3000, true, 20, 8, 2) == 2, "second sustained-load downgrade");
		check(q.update(3500, true, 20, 8, 2) == 2, "maximum quality reduction");
	}

	private static void testSlowRecovery() {
		AdaptiveShadowQuality q = new AdaptiveShadowQuality();

		q.update(0, true, 20, 8, 2);
		q.update(500, true, 20, 8, 2);
		q.update(1000, true, 20, 8, 2);
		q.update(1500, true, 20, 8, 2);
		q.update(2000, true, 20, 8, 2);
		q.update(2500, true, 20, 8, 2);
		q.update(3000, true, 20, 8, 2);

		check(q.level() == 2, "must reach level 2 before recovery");

		for (int t = 3500; t < 11000; t += 500)
			check(q.update(t, true, 2, 8, 2) == 2, "quality must stay reduced during filtered recovery");

		check(q.update(11000, true, 2, 8, 2) == 1, "first recovery");

		for (int t = 11500; t < 19000; t += 500)
			check(q.update(t, true, 2, 8, 2) == 1, "recovery cooldown");

		check(q.update(19000, true, 2, 8, 2) == 0, "full quality recovery");
	}

	private static void testManualReset() {
		AdaptiveShadowQuality q = new AdaptiveShadowQuality();

		q.update(0, true, 20, 8, 2);
		q.update(500, true, 20, 8, 2);
		q.update(1000, true, 20, 8, 2);
		q.update(1500, true, 20, 8, 2);

		check(q.level() == 1, "quality reduced before reset");
		check(q.update(2000, false, 20, 8, 2) == 0, "manual reset");
		check(q.level() == 0, "level reset");
		check(q.update(2500, true, 20, 8, 2) == 0, "fresh warmup after reset");
	}

	private static void testResolutionFloor() {
		check(AdaptiveShadowQuality.maxLevel(2048, 1024) == 1, "resolution floor");
		check(AdaptiveShadowQuality.maxLevel(512, 512) == 0, "minimum resolution");
		check(AdaptiveShadowQuality.maxLevel(4096, 512) == 3, "multiple resolution levels");

		check(AdaptiveShadowQuality.resolutionAtLevel(4096, 1024, 0) == 4096, "resolution level 0");
		check(AdaptiveShadowQuality.resolutionAtLevel(4096, 1024, 1) == 2048, "resolution level 1");
		check(AdaptiveShadowQuality.resolutionAtLevel(4096, 1024, 2) == 1024, "resolution level 2");
		check(AdaptiveShadowQuality.resolutionAtLevel(4096, 1024, 3) == 1024, "resolution floor preserved");

		check(AdaptiveShadowQuality.maxLevel(new int[] {4096, 3072, 2048}, 1024) == 2,
				"three cascade maximum level");
	}

	private static void testIsolatedSpikes() {
		AdaptiveShadowQuality q = new AdaptiveShadowQuality();

		for (int t = 0; t <= 20000; t += 500) {
			double measured = t > 0 && t % 2000 == 0 ? 20.0 : 5.0;
			check(q.update(t, true, measured, 8, 2) == 0, "isolated spikes");
		}
	}

	private static void testHysteresis() {
		AdaptiveShadowQuality q = new AdaptiveShadowQuality();

		for (int t = 0; t <= 10000; t += 500)
			check(q.update(t, true, 8.2, 8, 2) == 0, "hysteresis");
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
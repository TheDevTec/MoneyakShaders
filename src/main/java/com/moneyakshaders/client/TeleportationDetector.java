package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.util.math.Vec3d;

/**
 * Detects anomalous teleportation caused by buggy optimizations.
 * Monitors player position jumps that indicate optimization-related issues.
 *
 * <p>If detected, logs incident and disables problematic optimization.
 */
public final class TeleportationDetector {
	private static volatile TeleportationDetector instance;

	private final ConcurrentHashMap<String, PlayerState> playerStates = new ConcurrentHashMap<>();
	private final AtomicLong totalAnomaliesDetected = new AtomicLong(0);

	private static final float TELEPORT_THRESHOLD = 10.0f; // 10 block distance = teleport
	private static final long DEBOUNCE_MS = 100;

	private TeleportationDetector() {
	}

	public static TeleportationDetector getInstance() {
		if (instance == null) {
			synchronized (TeleportationDetector.class) {
				if (instance == null) {
					instance = new TeleportationDetector();
				}
			}
		}
		return instance;
	}

	/**
	 * Check for anomalous player movement (main thread safe).
	 */
	public TeleportationAnomaly checkPlayerMovement(String playerId, Vec3d currentPos) {
		long now = System.currentTimeMillis();
		PlayerState state = playerStates.getOrDefault(playerId, new PlayerState(currentPos, now));

		// Check if enough time has passed to avoid false positives
		if (now - state.lastCheckMs < DEBOUNCE_MS) {
			return TeleportationAnomaly.NONE;
		}

		// Compute distance moved
		double dx = currentPos.x - state.lastPos.x;
		double dy = currentPos.y - state.lastPos.y;
		double dz = currentPos.z - state.lastPos.z;
		float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

		// Check for anomalies
		TeleportationAnomaly anomaly = TeleportationAnomaly.NONE;

		if (distance > TELEPORT_THRESHOLD) {
			// Possible teleportation
			anomaly = classifyAnomaly(state, currentPos, distance);
		}

		// Update state
		playerStates.put(playerId, new PlayerState(currentPos, now));

		if (anomaly != TeleportationAnomaly.NONE) {
			totalAnomaliesDetected.incrementAndGet();
		}

		return anomaly;
	}

	private TeleportationAnomaly classifyAnomaly(PlayerState state, Vec3d currentPos, float distance) {
		// Check for characteristic patterns

		// Pattern 1: Horizontal teleport with no vertical change = phasing through wall
		if (Math.abs(currentPos.y - state.lastPos.y) < 0.5 && distance > 5.0f) {
			return TeleportationAnomaly.HORIZONTAL_PHASE;
		}

		// Pattern 2: Vertical teleport = falling through floor or ceiling phase
		if (Math.abs(currentPos.y - state.lastPos.y) > 10.0f) {
			return TeleportationAnomaly.VERTICAL_PHASE;
		}

		// Pattern 3: Diagonal teleport = corner phase or occlusion fault
		if (distance > TELEPORT_THRESHOLD) {
			return TeleportationAnomaly.CORNER_PHASE;
		}

		return TeleportationAnomaly.NONE;
	}

	/**
	 * Get statistics on detected anomalies.
	 */
	public long getTotalAnomaliesDetected() {
		return totalAnomaliesDetected.get();
	}

	/**
	 * Clear state (on world change).
	 */
	public void clearState() {
		playerStates.clear();
		totalAnomaliesDetected.set(0);
	}

	/**
	 * Teleportation anomaly classification.
	 */
	public enum TeleportationAnomaly {
		NONE(false),
		HORIZONTAL_PHASE(true), // Phasing through walls/glass
		VERTICAL_PHASE(true), // Falling through floors
		CORNER_PHASE(true); // Corner clipping

		public final boolean isCritical;

		TeleportationAnomaly(boolean isCritical) {
			this.isCritical = isCritical;
		}
	}

	/**
	 * Player position state.
	 */
	private static final class PlayerState {
		Vec3d lastPos;
		long lastCheckMs;

		PlayerState(Vec3d pos, long checkMs) {
			this.lastPos = pos;
			this.lastCheckMs = checkMs;
		}
	}
}

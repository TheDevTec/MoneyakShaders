package com.moneyakshaders.client;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Distance-aware entity animation LOD.
 *
 * <p>Entity movement, render-state extraction, textures, light, overlays and vertex submission remain
 * live every frame. Only the expensive {@link Model#setAngles(Object)} result is reused between
 * bounded refreshes. The cache is per entity and per shared model instance, because vanilla renderer
 * models are mutated and shared by every entity of a type.
 */
public final class EntityLODOptimizer {
	private static final EntityLODOptimizer INSTANCE = new EntityLODOptimizer();
	private static final int MAX_POSED_ENTITIES = 4096;
	private static final int MAX_PARTS_PER_MODEL = 2048;
	private static final long IDLE_EVICTION_NS = 30_000_000_000L;
	private static final long SWEEP_INTERVAL_NS = 2_000_000_000L;

	/* Kept for the older coordinator API; the live pose path below does not enqueue worker tasks. */
	private final ConcurrentHashMap<Integer, EntityLODLevel> entityLODCache = new ConcurrentHashMap<>();
	private final LinkedHashMap<Integer, Timeline> timelines = new LinkedHashMap<>(256, 0.75F, true);
	private final LinkedHashMap<Integer, EntityPoseCache> poses = new LinkedHashMap<>(256, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, EntityPoseCache> eldest) {
			return size() > MAX_POSED_ENTITIES;
		}
	};
	private long nextSweepNs;

	private EntityLODOptimizer() {
	}

	public static EntityLODOptimizer getInstance() {
		return INSTANCE;
	}

	/** Decides once while the state still has its source entity, before deferred command execution. */
	public static void associateState(EntityRenderState state, Entity entity) {
		INSTANCE.associate(state, entity);
	}

	private void associate(EntityRenderState state, Entity entity) {
		if (!(state instanceof OplAnimationLodState carrier)) return;
		carrier.moneyakshaders$setAnimationLod(entity.getId(), entity.getUuid(), false, true);
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (!config.experimentalRenderer
				|| !config.entityAnimationLod
				|| !(state instanceof LivingEntityRenderState livingState)
				|| !(entity instanceof LivingEntity)
				|| entity instanceof PlayerEntity) {
			return;
		}

		long nowNs = System.nanoTime();
		sweepIdle(nowNs);
		boolean forceLive = livingState.hurt
				|| livingState.deathTime > 0.0F
				|| livingState.usingRiptide
				|| livingState.shaking
				|| livingState.onFire
				|| livingState.hasOutline()
				|| livingState.leashDatas != null
				|| (livingState.timeSinceLastKineticAttack > 0.0F
						&& livingState.timeSinceLastKineticAttack < 10.0F)
				|| entity.hasVehicle()
				|| entity.hasPassengers();

		double distanceSq = Math.max(0.0, livingState.squaredDistanceToCamera);
		int nearDistance = Math.max(0, config.entityAnimationNearDistance);
		int midDistance = Math.max(nearDistance, config.entityAnimationMidDistance);
		boolean refreshPose;
		if (forceLive || distanceSq <= (double) nearDistance * nearDistance) {
			refreshPose = true;
		} else {
			int fps = distanceSq <= (double) midDistance * midDistance
					? config.entityAnimationMidFps : config.entityAnimationFarFps;
			fps = Math.max(1, Math.min(240, fps));
			long intervalNs = 1_000_000_000L / fps;
			Timeline timeline = timelines.get(entity.getId());
			boolean newTimeline = timeline == null || !timeline.uuid.equals(entity.getUuid());
			if (newTimeline) {
				timeline = new Timeline(entity.getUuid());
				timelines.put(entity.getId(), timeline);
			}
			timeline.lastSeenNs = nowNs;
			refreshPose = nowNs >= timeline.nextRefreshNs;
			if (refreshPose) {
				// The first visible frame must produce a real pose, but scheduling every newly
				// discovered RP entity for exactly now + interval makes whole crowds refresh in
				// one periodic wave. Give only the first interval a stable per-entity phase;
				// later refreshes retain the configured animation rate without group spikes.
				timeline.nextRefreshNs = nowNs + (newTimeline
						? staggeredFirstInterval(entity, intervalNs)
						: intervalNs);
			}
		}
		carrier.moneyakshaders$setAnimationLod(entity.getId(), entity.getUuid(), true, refreshPose);
	}

	private static long staggeredFirstInterval(Entity entity, long intervalNs) {
		long hash = entity.getUuid().getMostSignificantBits()
				^ entity.getUuid().getLeastSignificantBits()
				^ ((long) entity.getId() * 0x9E3779B97F4A7C15L);
		// 50..149 % keeps the average first interval approximately unchanged while
		// distributing equal-rate entities over almost one complete refresh period.
		long percent = 50L + Math.floorMod(hash, 100L);
		return Math.max(1L, intervalNs / 100L * percent);
	}

	/** Returns true when this optimizer ran or restored the pose; false requests vanilla setAngles. */
	public static boolean applyPose(Model<?> model, Object state, Runnable vanillaSetAngles) {
		return INSTANCE.apply(model, state, vanillaSetAngles);
	}

	private boolean apply(Model<?> model, Object state, Runnable vanillaSetAngles) {
		// Resource packs animate vanilla carrier models. Arbitrary mod Java models may mutate custom
		// state outside ModelPart transforms, which cannot be restored safely by this generic cache.
		if (!model.getClass().getName().startsWith("net.minecraft.")
				|| model.getParts().size() > MAX_PARTS_PER_MODEL
				|| !(state instanceof OplAnimationLodState carrier)
				|| !carrier.moneyakshaders$animationLodEnabled()
				|| carrier.moneyakshaders$entityId() < 0
				|| carrier.moneyakshaders$entityUuid() == null) {
			return false;
		}
		long nowNs = System.nanoTime();
		EntityPoseCache entityCache = poses.get(carrier.moneyakshaders$entityId());
		if (entityCache == null || !entityCache.uuid.equals(carrier.moneyakshaders$entityUuid())) {
			entityCache = new EntityPoseCache(carrier.moneyakshaders$entityUuid());
			poses.put(carrier.moneyakshaders$entityId(), entityCache);
		}
		entityCache.lastSeenNs = nowNs;
		Pose pose = entityCache.models.get(model);

		// Features pose the context model before the deferred queue asks for that exact state again.
		if (pose != null && pose.lastState == state && pose.matches(model)) {
			restoreTimed(model, pose);
			return true;
		}
		if (!carrier.moneyakshaders$refreshPose() && pose != null && pose.matches(model)) {
			restoreTimed(model, pose);
			pose.lastState = state;
			return true;
		}

		boolean debug = MoneyakShadersConfig.get().debugStats;
		long startNs = debug ? System.nanoTime() : 0L;
		vanillaSetAngles.run();
		if (debug) {
			DebugStats.animationPoseUpdates.incrementAndGet();
			DebugStats.animationPoseUpdateNanos.addAndGet(System.nanoTime() - startNs);
			if (!carrier.moneyakshaders$refreshPose()) DebugStats.animationPoseWarmups.incrementAndGet();
		}
		if (pose != null && pose.matches(model)) {
			pose.captureFrom(model, state);
		} else {
			entityCache.models.put(model, Pose.capture(model, state));
		}
		return true;
	}

	private static void restoreTimed(Model<?> model, Pose pose) {
		boolean debug = MoneyakShadersConfig.get().debugStats;
		long startNs = debug ? System.nanoTime() : 0L;
		pose.restore(model);
		if (debug) {
			DebugStats.animationPoseReplays.incrementAndGet();
			DebugStats.animationPoseReplayNanos.addAndGet(System.nanoTime() - startNs);
		}
	}

	private void sweepIdle(long nowNs) {
		if (nowNs < nextSweepNs) return;
		nextSweepNs = nowNs + SWEEP_INTERVAL_NS;
		sweep(timelines, nowNs);
		sweep(poses, nowNs);
	}

	private static <T extends Timed> void sweep(LinkedHashMap<Integer, T> map, long nowNs) {
		Iterator<T> iterator = map.values().iterator();
		while (iterator.hasNext()) {
			T value = iterator.next();
			if (nowNs - value.lastSeenNs() <= IDLE_EVICTION_NS) break;
			iterator.remove();
		}
	}

	public static int poseCacheSize() {
		return INSTANCE.poses.size();
	}

	/** Legacy coordinator entry point; synchronous because this arithmetic is cheaper than a task. */
	public void queueEntityLODComputation(int entityId, float distanceToCamera) {
		int lodLevel = distanceToCamera <= 16.0F ? 0 : distanceToCamera <= 32.0F ? 1
				: distanceToCamera <= 64.0F ? 2 : distanceToCamera <= 128.0F ? 3 : 4;
		entityLODCache.put(entityId, new EntityLODLevel(
				entityId, lodLevel, lodLevel, Math.max(20, 100 - lodLevel * 20), distanceToCamera));
	}

	public EntityLODLevel getLODLevel(int entityId) { return entityLODCache.get(entityId); }

	public EntityStats getStats() {
		int total = entityLODCache.size();
		return new EntityStats(total, total * 40L);
	}

	public void clearCache() {
		entityLODCache.clear();
		timelines.clear();
		poses.clear();
	}

	private interface Timed { long lastSeenNs(); }

	private static final class Timeline implements Timed {
		final UUID uuid;
		long nextRefreshNs;
		long lastSeenNs;
		Timeline(UUID uuid) { this.uuid = uuid; }
		@Override public long lastSeenNs() { return lastSeenNs; }
	}

	private static final class EntityPoseCache implements Timed {
		final UUID uuid;
		final IdentityHashMap<Model<?>, Pose> models = new IdentityHashMap<>();
		long lastSeenNs;
		EntityPoseCache(UUID uuid) { this.uuid = uuid; }
		@Override public long lastSeenNs() { return lastSeenNs; }
	}

	private static final class Pose {
		private static final int FLOATS_PER_PART = 9;
		final float[] transforms;
		final byte[] flags;
		Object lastState;

		private Pose(int partCount, Object state) {
			this.transforms = new float[partCount * FLOATS_PER_PART];
			this.flags = new byte[partCount];
			this.lastState = state;
		}

		static Pose capture(Model<?> model, Object state) {
			Pose pose = new Pose(model.getParts().size(), state);
			pose.captureFrom(model, state);
			return pose;
		}

		void captureFrom(Model<?> model, Object state) {
			this.lastState = state;
			int i = 0;
			for (ModelPart part : model.getParts()) {
				int o = i * FLOATS_PER_PART;
				transforms[o] = part.originX;
				transforms[o + 1] = part.originY;
				transforms[o + 2] = part.originZ;
				transforms[o + 3] = part.pitch;
				transforms[o + 4] = part.yaw;
				transforms[o + 5] = part.roll;
				transforms[o + 6] = part.xScale;
				transforms[o + 7] = part.yScale;
				transforms[o + 8] = part.zScale;
				flags[i] = (byte) ((part.visible ? 1 : 0) | (part.hidden ? 2 : 0));
				i++;
			}
		}

		boolean matches(Model<?> model) { return model.getParts().size() == flags.length; }

		void restore(Model<?> model) {
			int i = 0;
			for (ModelPart part : model.getParts()) {
				int o = i * FLOATS_PER_PART;
				part.originX = transforms[o];
				part.originY = transforms[o + 1];
				part.originZ = transforms[o + 2];
				part.pitch = transforms[o + 3];
				part.yaw = transforms[o + 4];
				part.roll = transforms[o + 5];
				part.xScale = transforms[o + 6];
				part.yScale = transforms[o + 7];
				part.zScale = transforms[o + 8];
				part.visible = (flags[i] & 1) != 0;
				part.hidden = (flags[i] & 2) != 0;
				i++;
			}
		}
	}

	public static final class EntityLODLevel {
		public final int entityId;
		public final int lodLevel;
		public final int detailLevel;
		public final int modelQuality;
		public final float distanceToCamera;
		public EntityLODLevel(int entityId, int lodLevel, int detailLevel, int modelQuality, float distanceToCamera) {
			this.entityId = entityId;
			this.lodLevel = lodLevel;
			this.detailLevel = detailLevel;
			this.modelQuality = modelQuality;
			this.distanceToCamera = distanceToCamera;
		}
		public boolean shouldSkipAnimations() { return lodLevel >= 3; }
		public boolean shouldSkipShadow() { return lodLevel >= 4; }
		public float getAnimationSpeed() { return 1.0F - lodLevel * 0.15F; }
	}

	public static final class EntityStats {
		public final int cachedEntities;
		public final long estimatedRamBytes;
		public EntityStats(int cachedEntities, long estimatedRamBytes) {
			this.cachedEntities = cachedEntities;
			this.estimatedRamBytes = estimatedRamBytes;
		}
		public String getRamUsedMB() {
			return String.format("%.2f MB", estimatedRamBytes / 1024.0 / 1024.0);
		}
	}
}

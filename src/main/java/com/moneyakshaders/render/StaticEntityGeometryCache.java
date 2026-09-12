package com.moneyakshaders.render;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.DebugStats;

import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.state.ArmorStandEntityRenderState;
import net.minecraft.client.render.entity.state.BlockDisplayEntityRenderState;
import net.minecraft.client.render.entity.state.DisplayEntityRenderState;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.ItemFrameEntityRenderState;
import net.minecraft.client.render.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.render.entity.state.TextDisplayEntityRenderState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.EulerAngle;

/**
 * Immutable geometry cache for server-side decoration entities.
 *
 * <p>The cache deliberately starts with armor stands: they are common custom-model carriers on
 * multiplayer servers and their pose/equipment has a complete, cheap invalidation snapshot. Camera
 * position and light are never part of the baked data. Unsupported queue commands (held items,
 * labels, fire, leashes, special models) permanently fall back to vanilla until the snapshot changes.
 * Render-thread only.
 */
public final class StaticEntityGeometryCache {
	private StaticEntityGeometryCache() {
	}

	private static final int MAX_ARMOR_STANDS = 2048;
	private static final int MAX_ITEM_FRAMES = 1024;
	private static final int MAX_DISPLAYS = 1024;
	private static final int MAX_VERTICES_PER_ENTITY = 100_000;
	private static final long IDLE_EVICTION_MS = 30_000L;
	private static final long SWEEP_INTERVAL_MS = 2_000L;
	/** A pose that keeps changing is an animation, not a cache miss. Keep it live until stable. */
	private static final long ARMOR_STAND_STABLE_MS = 750L;
	private static long nextSweepMs;
	private static final EquipmentSlot[] EQUIPMENT_SLOTS = EquipmentSlot.values();
	private static final Map<EntityRenderState, Entity> ASSOCIATIONS = new IdentityHashMap<>();
	private static final Map<Integer, Entry> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, Entry> eldest) {
			return size() > MAX_ARMOR_STANDS;
		}
	};
	private static final Map<Integer, LiveArmorStand> LIVE_ARMOR_STANDS = new LinkedHashMap<>(128, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, LiveArmorStand> eldest) {
			return size() > MAX_ARMOR_STANDS;
		}
	};
	private static final Map<Integer, ItemFrameEntry> ITEM_FRAME_CACHE = new LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, ItemFrameEntry> eldest) {
			return size() > MAX_ITEM_FRAMES;
		}
	};
	private static final Map<Integer, DisplayEntry> DISPLAY_CACHE = new LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, DisplayEntry> eldest) {
			return size() > MAX_DISPLAYS;
		}
	};

	/** Associates the freshly-created render state with its source entity until the render call. */
	public static void associate(EntityRenderState state, Entity entity) {
		if (!MoneyakShadersConfig.get().experimentalRenderer) {
			return;
		}
		if ((state instanceof ArmorStandEntityRenderState && entity instanceof ArmorStandEntity)
				|| (state instanceof ItemFrameEntityRenderState && entity instanceof ItemFrameEntity)
				|| (state instanceof BlockDisplayEntityRenderState && entity instanceof DisplayEntity.BlockDisplayEntity)
				|| (state instanceof ItemDisplayEntityRenderState && entity instanceof DisplayEntity.ItemDisplayEntity)
				|| (state instanceof TextDisplayEntityRenderState && entity instanceof DisplayEntity.TextDisplayEntity)) {
			if (ASSOCIATIONS.size() > 16_384) {
				// Defensive bound for states extracted by another mod and then dropped before manager.render.
				ASSOCIATIONS.clear();
			}
			ASSOCIATIONS.put(state, entity);
		}
	}

	/**
	 * Attempts cached replay or a first capture. Returns true when the manager's vanilla render call
	 * has been fully replaced; false tells the wrapper to invoke vanilla normally.
	 */
	public static boolean renderArmorStand(ArmorStandEntityRenderState state,
			double offsetX, double offsetY, double offsetZ, MatrixStack matrices,
			OrderedRenderCommandQueue realQueue, RenderCall original) {
		Entity associated = ASSOCIATIONS.remove(state);
		if (!(associated instanceof ArmorStandEntity armorStand)
				|| !MoneyakShadersConfig.get().experimentalRenderer
				|| !eligible(state)) {
			return false;
		}
		long nowMs = System.currentTimeMillis();
		sweepIdle(nowMs);
		// Player-head skins resolve asynchronously and may change their texture without changing the
		// ItemStack.  Replaying a captured early/fallback skin would freeze the wrong owner forever.
		if (hasLiveTextureEquipment(armorStand)) {
			CACHE.remove(armorStand.getId());
			LIVE_ARMOR_STANDS.remove(armorStand.getId());
			return false;
		}

		LiveArmorStand live = LIVE_ARMOR_STANDS.get(armorStand.getId());
		if (live != null) {
			if (!live.snapshot.matches(armorStand, state)) {
				live.snapshot = Snapshot.capture(armorStand, state);
				live.lastChangeMs = nowMs;
			}
			if (nowMs - live.lastChangeMs < ARMOR_STAND_STABLE_MS) {
				return false; // animated pose: vanilla draw also feeds the exact dynamic shadow capture
			}
			LIVE_ARMOR_STANDS.remove(armorStand.getId());
		}

		Entry cached = CACHE.get(armorStand.getId());
		if (cached != null && cached.uuid.equals(armorStand.getUuid()) && cached.snapshot.matches(armorStand, state)) {
			cached.lastSeenMs = nowMs;
			if (cached.geometry == null) {
				stat(DebugStats.entityBakeFallback);
				return false;
			}
			stat(DebugStats.entityBakeHit);
			replay(cached.geometry, state, offsetX, offsetY, offsetZ, matrices, realQueue, true);
			return true;
		}
		if (cached != null) {
			// Do not recapture a moving ArmorStand every frame.  The first changed frame renders live;
			// continued changes extend the live window and keep model shadows temporally coherent.
			CACHE.remove(armorStand.getId());
			LIVE_ARMOR_STANDS.put(armorStand.getId(), new LiveArmorStand(
					Snapshot.capture(armorStand, state), nowMs));
			return false;
		}

		BakedBlockEntities.CaptureSession capture = BakedBlockEntities.beginSharedCapture(
				matrices, offsetX, offsetY, offsetZ);
		stat(DebugStats.entityBakeCapture);
		original.render(capture.queue());
		BakedBlockEntities.CapturedGeometry geometry = BakedBlockEntities.finishSharedCapture(capture);
		geometry = bounded(geometry);
		Snapshot snapshot = Snapshot.capture(armorStand, state);
		CACHE.put(armorStand.getId(), new Entry(armorStand.getUuid(), snapshot, geometry, nowMs));
		if (geometry == null) {
			stat(DebugStats.entityBakeFallback);
			return false;
		}
		replay(geometry, state, offsetX, offsetY, offsetZ, matrices, realQueue, false);
		return true;
	}

	/** Bakes fixed, fully-interpolated block/item/text displays; camera-facing displays stay live. */
	public static boolean renderDisplay(DisplayEntityRenderState state,
			double offsetX, double offsetY, double offsetZ, MatrixStack matrices,
			OrderedRenderCommandQueue realQueue, RenderCall original) {
		Entity associated = ASSOCIATIONS.remove(state);
		if (!(associated instanceof DisplayEntity display)
				|| !MoneyakShadersConfig.get().experimentalRenderer
				|| !eligible(state)) {
			return false;
		}
		long nowMs = System.currentTimeMillis();
		sweepIdle(nowMs);
		if (display instanceof DisplayEntity.ItemDisplayEntity itemDisplay
				&& hasLiveTextureItem(itemDisplay.getData() == null ? ItemStack.EMPTY : itemDisplay.getData().itemStack())) {
			DISPLAY_CACHE.remove(display.getId());
			return false;
		}

		DisplayEntry cached = DISPLAY_CACHE.get(display.getId());
		if (cached != null && cached.uuid.equals(display.getUuid()) && cached.snapshot.matches(display, state)) {
			cached.lastSeenMs = nowMs;
			if (cached.geometry == null) {
				stat(DebugStats.entityBakeFallback);
				return false;
			}
			stat(DebugStats.entityBakeHit);
			replay(cached.geometry, state, offsetX, offsetY, offsetZ, matrices, realQueue,
					!(state instanceof TextDisplayEntityRenderState));
			return true;
		}

		DisplaySnapshot snapshot = DisplaySnapshot.capture(display, state);
		if (snapshot == null) {
			return false;
		}
		BakedBlockEntities.CaptureSession capture = BakedBlockEntities.beginSharedCapture(
				matrices, offsetX, offsetY, offsetZ);
		stat(DebugStats.entityBakeCapture);
		original.render(capture.queue());
		BakedBlockEntities.CapturedGeometry geometry = BakedBlockEntities.finishSharedCapture(
				capture, state instanceof TextDisplayEntityRenderState);
		geometry = bounded(geometry);
		DISPLAY_CACHE.put(display.getId(), new DisplayEntry(display.getUuid(), snapshot, geometry, nowMs));
		if (geometry == null) {
			stat(DebugStats.entityBakeFallback);
			return false;
		}
		replay(geometry, state, offsetX, offsetY, offsetZ, matrices, realQueue,
				!(state instanceof TextDisplayEntityRenderState));
		return true;
	}

	/** Map pixels stay live in their texture; decoration changes invalidate and recapture only geometry. */
	public static boolean renderItemFrame(ItemFrameEntityRenderState state,
			double offsetX, double offsetY, double offsetZ, MatrixStack matrices,
			OrderedRenderCommandQueue realQueue, RenderCall original) {
		Entity associated = ASSOCIATIONS.remove(state);
		if (!(associated instanceof ItemFrameEntity itemFrame)
				|| !MoneyakShadersConfig.get().experimentalRenderer
				|| !eligible(state)) {
			return false;
		}
		long nowMs = System.currentTimeMillis();
		sweepIdle(nowMs);
		if (hasLiveTextureItem(itemFrame.getHeldItemStack())) {
			ITEM_FRAME_CACHE.remove(itemFrame.getId());
			return false;
		}

		double renderX = offsetX + state.facing.getOffsetX() * 0.3F;
		double renderY = offsetY - 0.25;
		double renderZ = offsetZ + state.facing.getOffsetZ() * 0.3F;
		ItemFrameEntry cached = ITEM_FRAME_CACHE.get(itemFrame.getId());
		if (cached != null && cached.uuid.equals(itemFrame.getUuid()) && cached.snapshot.matches(itemFrame, state)) {
			cached.lastSeenMs = nowMs;
			if (cached.geometry == null) {
				stat(DebugStats.entityBakeFallback);
				return false;
			}
			stat(DebugStats.entityBakeHit);
			replay(cached.geometry, state, renderX, renderY, renderZ, matrices, realQueue, true);
			return true;
		}

		BakedBlockEntities.CaptureSession capture = BakedBlockEntities.beginSharedCapture(
				matrices, renderX, renderY, renderZ);
		stat(DebugStats.entityBakeCapture);
		original.render(capture.queue());
		BakedBlockEntities.CapturedGeometry geometry = BakedBlockEntities.finishSharedCapture(capture);
		geometry = bounded(geometry);
		ITEM_FRAME_CACHE.put(itemFrame.getId(), new ItemFrameEntry(
				itemFrame.getUuid(), new ItemFrameSnapshot(itemFrame, state), geometry, nowMs));
		if (geometry == null) {
			stat(DebugStats.entityBakeFallback);
			return false;
		}
		replay(geometry, state, renderX, renderY, renderZ, matrices, realQueue, true);
		return true;
	}

	private static boolean eligible(ArmorStandEntityRenderState state) {
		return state.positionOffset == null
				&& !state.onFire
				&& state.outlineColor == 0
				&& state.displayName == null
				&& state.leashDatas == null
				&& state.timeSinceLastHit >= 5.0F;
	}

	private static BakedBlockEntities.CapturedGeometry bounded(BakedBlockEntities.CapturedGeometry geometry) {
		return geometry != null && geometry.vertexCount() <= MAX_VERTICES_PER_ENTITY ? geometry : null;
	}

	private static boolean hasLiveTextureEquipment(ArmorStandEntity armorStand) {
		for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
			if (hasLiveTextureItem(armorStand.getEquippedStack(slot))) {
				return true;
			}
		}
		return false;
	}

	/** Textures resolved from a player profile are mutable client state, unlike ordinary item models. */
	private static boolean hasLiveTextureItem(ItemStack stack) {
		return !stack.isEmpty() && stack.isOf(Items.PLAYER_HEAD);
	}

	private static void stat(java.util.concurrent.atomic.AtomicLong counter) {
		if (MoneyakShadersConfig.get().debugStats) {
			counter.incrementAndGet();
		}
	}

	private static void sweepIdle(long nowMs) {
		if (nowMs < nextSweepMs) {
			return;
		}
		nextSweepMs = nowMs + SWEEP_INTERVAL_MS;
		sweepOldest(CACHE, nowMs);
		sweepOldest(ITEM_FRAME_CACHE, nowMs);
		sweepOldest(DISPLAY_CACHE, nowMs);
	}

	/** Maps are access-ordered LRUs: once the eldest entry is recent, all following entries are recent too. */
	private static <T extends TimedEntry> void sweepOldest(Map<Integer, T> cache, long nowMs) {
		var iterator = cache.values().iterator();
		while (iterator.hasNext()) {
			TimedEntry entry = iterator.next();
			if (nowMs - entry.lastSeenMs() <= IDLE_EVICTION_MS) {
				break;
			}
			iterator.remove();
		}
	}

	private static boolean eligible(ItemFrameEntityRenderState state) {
		return !state.onFire
				&& state.outlineColor == 0
				&& state.displayName == null
				&& state.leashDatas == null;
	}

	private static boolean eligible(DisplayEntityRenderState state) {
		return state.displayRenderState != null
				&& state.displayRenderState.billboardConstraints() == DisplayEntity.BillboardMode.FIXED
				&& state.lerpProgress >= 1.0F
				&& state.positionOffset == null
				&& !state.onFire
				&& state.outlineColor == 0
				&& state.displayName == null
				&& state.leashDatas == null
				&& (state instanceof BlockDisplayEntityRenderState
						|| state instanceof ItemDisplayEntityRenderState
						|| state instanceof TextDisplayEntityRenderState);
	}

	private static void replay(BakedBlockEntities.CapturedGeometry geometry,
			ArmorStandEntityRenderState state, double offsetX, double offsetY, double offsetZ,
			MatrixStack matrices, OrderedRenderCommandQueue queue, boolean captureShadow) {
		captureShadow &= com.moneyakshaders.client.EntityShadowCapture.shouldCaptureStaticGeometry();
		recordReplay(geometry, DebugStats.staticArmorReplays, captureShadow);
		matrices.push();
		matrices.translate(offsetX, offsetY, offsetZ);
		BakedBlockEntities.replayShared(geometry, matrices, queue, state.light);
		if (captureShadow) BakedBlockEntities.captureSharedShadow(geometry, matrices);
		if (!state.shadowPieces.isEmpty()) {
			queue.submitShadowPieces(matrices, state.shadowRadius, state.shadowPieces);
		}
		matrices.pop();
	}

	private static void replay(BakedBlockEntities.CapturedGeometry geometry,
			DisplayEntityRenderState state, double renderX, double renderY, double renderZ,
			MatrixStack matrices, OrderedRenderCommandQueue queue, boolean captureShadow) {
		captureShadow &= com.moneyakshaders.client.EntityShadowCapture.shouldCaptureStaticGeometry();
		recordReplay(geometry, DebugStats.staticDisplayReplays, captureShadow);
		matrices.push();
		matrices.translate(renderX, renderY, renderZ);
		BakedBlockEntities.replayShared(geometry, matrices, queue, state.light);
		if (captureShadow) BakedBlockEntities.captureSharedShadow(geometry, matrices);
		if (!state.shadowPieces.isEmpty()) {
			queue.submitShadowPieces(matrices, state.shadowRadius, state.shadowPieces);
		}
		matrices.pop();
	}

	private static void replay(BakedBlockEntities.CapturedGeometry geometry,
			ItemFrameEntityRenderState state, double renderX, double renderY, double renderZ,
			MatrixStack matrices, OrderedRenderCommandQueue queue, boolean captureShadow) {
		captureShadow &= com.moneyakshaders.client.EntityShadowCapture.shouldCaptureStaticGeometry();
		recordReplay(geometry, DebugStats.staticItemFrameReplays, captureShadow);
		matrices.push();
		matrices.translate(renderX, renderY, renderZ);
		BakedBlockEntities.replayShared(geometry, matrices, queue, state.light);
		if (captureShadow) BakedBlockEntities.captureSharedShadow(geometry, matrices);
		if (!state.shadowPieces.isEmpty()) {
			queue.submitShadowPieces(matrices, state.shadowRadius, state.shadowPieces);
		}
		matrices.pop();
	}

	private static void recordReplay(BakedBlockEntities.CapturedGeometry geometry,
			java.util.concurrent.atomic.AtomicLong typeCounter, boolean captureShadow) {
		if (!MoneyakShadersConfig.get().debugStats) return;
		int vertices = geometry.vertexCount();
		typeCounter.incrementAndGet();
		DebugStats.staticReplayVertices.addAndGet(vertices);
		if (captureShadow) DebugStats.staticShadowVertices.addAndGet(vertices);
	}

	public static void clearAll() {
		ASSOCIATIONS.clear();
		CACHE.clear();
		LIVE_ARMOR_STANDS.clear();
		ITEM_FRAME_CACHE.clear();
		DISPLAY_CACHE.clear();
	}

	public static int size() {
		return CACHE.size() + ITEM_FRAME_CACHE.size() + DISPLAY_CACHE.size();
	}

	@FunctionalInterface
	public interface RenderCall {
		void render(OrderedRenderCommandQueue queue);
	}

	private interface TimedEntry {
		long lastSeenMs();
	}

	private static final class Entry implements TimedEntry {
		final UUID uuid; final Snapshot snapshot; final BakedBlockEntities.CapturedGeometry geometry; long lastSeenMs;
		Entry(UUID uuid, Snapshot snapshot, BakedBlockEntities.CapturedGeometry geometry, long lastSeenMs) {
			this.uuid = uuid; this.snapshot = snapshot; this.geometry = geometry; this.lastSeenMs = lastSeenMs;
		}
		@Override public long lastSeenMs() { return lastSeenMs; }
	}

	private static final class LiveArmorStand {
		Snapshot snapshot;
		long lastChangeMs;
		LiveArmorStand(Snapshot snapshot, long lastChangeMs) {
			this.snapshot = snapshot;
			this.lastChangeMs = lastChangeMs;
		}
	}

	private static final class ItemFrameEntry implements TimedEntry {
		final UUID uuid; final ItemFrameSnapshot snapshot; final BakedBlockEntities.CapturedGeometry geometry; long lastSeenMs;
		ItemFrameEntry(UUID uuid, ItemFrameSnapshot snapshot, BakedBlockEntities.CapturedGeometry geometry, long lastSeenMs) {
			this.uuid = uuid; this.snapshot = snapshot; this.geometry = geometry; this.lastSeenMs = lastSeenMs;
		}
		@Override public long lastSeenMs() { return lastSeenMs; }
	}

	private static final class DisplayEntry implements TimedEntry {
		final UUID uuid; final DisplaySnapshot snapshot; final BakedBlockEntities.CapturedGeometry geometry; long lastSeenMs;
		DisplayEntry(UUID uuid, DisplaySnapshot snapshot, BakedBlockEntities.CapturedGeometry geometry, long lastSeenMs) {
			this.uuid = uuid; this.snapshot = snapshot; this.geometry = geometry; this.lastSeenMs = lastSeenMs;
		}
		@Override public long lastSeenMs() { return lastSeenMs; }
	}

	private interface DisplaySnapshot {
		boolean matches(DisplayEntity entity, DisplayEntityRenderState state);

		static DisplaySnapshot capture(DisplayEntity entity, DisplayEntityRenderState state) {
			if (entity instanceof DisplayEntity.BlockDisplayEntity
					&& state instanceof BlockDisplayEntityRenderState blockState && blockState.data != null) {
				return new BlockDisplaySnapshot(state.displayRenderState, state.yaw, state.pitch, blockState.data.blockState());
			}
			if (entity instanceof DisplayEntity.ItemDisplayEntity itemEntity
					&& state instanceof ItemDisplayEntityRenderState) {
				DisplayEntity.ItemDisplayEntity.Data data = itemEntity.getData();
				if (data != null) {
					return new ItemDisplaySnapshot(state.displayRenderState, state.yaw, state.pitch,
							data.itemStack().copy(), data.itemTransform());
				}
			}
			if (entity instanceof DisplayEntity.TextDisplayEntity
					&& state instanceof TextDisplayEntityRenderState textState && textState.data != null) {
				return new TextDisplaySnapshot(state.displayRenderState, state.yaw, state.pitch, textState.data,
						MinecraftClient.getInstance().options.getTextBackgroundOpacity(0.25F));
			}
			return null;
		}
	}

	private record BlockDisplaySnapshot(DisplayEntity.RenderState renderState, float yaw, float pitch,
			net.minecraft.block.BlockState blockState) implements DisplaySnapshot {
		@Override
		public boolean matches(DisplayEntity entity, DisplayEntityRenderState state) {
			return entity instanceof DisplayEntity.BlockDisplayEntity
					&& state instanceof BlockDisplayEntityRenderState block
					&& block.data != null
					&& this.renderState == state.displayRenderState
					&& Float.floatToIntBits(this.yaw) == Float.floatToIntBits(state.yaw)
					&& Float.floatToIntBits(this.pitch) == Float.floatToIntBits(state.pitch)
					&& this.blockState.equals(block.data.blockState());
		}
	}

	private record ItemDisplaySnapshot(DisplayEntity.RenderState renderState, float yaw, float pitch,
			ItemStack item, net.minecraft.item.ItemDisplayContext transform) implements DisplaySnapshot {
		@Override
		public boolean matches(DisplayEntity entity, DisplayEntityRenderState state) {
			if (!(entity instanceof DisplayEntity.ItemDisplayEntity itemEntity)
					|| !(state instanceof ItemDisplayEntityRenderState)
					|| this.renderState != state.displayRenderState
					|| Float.floatToIntBits(this.yaw) != Float.floatToIntBits(state.yaw)
					|| Float.floatToIntBits(this.pitch) != Float.floatToIntBits(state.pitch)) {
				return false;
			}
			DisplayEntity.ItemDisplayEntity.Data data = itemEntity.getData();
			return data != null && this.transform == data.itemTransform() && ItemStack.areEqual(this.item, data.itemStack());
		}
	}

	private record TextDisplaySnapshot(DisplayEntity.RenderState renderState, float yaw, float pitch,
			DisplayEntity.TextDisplayEntity.Data data, float defaultBackgroundOpacity) implements DisplaySnapshot {
		@Override
		public boolean matches(DisplayEntity entity, DisplayEntityRenderState state) {
			return entity instanceof DisplayEntity.TextDisplayEntity
					&& state instanceof TextDisplayEntityRenderState text
					&& this.renderState == state.displayRenderState
					&& this.data == text.data
					&& Float.floatToIntBits(this.yaw) == Float.floatToIntBits(state.yaw)
					&& Float.floatToIntBits(this.pitch) == Float.floatToIntBits(state.pitch)
					&& Float.floatToIntBits(this.defaultBackgroundOpacity) == Float.floatToIntBits(
							MinecraftClient.getInstance().options.getTextBackgroundOpacity(0.25F));
		}
	}

	private static final class ItemFrameSnapshot {
		final int rotation;
		final boolean glow;
		final boolean invisible;
		final net.minecraft.util.math.Direction facing;
		final ItemStack item;
		final Object mapId;
		final net.minecraft.util.Identifier mapTexture;
		final List<MapDecorationSnapshot> decorations;

		ItemFrameSnapshot(ItemFrameEntity entity, ItemFrameEntityRenderState state) {
			this.rotation = state.rotation;
			this.glow = state.glow;
			this.invisible = state.invisible;
			this.facing = state.facing;
			this.item = entity.getHeldItemStack().copy();
			this.mapId = state.mapId;
			this.mapTexture = state.mapRenderState.texture;
			this.decorations = new ArrayList<>(state.mapRenderState.decorations.size());
			for (net.minecraft.client.render.MapRenderState.Decoration decoration : state.mapRenderState.decorations) {
				this.decorations.add(new MapDecorationSnapshot(decoration.sprite, decoration.x, decoration.z,
						decoration.rotation, decoration.alwaysRendered, decoration.name));
			}
		}

		boolean matches(ItemFrameEntity entity, ItemFrameEntityRenderState state) {
			return this.rotation == state.rotation
					&& this.glow == state.glow
					&& this.invisible == state.invisible
					&& this.facing == state.facing
					&& java.util.Objects.equals(this.mapId, state.mapId)
					&& java.util.Objects.equals(this.mapTexture, state.mapRenderState.texture)
					&& decorationsMatch(state)
					&& ItemStack.areEqual(this.item, entity.getHeldItemStack());
		}

		private boolean decorationsMatch(ItemFrameEntityRenderState state) {
			if (this.decorations.size() != state.mapRenderState.decorations.size()) {
				return false;
			}
			for (int i = 0; i < this.decorations.size(); i++) {
				if (!this.decorations.get(i).matches(state.mapRenderState.decorations.get(i))) {
					return false;
				}
			}
			return true;
		}
	}

	private record MapDecorationSnapshot(net.minecraft.client.texture.Sprite sprite, byte x, byte z,
			byte rotation, boolean alwaysRendered, net.minecraft.text.Text name) {
		boolean matches(net.minecraft.client.render.MapRenderState.Decoration decoration) {
			return this.sprite == decoration.sprite && this.x == decoration.x && this.z == decoration.z
					&& this.rotation == decoration.rotation && this.alwaysRendered == decoration.alwaysRendered
					&& java.util.Objects.equals(this.name, decoration.name);
		}
	}

	private static final class Snapshot {
		final boolean marker;
		final boolean small;
		final boolean showArms;
		final boolean showBasePlate;
		final boolean invisible;
		final boolean hurt;
		final boolean shaking;
		final boolean baby;
		final boolean flipUpsideDown;
		final boolean invisibleToPlayer;
		final float yaw;
		final float bodyYaw;
		final float relativeHeadYaw;
		final float pitch;
		final float baseScale;
		final float ageScale;
		final EulerAngle head;
		final EulerAngle body;
		final EulerAngle leftArm;
		final EulerAngle rightArm;
		final EulerAngle leftLeg;
		final EulerAngle rightLeg;
		final ItemStack[] equipment;

		private Snapshot(ArmorStandEntity entity, ArmorStandEntityRenderState state) {
			this.marker = state.marker;
			this.small = state.small;
			this.showArms = state.showArms;
			this.showBasePlate = state.showBasePlate;
			this.invisible = state.invisible;
			this.hurt = state.hurt;
			this.shaking = state.shaking;
			this.baby = state.baby;
			this.flipUpsideDown = state.flipUpsideDown;
			this.invisibleToPlayer = state.invisibleToPlayer;
			this.yaw = state.yaw;
			this.bodyYaw = state.bodyYaw;
			this.relativeHeadYaw = state.relativeHeadYaw;
			this.pitch = state.pitch;
			this.baseScale = state.baseScale;
			this.ageScale = state.ageScale;
			this.head = state.headRotation;
			this.body = state.bodyRotation;
			this.leftArm = state.leftArmRotation;
			this.rightArm = state.rightArmRotation;
			this.leftLeg = state.leftLegRotation;
			this.rightLeg = state.rightLegRotation;
			this.equipment = new ItemStack[EQUIPMENT_SLOTS.length];
			for (int i = 0; i < EQUIPMENT_SLOTS.length; i++) {
				this.equipment[i] = entity.getEquippedStack(EQUIPMENT_SLOTS[i]).copy();
			}
		}

		static Snapshot capture(ArmorStandEntity entity, ArmorStandEntityRenderState state) {
			return new Snapshot(entity, state);
		}

		boolean matches(ArmorStandEntity entity, ArmorStandEntityRenderState state) {
			if (this.marker != state.marker || this.small != state.small || this.showArms != state.showArms
					|| this.showBasePlate != state.showBasePlate || this.invisible != state.invisible
					|| this.hurt != state.hurt || this.shaking != state.shaking || this.baby != state.baby
					|| this.flipUpsideDown != state.flipUpsideDown || this.invisibleToPlayer != state.invisibleToPlayer
					|| Float.floatToIntBits(this.yaw) != Float.floatToIntBits(state.yaw)
					|| Float.floatToIntBits(this.bodyYaw) != Float.floatToIntBits(state.bodyYaw)
					|| Float.floatToIntBits(this.relativeHeadYaw) != Float.floatToIntBits(state.relativeHeadYaw)
					|| Float.floatToIntBits(this.pitch) != Float.floatToIntBits(state.pitch)
					|| Float.floatToIntBits(this.baseScale) != Float.floatToIntBits(state.baseScale)
					|| Float.floatToIntBits(this.ageScale) != Float.floatToIntBits(state.ageScale)
					|| !this.head.equals(state.headRotation) || !this.body.equals(state.bodyRotation)
					|| !this.leftArm.equals(state.leftArmRotation) || !this.rightArm.equals(state.rightArmRotation)
					|| !this.leftLeg.equals(state.leftLegRotation) || !this.rightLeg.equals(state.rightLegRotation)
					|| this.equipment.length != EQUIPMENT_SLOTS.length) {
				return false;
			}
			for (int i = 0; i < EQUIPMENT_SLOTS.length; i++) {
				if (!ItemStack.areEqual(this.equipment[i], entity.getEquippedStack(EQUIPMENT_SLOTS[i]))) {
					return false;
				}
			}
			return true;
		}
	}
}

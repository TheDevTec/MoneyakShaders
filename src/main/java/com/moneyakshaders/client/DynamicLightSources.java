package com.moneyakshaders.client;

import java.util.List;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.render.PointLightRegistry;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.GlowSquidEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;

/**
 * Client-side dynamic light registry (LambDynLights-style). Each client tick we collect
 * nearby light-emitting things — the player's (and other players') held light block, dropped
 * light items, burning entities (fire / flaming arrows), glow squid — into a small flat array
 * of (worldPos, level, colour). {@link #boost} then lifts the block-light component of any
 * entity's packed lightmap coordinate so vanilla draws it lit instead of pitch-black in the
 * dark. Entities are vanilla-rendered, so this is the only way to light them from our side.
 *
 * <p>Render/client-thread only; the flat array is swapped atomically (volatile) each update.
 */
public final class DynamicLightSources {
	public static final int MAX = 32;
	// Source discovery must cover more than the near camera bubble. Otherwise a visible burning mob
	// or dropped torch simply vanishes from terrain/entity lighting at ~48 blocks, even though its own
	// physical light radius is unchanged. The spatial query grid keeps the larger registry cheap.
	private static final float RADIUS = 96f;
	private static final float[] EMPTY = new float[0];
	// Immutable shared RGB constants: terrain snapshotting can classify thousands of luminous blocks
	// during a chunk burst, so returning a new 3-float array here would turn light tinting into GC work.
	private static final float[] WARM = {1.0f, 0.78f, 0.50f};
	private static final float[] LAVA = {1.0f, 0.55f, 0.25f};
	private static final float[] GLOW_INK = {0.40f, 0.80f, 1.0f};
	private static final float[] EQUIPMENT_LIGHT_SCRATCH = new float[4];
	private static final float DROPPED_CLUSTER_RADIUS_SQ = 1.0f;

	// [i*7 + {0,1,2}] world x,y,z ; [+3] level 0..15 ; [+4,5,6] colour r,g,b
	private static volatile float[] data = EMPTY;
	private static volatile int count;
	// Tick discovery stays immutable for entity-light queries, while terrain rendering replaces only
	// source positions with Minecraft's interpolated render position. Keeping the Entity references in
	// the same published order removes the visible 20 Hz light lag without rescanning the world per frame.
	private static volatile Entity[] sourceEntities = new Entity[0];
	private static volatile float[] sourceYOffsets = EMPTY;
	// True for a light embedded in its carrier (held item, burning/glowing entity). Dropped items are
	// false: their cubemap must retain a nearby player's feet instead of applying the broad carrier cut.
	private static volatile boolean[] embeddedSources = new boolean[0];
	private static final float[] renderData = new float[MAX * 7];
	private static final int[] renderSourceIndices = new int[MAX];
	private static final boolean[] renderEmbeddedSources = new boolean[MAX];
	private static float[] lastRenderBase;
	private static int lastRenderCount = -1, renderCount;
	private static float lastRenderTickProgress = Float.NaN;
	private static double lastRenderCamX = Double.NaN, lastRenderCamY = Double.NaN, lastRenderCamZ = Double.NaN;
	private static long renderRevision, lastRenderFingerprint = Long.MIN_VALUE;
	/** 16-block source-cell index. A source reaches at most 15 blocks, so a query only needs 3x3x3 cells. */
	private static volatile SourceGrid sourceGrid = SourceGrid.EMPTY;
	/** True when entry 0 is the LOCAL player's held light — the terrain shader's uHeldLight already
	 *  fills for that one, so the terrain point-light loop must skip it (it would double-light). */
	private static volatile boolean firstIsLocalHeld;
	private static final int OCCLUSION_CACHE_SIZE = 1024;
	private static final long[] occlusionKeys = new long[OCCLUSION_CACHE_SIZE];
	private static final int[] occlusionStamps = new int[OCCLUSION_CACHE_SIZE];
	private static final byte[] occlusionValues = new byte[OCCLUSION_CACHE_SIZE];
	private static int occlusionGeneration = 1;

	private static final class SourceGrid {
		static final SourceGrid EMPTY = new SourceGrid(0);
		private static final int TABLE_SIZE = 64; // MAX=32, keeps probing short without a map allocation per tick
		private final long[] keys = new long[TABLE_SIZE];
		private final boolean[] occupied = new boolean[TABLE_SIZE];
		private final byte[] heads = new byte[TABLE_SIZE];
		private final byte[] next = new byte[MAX];
		private final int sourceCount;

		private SourceGrid(int sourceCount) {
			this.sourceCount = sourceCount;
			java.util.Arrays.fill(heads, (byte) -1);
			java.util.Arrays.fill(next, (byte) -1);
		}

		static SourceGrid build(float[] sources, int count) {
			if (count == 0) return EMPTY;
			SourceGrid grid = new SourceGrid(count);
			for (int i = 0; i < count; i++) {
				int o = i * 7;
				long key = ChunkSectionPos.asLong(
						Math.floorDiv((int) Math.floor(sources[o]), 16),
						Math.floorDiv((int) Math.floor(sources[o + 1]), 16),
						Math.floorDiv((int) Math.floor(sources[o + 2]), 16));
				int slot = grid.findSlot(key);
				if (!grid.occupied[slot]) {
					grid.occupied[slot] = true;
					grid.keys[slot] = key;
				}
				grid.next[i] = grid.heads[slot];
				grid.heads[slot] = (byte) i;
			}
			return grid;
		}

		int first(int cellX, int cellY, int cellZ) {
			if (sourceCount == 0) return -1;
			long key = ChunkSectionPos.asLong(cellX, cellY, cellZ);
			int slot = findSlot(key);
			return occupied[slot] ? heads[slot] : -1;
		}

		int next(int index) { return next[index]; }

		private int findSlot(long key) {
			long mixed = key ^ (key >>> 33);
			mixed *= 0xff51afd7ed558ccdl;
			mixed ^= mixed >>> 33;
			int slot = (int) mixed & (TABLE_SIZE - 1);
			while (occupied[slot] && keys[slot] != key) slot = (slot + 1) & (TABLE_SIZE - 1);
			return slot;
		}
	}

	// NOTE: placed light blocks (torches, lanterns, …) are no longer scanned here per tick — their
	// colour is BAKED into the section meshes by BakedLightTint (unlimited range/count, zero per-frame
	// cost). This class now only tracks DYNAMIC sources: held/dropped light items and burning entities.

	private DynamicLightSources() {
	}

	public static int count() {
		return count;
	}

	public static float[] data() {
		return data;
	}

	/**
	 * Dynamic-light snapshot with positions interpolated exactly like Minecraft's entity render pass.
	 * Discovery, colours and the spatial boost grid remain tick-based; this is a tiny O(source count)
	 * position refresh for the terrain/cubemap shader only and allocates no arrays per frame.
	 */
	public static float[] renderData(MinecraftClient client) {
		refreshLocalHeld(client);
		float[] base = data;
		int n = count;
		if (client == null || n <= 0) {
			renderCount = 0;
			return EMPTY;
		}
		float tickProgress = client.getRenderTickCounter().getTickProgress(false);
		Vec3d cam = client.gameRenderer.getCamera().getCameraPos();
		if (base == lastRenderBase && n == lastRenderCount
				&& Float.floatToIntBits(tickProgress) == Float.floatToIntBits(lastRenderTickProgress)
				&& Double.doubleToLongBits(cam.x) == Double.doubleToLongBits(lastRenderCamX)
				&& Double.doubleToLongBits(cam.y) == Double.doubleToLongBits(lastRenderCamY)
				&& Double.doubleToLongBits(cam.z) == Double.doubleToLongBits(lastRenderCamZ)) return renderData;

		System.arraycopy(base, 0, renderData, 0, n * 7);
		Entity[] entities = sourceEntities;
		float[] offsets = sourceYOffsets;
		boolean[] embedded = embeddedSources;
		for (int i = 0; i < n; i++) {
			renderSourceIndices[i] = i;
			renderEmbeddedSources[i] = i < embedded.length && embedded[i];
			if (i >= entities.length || i >= offsets.length) continue;
			Entity entity = entities[i];
			if (entity == null || entity.isRemoved()) continue;
			Vec3d pos = entity.getLerpedPos(tickProgress);
			int o = i * 7;
			renderData[o] = (float)pos.x;
			renderData[o + 1] = (float)pos.y + offsets[i];
			renderData[o + 2] = (float)pos.z;
		}

		sortByInfluence(renderData, renderSourceIndices, renderEmbeddedSources, firstIsLocalHeld ? 1 : 0, n, cam.x, cam.y, cam.z);
		long fingerprint = fingerprint(renderData, renderSourceIndices, n);
		if (fingerprint != lastRenderFingerprint) {
			lastRenderFingerprint = fingerprint;
			renderRevision++;
		}

		renderCount = n;
		lastRenderBase = base;
		lastRenderCount = n;
		lastRenderTickProgress = tickProgress;
		lastRenderCamX = cam.x;
		lastRenderCamY = cam.y;
		lastRenderCamZ = cam.z;
		return renderData;
	}

	public static long renderRevision() {
		return renderRevision;
	}

	public static int visibleRenderCount(MinecraftClient client) {
		renderData(client);
		return renderCount;
	}

	private static void sortByInfluence(float[] buf, int[] indices, boolean[] embedded, int start, int n, double cx, double cy, double cz) {
		for (int i = start; i < n - 1; i++) {
			int best = i;
			float bestScore = computeInfluence(buf, i, cx, cy, cz);
			for (int j = i + 1; j < n; j++) {
				float score = computeInfluence(buf, j, cx, cy, cz);
				if (score > bestScore || score == bestScore && indices[j] < indices[best]) {
					best = j;
					bestScore = score;
				}
			}
			if (best != i) swapSource(buf, indices, embedded, i, best);
		}
	}

	private static float computeInfluence(float[] buf, int index, double cx, double cy, double cz) {
		int o = index * 7;
		float dx = buf[o] - (float)cx, dy = buf[o + 1] - (float)cy, dz = buf[o + 2] - (float)cz;
		float level = Math.max(0f, buf[o + 3]);
		float distanceSq = dx * dx + dy * dy + dz * dz;
		return level * level / (distanceSq + 4f);
	}

	private static void swapSource(float[] buf, int[] indices, boolean[] embedded, int a, int b) {
		int ao = a * 7, bo = b * 7;
		for (int i = 0; i < 7; i++) {
			float v = buf[ao + i];
			buf[ao + i] = buf[bo + i];
			buf[bo + i] = v;
		}
		int index = indices[a];
		indices[a] = indices[b];
		indices[b] = index;
		boolean flag = embedded[a];
		embedded[a] = embedded[b];
		embedded[b] = flag;
	}

	private static long fingerprint(float[] buf, int[] indices, int n) {
		long hash = 0xcbf29ce484222325L;
		for (int i = 0; i < n; i++) {
			int o = i * 7;
			hash ^= indices[i];
			hash *= 0x100000001b3L;
			for (int j = 0; j < 7; j++) {
				hash ^= Float.floatToRawIntBits(buf[o + j]);
				hash *= 0x100000001b3L;
			}
		}
		return hash;
	}

	private static void invalidateRenderSnapshot() {
		lastRenderBase = null;
		lastRenderCount = -1;
		lastRenderTickProgress = Float.NaN;
		lastRenderCamX = lastRenderCamY = lastRenderCamZ = Double.NaN;
	}

	/**
	 * Fast render-frame path for the local equipment only. World/entity discovery remains at 20 Hz,
	 * but an inventory click must not wait for the next client tick before its held/helmet light exists.
	 * Arrays are rebuilt only when the effective luminance/colour changes, never once per frame.
	 */
	private static void refreshLocalHeld(MinecraftClient client) {
		if (client == null || client.player == null) return;
		equipmentLight(client.player, EQUIPMENT_LIGHT_SCRATCH);
		int luminance = MoneyakShadersConfig.get().dynamicLighting ? (int) EQUIPMENT_LIGHT_SCRATCH[3] : 0;
		boolean want = luminance > 0;
		float[] current = data;
		boolean had = firstIsLocalHeld;
		float r = EQUIPMENT_LIGHT_SCRATCH[0];
		float g = EQUIPMENT_LIGHT_SCRATCH[1];
		float b = EQUIPMENT_LIGHT_SCRATCH[2];
		if (had == want && (!want || (current.length >= 7
				&& Float.floatToIntBits(current[3]) == Float.floatToIntBits(luminance)
				&& Float.floatToIntBits(current[4]) == Float.floatToIntBits(r)
				&& Float.floatToIntBits(current[5]) == Float.floatToIntBits(g)
				&& Float.floatToIntBits(current[6]) == Float.floatToIntBits(b)))) {
			return;
		}

		int oldCount = count;
		int sourceStart = had ? 1 : 0;
		int destinationStart = want ? 1 : 0;
		int preserved = Math.min(Math.max(0, oldCount - sourceStart), MAX - destinationStart);
		float[] nextData = new float[MAX * 7];
		Entity[] nextEntities = new Entity[MAX];
		float[] nextOffsets = new float[MAX];
		boolean[] nextEmbedded = new boolean[MAX];
		if (preserved > 0) {
			System.arraycopy(current, sourceStart * 7, nextData, destinationStart * 7, preserved * 7);
			System.arraycopy(sourceEntities, sourceStart, nextEntities, destinationStart, preserved);
			System.arraycopy(sourceYOffsets, sourceStart, nextOffsets, destinationStart, preserved);
			System.arraycopy(embeddedSources, sourceStart, nextEmbedded, destinationStart, preserved);
		}
		if (want) {
			PlayerEntity player = client.player;
			put(nextData, 0, (float) player.getX(), (float) player.getY() + equipmentSourceHeight(player),
					(float) player.getZ(), luminance, r, g, b);
			nextEntities[0] = player;
			nextOffsets[0] = equipmentSourceHeight(player);
			nextEmbedded[0] = true;
		}
		int nextCount = destinationStart + preserved;
		sourceGrid = SourceGrid.build(nextData, nextCount);
		sourceEntities = nextEntities;
		sourceYOffsets = nextOffsets;
		embeddedSources = nextEmbedded;
		data = nextData;
		count = nextCount;
		firstIsLocalHeld = want;
		invalidateRenderSnapshot();
	}

	public static boolean firstIsLocalHeld() {
		return firstIsLocalHeld;
	}

	public static boolean[] embeddedSources() {
		return renderCount > 0 ? renderEmbeddedSources : embeddedSources;
	}

	/** Dropped emissive item models keep their authored/RP colour instead of tinting one another. */
	public static boolean isDroppedLight(Entity entity) {
		return entity instanceof ItemEntity item && itemLuminance(item.getStack()) > 0;
	}

	/** Supplies the already-merged local equipment light as {r,g,b,radius}. */
	public static void localHeldLight(MinecraftClient client, float[] out) {
		refreshLocalHeld(client);
		float[] current = data;
		if (!firstIsLocalHeld || current.length < 7) {
			out[0] = out[1] = out[2] = out[3] = 0f;
			return;
		}
		out[0] = current[4] * 0.7f;
		out[1] = current[5] * 0.7f;
		out[2] = current[6] * 0.7f;
		out[3] = current[3] + 2.0f;
	}

	/** Rebuild the source list from entities near the camera. Call once per client tick. */
	public static void update(MinecraftClient client) {
		if (++occlusionGeneration == 0) {
			java.util.Arrays.fill(occlusionStamps, 0);
			occlusionGeneration = 1;
		}
		if (client == null || client.world == null || client.player == null) {
			count = 0;
			data = EMPTY;
			firstIsLocalHeld = false;
			sourceEntities = new Entity[0];
			sourceYOffsets = EMPTY;
			embeddedSources = new boolean[0];
			sourceGrid = SourceGrid.EMPTY;
			renderCount = 0;
			invalidateRenderSnapshot();
			return;
		}
		Vec3d cam = client.gameRenderer.getCamera().getCameraPos();
		if (!MoneyakShadersConfig.get().dynamicLighting) {
			count = 0;
			data = EMPTY;
			firstIsLocalHeld = false;
			sourceEntities = new Entity[0];
			sourceYOffsets = EMPTY;
			embeddedSources = new boolean[0];
			sourceGrid = SourceGrid.EMPTY;
			renderCount = 0;
			invalidateRenderSnapshot();
			return;
		}
		float[] buf = new float[MAX * 7];
		Entity[] entities = new Entity[MAX];
		float[] yOffsets = new float[MAX];
		boolean[] embedded = new boolean[MAX];
		int n = addHeld(buf, 0, client.player);
		if (n > 0) {
			entities[0] = client.player;
			yOffsets[0] = equipmentSourceHeight(client.player);
			embedded[0] = true;
		}
		firstIsLocalHeld = n == 1;

		Box box = new Box(cam.x - RADIUS, cam.y - RADIUS, cam.z - RADIUS,
				cam.x + RADIUS, cam.y + RADIUS, cam.z + RADIUS);
		// Filter inside the spatial query. Previously a crowded hub materialized every nearby mob
		// into this list, only for the loop below to reject it. These are exactly the entity kinds
		// that can reach the existing source-emission branches.
		List<Entity> ents = client.world.getOtherEntities(null, box,
				e -> e instanceof PlayerEntity || e instanceof ItemEntity || e instanceof GlowSquidEntity || e.isOnFire());
		for (Entity e : ents) {
			if (n >= MAX) {
				break;
			}
			// A closed underground section is absent from the terrain/shadow pass. Keeping its burning
			// mobs, dropped torches and remote held items in the dynamic registry still ran shader/light
			// work for a scene that cannot contribute a pixel. The residency predicate is conservative
			// and automatically re-enables the source when the player enters a cave or nearby section.
			if (!com.moneyakshaders.render.ExperimentalSectionRender.isSectionActiveForEntity(
					net.minecraft.util.math.MathHelper.floor(e.getX()) >> 4,
					net.minecraft.util.math.MathHelper.floor(e.getY()) >> 4,
					net.minecraft.util.math.MathHelper.floor(e.getZ()) >> 4)) {
				continue;
			}
			if (e instanceof PlayerEntity pe) {
				if (pe != client.player) {
					int before = n;
					n = addHeld(buf, n, pe);
					if (n > before) {
						entities[before] = pe;
						yOffsets[before] = equipmentSourceHeight(pe);
						embedded[before] = true;
					}
				}
				continue;
			}
			float lvl = 0f;
			float r = 1f, g = 0.6f, b = 0.3f;
			float yOff = (float) (e.getHeight() * 0.5f);
			if (e.getHeight() >= 1.5f) yOff = Math.min(e.getHeight() - 0.1f, yOff + 0.5f);
			if (e instanceof ItemEntity ie) {
				int l = itemLuminance(ie.getStack());
				if (l > 0) {
					lvl = l;
					float[] c = itemColor(ie.getStack());
					r = c[0]; g = c[1]; b = c[2];
					// Items lie on the ground (Y ≈ 0.125) so the radius sphere mostly disappears below
					// the floor — the surface looks barely lit. Lift the dropped-item light source so it
					// covers the world the same way a held / placed torch of the same luminance does.
					// Keep the illumination origin stable while the visual item bobs. A slightly raised
					// origin leaves the item below its own light, so its silhouette can remain on the floor
					// without making the whole light pool pulse up and down with the render animation.
					yOff = 0.72f;
				}
			} else if (e instanceof GlowSquidEntity) {
				lvl = 9f; r = 0.40f; g = 0.80f; b = 1.0f;
			}
			if (lvl <= 0f && e.isOnFire()) {
				lvl = 11f; r = 1.0f; g = 0.60f; b = 0.30f; // burning mob / flaming arrow
			}
			if (lvl > 0f) {
				if (e instanceof ItemEntity) {
					n = addOrMergeDropped(buf, entities, yOffsets, embedded, n, e, yOff, lvl, r, g, b);
				} else if (n < MAX) {
					int index = n++;
					put(buf, index, (float) e.getX(), (float) e.getY() + yOff, (float) e.getZ(), lvl, r, g, b);
					entities[index] = e;
					yOffsets[index] = yOff;
					embedded[index] = true;
				}
			}
		}
		// Dropped clusters accumulate colour additively while scanning. Normalise only once so the
		// result is independent of entity iteration order and mixed piles keep full brightness.
		for (int i = 0; i < n; i++) {
			if (!(entities[i] instanceof ItemEntity)) continue;
			int o = i * 7;
			float peak = Math.max(buf[o + 4], Math.max(buf[o + 5], buf[o + 6]));
			if (peak > 0f) {
				buf[o + 4] /= peak;
				buf[o + 5] /= peak;
				buf[o + 6] /= peak;
			}
		}
		// Publish the index before count; boost falls back to the exact linear path only if a renderer
		// observes a transition mid-tick (normally client/render access is serialized).
		sourceGrid = SourceGrid.build(buf, n);
		sourceEntities = entities;
		sourceYOffsets = yOffsets;
		embeddedSources = embedded;
		data = buf;
		count = n;
		invalidateRenderSnapshot();
	}

	private static float equipmentSourceHeight(PlayerEntity player) {
		// Standing: 1.08 -> 1.58 blocks, clearing a one-block step. Keep the source inside
		// the current pose when crouching/swimming, rather than pushing it into the ceiling.
		return Math.max(0.1f, Math.min(player.getHeight() - 0.1f, player.getHeight() * 0.6f + 0.5f));
	}

	private static int addHeld(float[] buf, int n, PlayerEntity p) {
		if (n >= MAX) {
			return n;
		}
		// Hands + all four armor slots — a glowing block worn (e.g. torch in the helmet slot) now
		// emits light the same as if it were held or placed. Co-located equipment is one physical
		// source; additively merge its hues instead of discarding every colour but the brightest item.
		equipmentLight(p, EQUIPMENT_LIGHT_SCRATCH);
		int luminance = (int) EQUIPMENT_LIGHT_SCRATCH[3];
		if (luminance > 0) {
			put(buf, n++, (float) p.getX(), (float) p.getY() + equipmentSourceHeight(p), (float) p.getZ(),
					luminance, EQUIPMENT_LIGHT_SCRATCH[0], EQUIPMENT_LIGHT_SCRATCH[1], EQUIPMENT_LIGHT_SCRATCH[2]);
		}
		return n;
	}

	private static void equipmentLight(PlayerEntity player, float[] out) {
		float r = 0f, g = 0f, b = 0f;
		int maxLuminance = 0;
		for (int slot = 0; slot < 6; slot++) {
			ItemStack stack = switch (slot) {
				case 0 -> player.getMainHandStack();
				case 1 -> player.getOffHandStack();
				case 2 -> player.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD);
				case 3 -> player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST);
				case 4 -> player.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS);
				default -> player.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET);
			};
			int luminance = itemLuminance(stack);
			if (luminance <= 0) continue;
			float[] color = itemColor(stack);
			r += color[0] * luminance;
			g += color[1] * luminance;
			b += color[2] * luminance;
			maxLuminance = Math.max(maxLuminance, luminance);
		}
		float peak = Math.max(r, Math.max(g, b));
		if (peak <= 0f) {
			out[0] = out[1] = out[2] = out[3] = 0f;
			return;
		}
		out[0] = r / peak;
		out[1] = g / peak;
		out[2] = b / peak;
		out[3] = maxLuminance;
	}

	private static int addOrMergeDropped(float[] buf, Entity[] entities, float[] yOffsets,
			boolean[] embedded, int n, Entity entity, float yOff, float lvl, float r, float g, float b) {
		float x = (float) entity.getX();
		float y = (float) entity.getY() + yOff;
		float z = (float) entity.getZ();
		for (int i = 0; i < n; i++) {
			if (!(entities[i] instanceof ItemEntity)) continue;
			int o = i * 7;
			float dx = x - buf[o], dy = y - buf[o + 1], dz = z - buf[o + 2];
			if (dx * dx + dy * dy + dz * dz > DROPPED_CLUSTER_RADIUS_SQ) continue;
			buf[o + 3] = Math.max(buf[o + 3], lvl);
			buf[o + 4] += r * lvl;
			buf[o + 5] += g * lvl;
			buf[o + 6] += b * lvl;
			return n;
		}
		if (n >= MAX) return n;
		put(buf, n, x, y, z, lvl, r * lvl, g * lvl, b * lvl);
		entities[n] = entity;
		yOffsets[n] = yOff;
		embedded[n] = false;
		return n + 1;
	}

	private static void put(float[] buf, int i, float x, float y, float z, float lvl, float r, float g, float b) {
		int o = i * 7;
		buf[o] = x; buf[o + 1] = y; buf[o + 2] = z; buf[o + 3] = lvl; buf[o + 4] = r; buf[o + 5] = g; buf[o + 6] = b;
	}

	/**
	 * Lift the block-light of a packed lightmap coordinate ({@code block<<4 | sky<<20}) using the
	 * nearest dynamic source (linear distance falloff, like vanilla light spread). Returns the
	 * input unchanged when no source reaches the position.
	 */
	public static int boost(double ex, double ey, double ez, int packed) {
		float[] d = data;
		int n = count;
		if (n == 0) {
			return packed;
		}
		float dyn = 0f;
		int block = (packed >> 4) & 0xF;
		SourceGrid grid = sourceGrid;
		if (grid.sourceCount != n) {
			return boostLinear(d, n, ex, ey, ez, packed);
		}
		int cellX = Math.floorDiv((int) Math.floor(ex), 16);
		int cellY = Math.floorDiv((int) Math.floor(ey), 16);
		int cellZ = Math.floorDiv((int) Math.floor(ez), 16);
		for (int gx = cellX - 1; gx <= cellX + 1; gx++) {
			for (int gy = cellY - 1; gy <= cellY + 1; gy++) {
				for (int gz = cellZ - 1; gz <= cellZ + 1; gz++) {
					for (int i = grid.first(gx, gy, gz); i >= 0; i = grid.next(i)) {
			int o = i * 7;
			float dx = (float) ex - d[o], dy = (float) ey - d[o + 1], dz = (float) ez - d[o + 2];
			float sourceLevel = d[o + 3];
			float distanceSq = dx * dx + dy * dy + dz * dz;
			// Most sources in a busy multiplayer hub cannot reach a given entity/BE. Rejecting
			// those by squared distance avoids an otherwise unconditional sqrt for every one of
			// the (up to 32) sources, while preserving the exact linear falloff for reachable ones.
			if (distanceSq >= sourceLevel * sourceLevel) {
				continue;
			}
			float lvl = sourceLevel - (float) Math.sqrt(distanceSq);
							if (lvl > dyn && lvl > block && isTickSourceVisible(i, ex, ey, ez)) {
								dyn = lvl;
			}
					}
				}
			}
		}
		if (dyn <= 0f) {
			return packed;
		}
		int sky = (packed >> 20) & 0xF;
		int nb = Math.max(block, Math.min(15, (int) dyn));
		return (nb << 4) | (sky << 20);
	}

	private static int boostLinear(float[] d, int n, double ex, double ey, double ez, int packed) {
		float dyn = 0f;
		int block = (packed >> 4) & 0xF;
		for (int i = 0; i < n; i++) {
			int o = i * 7;
			float dx = (float) ex - d[o], dy = (float) ey - d[o + 1], dz = (float) ez - d[o + 2];
			float sourceLevel = d[o + 3];
			float distanceSq = dx * dx + dy * dy + dz * dz;
			if (distanceSq >= sourceLevel * sourceLevel) continue;
			float lvl = sourceLevel - (float) Math.sqrt(distanceSq);
			if (lvl > dyn && lvl > block && isTickSourceVisible(i, ex, ey, ez)) dyn = lvl;
		}
		if (dyn <= 0f) return packed;
		int sky = (packed >> 20) & 0xF;
		int nb = Math.max(block, Math.min(15, (int) dyn));
		return (nb << 4) | (sky << 20);
	}

	/** Cached per-tick collider ray used only after distance/falloff proves a source can brighten a target. */
	public static boolean isVisibleFromSource(int source, double ex, double ey, double ez) {
		MinecraftClient client = MinecraftClient.getInstance();
		float[] d = renderData(client);
		if (client.world == null || source < 0 || source >= renderCount || source * 7 + 2 >= d.length) return false;
		int tickSource = renderSourceIndices[source];
		int o = source * 7;
		return rayVisible(client, tickSource, d[o], d[o + 1], d[o + 2], ex, ey, ez);
	}

	private static boolean isTickSourceVisible(int source, double ex, double ey, double ez) {
		MinecraftClient client = MinecraftClient.getInstance();
		float[] d = data;
		if (client.world == null || source < 0 || source >= count || source * 7 + 2 >= d.length) return false;
		int o = source * 7;
		return rayVisible(client, source, d[o], d[o + 1], d[o + 2], ex, ey, ez);
	}

	private static boolean rayVisible(MinecraftClient client, int source, float sx, float sy, float sz, double ex, double ey, double ez) {
		int tx = (int)Math.floor(ex), ty = (int)Math.floor(ey), tz = (int)Math.floor(ez);
		long target = net.minecraft.util.math.BlockPos.asLong(tx, ty, tz);
		long key = target * 0x9E3779B97F4A7C15L ^ (long)source * 0xC2B2AE3D27D4EB4FL;
		int slot = (int)(key ^ key >>> 32) & (OCCLUSION_CACHE_SIZE - 1);
		if (occlusionStamps[slot] == occlusionGeneration && occlusionKeys[slot] == key) return occlusionValues[slot] != 0;

		Vec3d end = new Vec3d(ex, ey, ez);
		HitResult hit = client.world.raycast(new RaycastContext(new Vec3d(sx, sy, sz), end,
				RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, client.player));
		boolean visible = hit.getType() == HitResult.Type.MISS || hit.getPos().squaredDistanceTo(end) <= 0.04;
		occlusionKeys[slot] = key;
		occlusionStamps[slot] = occlusionGeneration;
		occlusionValues[slot] = (byte)(visible ? 1 : 0);
		return visible;
	}

	private static int itemLuminance(ItemStack s) {
		if (s == null || s.isEmpty()) {
			return 0;
		}
		if (s.getItem() instanceof BlockItem bi) {
			return bi.getBlock().getDefaultState().getLuminance();
		}
		if (s.getItem() == Items.LAVA_BUCKET) {
			return 15;
		}
		if (s.getItem() == Items.GLOW_INK_SAC) {
			return 8;
		}
		return 0;
	}

	private static float[] itemColor(ItemStack s) {
		if (s.getItem() == Items.LAVA_BUCKET) {
			return LAVA;
		}
		if (s.getItem() == Items.GLOW_INK_SAC) {
			return GLOW_INK;
		}
		if (s.getItem() instanceof BlockItem bi) {
			return blockColor(bi.getBlock());
		}
		return WARM;
	}

	public static float[] blockColor(net.minecraft.block.Block b) {
		return PointLightRegistry.rgb(b);
	}
}

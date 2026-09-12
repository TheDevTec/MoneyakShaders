package com.moneyakshaders.client;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.moneyakshaders.render.ExperimentalSectionRender;
import com.moneyakshaders.render.ShadowLodPolicy;

/**
 * Captures the real, animated entity-model geometry (every {@code ModelPart.Cuboid} of every
 * rendered entity — head, body, arms, legs, armor, resource-pack remodels) during the world's
 * entity pass, so the shadow pass can cast model-shaped shadows instead of hitbox boxes.
 *
 * <p>Capture window: {@link ExperimentalSectionRender} sets {@link #active} true after its shadow
 * pass (end of the OPAQUE section hook) and false at the TRANSLUCENT hook — exactly the span where
 * MC renders world entities, so first-person hand / GUI models are excluded. Each cuboid's 8
 * corners are transformed by its pose matrix into WORLD space and written as 12 triangles. The
 * write buffer is swapped to "ready" each frame; the shadow pass (which runs before entities) uses
 * the previous frame's geometry — a 1-frame lag, invisible in practice.
 *
 * <p>Render-thread only.
 */
public final class EntityShadowCapture {
	private static final int CAP = 300_000; // floats (~100k verts ≈ 2800 cuboids)
	public static volatile boolean active;

	private static long readyDirectionalRevision;
	private static long readyPointShadowRevision;
	private static int readyDirectionalCount;
	private static int readyPointShadowCount;
	private static long readyDirectionalFingerprint = Long.MIN_VALUE;
	private static long readyPointShadowFingerprint = Long.MIN_VALUE;
		private static float[] writeBuf = new float[CAP];
	private static int writeCount;
	private static float[] readyBuf = new float[CAP];
	private static int readyCount;
	private static int writeMotionCount;
	private static final int MOTION_CAP = 16_384;
	private static final long[] MOTION_HASHES = new long[MOTION_CAP];
	private static final float[] MOTION_X = new float[MOTION_CAP];
	private static final float[] MOTION_Y = new float[MOTION_CAP];
	private static final float[] MOTION_Z = new float[MOTION_CAP];
	private static final float[] MOTION_RADIUS = new float[MOTION_CAP];
	private static int readyMotionCount;
	private static boolean readyMotionOverflow;
	private static long readyMotionFingerprint = Long.MIN_VALUE;
	private static double camX, camY, camZ;
	// Set once around EntityRenderManager.render.  A posed arm/leg has a different local translation
	// from the entity root; using that per-cuboid value for LOD made animated limbs cross thresholds
	// independently and flicker in/out of the shadow stream.
	// Capture is render-thread-only. Primitive state avoids one boxed Double allocation per entity.
	private static boolean entityScopeActive;
	private static double entityDistanceSq;
	// All parts of one entity use the same LOD. FAR still keeps the real cached/model silhouette, but
	// drops small cuboids/quads; an entity AABB is not a valid proxy for armor stands carrying large
	// resource-pack models (and marker stands can have an almost empty box).
	private static boolean entityScopeDetailedModelCapture;
	private static int entityScopeLod = ShadowLodPolicy.LOD_NEAR;
	private static int entityScopeMotionIndex = -1;
	private static long entityScopePoseHash;
	private static int entityScopePoseSamples;
	// Textured item quads are already a compact silhouette stream. They must not share the model
	// cuboid LOD switch: doing so made held/dropped item shadows lose their texture as soon as a
	// normal entity crossed the MID -> FAR threshold (~6.25 blocks for the default 0.75 radius).
	// Keep those silhouettes until the explicit entityShadowDistance cap instead.
	private static boolean entityScopeTexturedItemCapture;
	// EntityRenderManager extracts and renders these states on the render thread. An identity map
	// avoids both equals/hashCode work and a lock per visible entity; the defensive bound covers an
	// incompatible renderer that extracts states but never submits them back to the manager.
	private static final Map<net.minecraft.client.render.entity.state.EntityRenderState, Entity> STATE_ENTITIES
			= new IdentityHashMap<>();

	// Item geometry is captured separately because it needs UVs: items (swords, tools, blocks) are
	// drawn into the shadow map with the block atlas + alpha discard so the SHADOW is the texture
	// silhouette (sword shape) instead of the full quad rectangle (the "big square" bug). 5 floats
	// per vertex: x,y,z,u,v.
	private static final int ICAP = 150_000; // floats → 30k verts → 5k item quads
	private static float[] itemWrite = new float[ICAP];
	private static int itemWriteCount;
	private static float[] itemReady = new float[ICAP];
	private static int itemReadyCount;

	// Item models live on TWO atlases in 1.21.11: regular items on the items atlas, but BLOCK items
	// (held/dropped blocks) on the BLOCK atlas. The shadow alpha-discard must sample the right one,
	// so block-item quads are captured into a SECOND buffer and drawn with the block atlas. Without
	// this, block items sampled the wrong atlas → wrong/zero alpha → no shadow.
	private static float[] blockItemWrite = new float[ICAP];
	private static int blockItemWriteCount;
	private static float[] blockItemReady = new float[ICAP];
	private static int blockItemReadyCount;

	private static final Vector3f TMP = new Vector3f();
	private static final float[] C = new float[24]; // 8 transformed corners (world space)
	private static final float[] Q = new float[20]; // 4 transformed quad corners (x,y,z,u,v each)

	private EntityShadowCapture() {
	}

	/**
	 * Begin capturing this frame's entity cuboids. MC renders entities with a fresh-identity
	 * {@code MatrixStack} translated by the entity's camera-relative position (the camera rotation
	 * lives in the GL modelview, not this stack), so the pose matrix already yields camera-relative
	 * world coords — adding the camera position gives absolute world space, no rotation needed.
	 */
	public static void begin(double cx, double cy, double cz) {
		active = true;
		writeCount = 0;
		itemWriteCount = 0;
		blockItemWriteCount = 0;
		writeMotionCount = 0;
		camX = cx;
		camY = cy;
		camZ = cz;
	}

	public static long readyDirectionalRevision() {
		return readyDirectionalRevision;
	}

	public static long readyPointShadowRevision() {
		return readyPointShadowRevision;
	}

	public static int readyDirectionalCount() {
		return readyDirectionalCount;
	}

	public static int readyPointShadowCount() {
		return readyPointShadowCount;
	}

	private static long geometryFingerprint() {
		long hash = fingerprint(readyBuf, readyCount, 0xcbf29ce484222325L);
		hash = fingerprint(itemReady, itemReadyCount, hash);
		return fingerprint(blockItemReady, blockItemReadyCount, hash);
	}

	private static long pointShadowFingerprint() {
		if (readyMotionOverflow) return readyPointShadowFingerprint ^ readyDirectionalFingerprint ^ writeMotionCount;
		long sum = 0L, xor = 0L;
		for (int i = 0; i < readyMotionCount; i++) {
			long h = MOTION_HASHES[i];
			sum += h;
			xor ^= Long.rotateLeft(h, (int)h & 63);
		}
		return sum ^ Long.rotateLeft(xor, 23) ^ ((long)writeMotionCount * 0x9e3779b97f4a7c15L);
	}

	private static long combineMotionHashes(long sum, long xor, int count) {
		return sum ^ Long.rotateLeft(xor, 23) ^ ((long)count * 0x9e3779b97f4a7c15L);
	}

	/** Associates an extracted render state with its source only until the immediately following render. */
	public static void associate(net.minecraft.client.render.entity.state.EntityRenderState state, Entity entity) {
		// Vanilla may extract render states before our OPAQUE hook opens the entity capture window.
		// Dropping that early association made motion invalidation depend on camera/source movement:
		// a walking mob then cast a frozen (apparently missing) point shadow while the player stood
		// still. Keep the cheap identity association until EntityRenderManager.render consumes it.
		com.moneyakshaders.MoneyakShadersConfig cfg = com.moneyakshaders.MoneyakShadersConfig.get();
		if (!cfg.experimentalRenderer || !cfg.entityShadows || (!cfg.sunShadows && !cfg.pointLightShadows)) {
			return;
		}
		if (STATE_ENTITIES.size() > 16_384) {
			STATE_ENTITIES.clear();
		}
		STATE_ENTITIES.put(state, entity);
	}

	private static Entity takeAssociated(net.minecraft.client.render.entity.state.EntityRenderState state) {
		return STATE_ENTITIES.remove(state);
	}

	/** Opens one entity-wide capture scope; all its model parts must make the same LOD decision. */
	public static void beginEntityScope(net.minecraft.client.render.entity.state.EntityRenderState state,
			double offsetX, double offsetY, double offsetZ) {
		entityScopeMotionIndex = -1;
		entityScopePoseHash = 0xcbf29ce484222325L;
		entityScopePoseSamples = 0;
		if (!active) {
			// Normal entity rendering does not need a scope outside the small capture window.  Keeping
			// this before the association lookup avoids map work and LOD math for every visible entity.
			entityScopeActive = false;
			entityScopeDetailedModelCapture = true;
			entityScopeTexturedItemCapture = true;
			entityScopeLod = ShadowLodPolicy.LOD_NEAR;
			return;
		}
		// Render states are pooled/reused; consume the association on every path, including culled LODs.
		Entity entity = takeAssociated(state);
		if (entity != null) {
			// Renderer offsets are already tick-interpolated. Reconstruct world position so a walking
			// silhouette advances every frame instead of jumping at the entity's 20 Hz logical position.
			entityScopeMotionIndex = recordEntityMotion(entity,
					offsetX + camX, offsetY + camY, offsetZ + camZ);
		}
		entityDistanceSq = offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ;
		entityScopeActive = true;
		entityScopeDetailedModelCapture = true;
		entityScopeTexturedItemCapture = true;
		entityScopeLod = ShadowLodPolicy.LOD_NEAR;
		// Underground residency is the single source of truth for unused-scene work.  Vanilla may still
		// submit an entity while its terrain section is sleeping; recording its model only to discard the
		// section from the shadow pass wastes CPU and transient VBO bandwidth.
		if (entity != null && !com.moneyakshaders.render.ExperimentalSectionRender.isSectionActiveForEntity(
				entity.getBlockX() >> 4, entity.getBlockY() >> 4, entity.getBlockZ() >> 4)) {
			entityScopeDetailedModelCapture = false;
			entityScopeTexturedItemCapture = false;
			return;
		}
		if (!active || beyondShadowDistance(null)) {
			entityScopeDetailedModelCapture = false;
			entityScopeTexturedItemCapture = false;
			return;
		}
		int lod = ShadowLodPolicy.classifySquared(entityDistanceSq, 0.75f);
		// The explicit distance setting is authoritative. Past the screen-space FAR threshold retain
		// FAR's reduced real geometry instead of silently turning the shadow off before the configured cap.
		entityScopeLod = lod == ShadowLodPolicy.LOD_NONE ? ShadowLodPolicy.LOD_FAR : lod;
	}

	public static void endEntityScope() {
		if (entityScopeMotionIndex >= 0) {
			long h = MOTION_HASHES[entityScopeMotionIndex] ^ entityScopePoseHash;
			h ^= h >>> 30;
			h *= 0xbf58476d1ce4e5b9L;
			h ^= h >>> 27;
			h *= 0x94d049bb133111ebL;
			h ^= h >>> 31;
			MOTION_HASHES[entityScopeMotionIndex] = h;
		}
		entityScopeMotionIndex = -1;
		entityScopeActive = false;
		entityScopeDetailedModelCapture = true;
		entityScopeTexturedItemCapture = true;
		entityScopeLod = ShadowLodPolicy.LOD_NEAR;
		entityScopePoseHash = 0L;
		entityScopePoseSamples = 0;
	}

	/**
	 * Fast entity-wide gate for cached geometry replay. Callers can avoid walking every cached quad
	 * when this entity is outside the configured shadow range, underground, or outside the capture
	 * window. The per-quad LOD test remains authoritative for entities that pass this coarse gate.
	 */
	public static boolean shouldCaptureStaticGeometry() {
		return active && entityScopeActive && entityScopeDetailedModelCapture;
	}

	/** End capture and promote this frame's geometry to "ready" for the next frame's shadow pass. */
	public static void end() {
		active = false;

		float[] geometrySwap = readyBuf;
		readyBuf = writeBuf;
		readyCount = writeCount;
		writeBuf = geometrySwap;
		writeCount = 0;

		float[] itemSwap = itemReady;
		itemReady = itemWrite;
		itemReadyCount = itemWriteCount;
		itemWrite = itemSwap;
		itemWriteCount = 0;

		float[] blockItemSwap = blockItemReady;
		blockItemReady = blockItemWrite;
		blockItemReadyCount = blockItemWriteCount;
		blockItemWrite = blockItemSwap;
		blockItemWriteCount = 0;

		readyDirectionalCount = readyCount + itemReadyCount / 5 * 3 + blockItemReadyCount / 5 * 3;
		long directionalFingerprint = geometryFingerprint();
		if (directionalFingerprint != readyDirectionalFingerprint) {
			readyDirectionalFingerprint = directionalFingerprint;
			readyDirectionalRevision++;
		}

		readyMotionCount = Math.min(writeMotionCount, MOTION_CAP);
		readyMotionOverflow = writeMotionCount > MOTION_CAP;
		readyPointShadowCount = readyMotionCount;

		long sum = 0L, xor = 0L;
		for (int i = 0; i < readyMotionCount; i++) {
			long h = MOTION_HASHES[i];
			sum += h;
			xor ^= Long.rotateLeft(h, (int)h & 63);
		}

		long pointFingerprint = combineMotionHashes(sum, xor, readyMotionCount);
		if (readyMotionOverflow) pointFingerprint ^= readyDirectionalFingerprint;
		readyMotionFingerprint = pointFingerprint;

		if (pointFingerprint != readyPointShadowFingerprint) {
			readyPointShadowFingerprint = pointFingerprint;
			readyPointShadowRevision++;
		}

		STATE_ENTITIES.clear();
	}
	/**
	 * Order-independent fingerprint of visible entity identity + actual world position. Model pose,
	 * item spin and idle animation deliberately do not participate: point cubemaps exclude their own
	 * emitter and only need rebuilding when an occluder physically moves through the world.
	 */
	private static int recordEntityMotion(Entity entity, double x, double y, double z) {
		long h = entity.getId() * 0x9e3779b97f4a7c15L;
		h ^= (long) Math.round(x * 512.0) * 0xbf58476d1ce4e5b9L;
		h ^= (long) Math.round(y * 512.0) * 0x94d049bb133111ebL;
		h ^= Long.rotateLeft((long) Math.round(z * 512.0), 31);
		h ^= h >>> 30;
		h *= 0xbf58476d1ce4e5b9L;
		h ^= h >>> 27;
		h *= 0x94d049bb133111ebL;
		h ^= h >>> 31;
		int index = writeMotionCount++;
		if (index < MOTION_CAP) {
			MOTION_HASHES[index] = h;
			MOTION_X[index] = (float) x;
			MOTION_Y[index] = (float) y;
			MOTION_Z[index] = (float) z;
			// A conservative carrier sphere keeps large mobs and equipment/model geometry relevant while
			// still excluding the thousands of entities which cannot intersect this light's cubemap.
			MOTION_RADIUS[index] = Math.max(1.0f,
					Math.max(entity.getWidth(), entity.getHeight()) * 0.75f + 1.0f);
			return index;
		}
		return -1;
	}

	/** Adds a bounded world-space model-pose signature to the current entity's spatial fingerprint. */
	private static void recordEntityPose(Matrix4f matrix) {
		if (entityScopeMotionIndex < 0 || entityScopePoseSamples++ >= 12) return;
		entityScopePoseHash = poseMix(entityScopePoseHash, matrix.m00());
		entityScopePoseHash = poseMix(entityScopePoseHash, matrix.m01());
		entityScopePoseHash = poseMix(entityScopePoseHash, matrix.m02());
		entityScopePoseHash = poseMix(entityScopePoseHash, matrix.m10());
		entityScopePoseHash = poseMix(entityScopePoseHash, matrix.m11());
		entityScopePoseHash = poseMix(entityScopePoseHash, matrix.m12());
		entityScopePoseHash = poseMix(entityScopePoseHash, matrix.m20());
		entityScopePoseHash = poseMix(entityScopePoseHash, matrix.m21());
		entityScopePoseHash = poseMix(entityScopePoseHash, matrix.m22());
		// Camera-relative translation plus the captured camera origin is stable in world space.
		entityScopePoseHash = poseMix(entityScopePoseHash, matrix.m30() + (float) camX);
		entityScopePoseHash = poseMix(entityScopePoseHash, matrix.m31() + (float) camY);
		entityScopePoseHash = poseMix(entityScopePoseHash, matrix.m32() + (float) camZ);
	}

	private static long poseMix(long hash, float value) {
		hash ^= Math.round(value * 512f);
		return hash * 0x100000001b3L;
	}

	/**
	 * Order-independent motion fingerprint limited to entities which can intersect one point light.
	 * A distant mob must not invalidate every cubemap; entering, leaving or moving inside the sphere
	 * changes this value immediately. Overflow falls back to the conservative global fingerprint.
	 */
	public static long spatialMotionFingerprint(double lightX, double lightY, double lightZ, float lightRange) {
		if (readyMotionOverflow) return readyPointShadowFingerprint;
		long sum = 0L, xor = 0L;
		int count = 0;
		for (int i = 0; i < readyMotionCount; i++) {
			double dx = MOTION_X[i] - lightX;
			double dy = MOTION_Y[i] - lightY;
			double dz = MOTION_Z[i] - lightZ;
			double reach = lightRange + MOTION_RADIUS[i];
			if (dx * dx + dy * dy + dz * dz > reach * reach) continue;
			long h = MOTION_HASHES[i];
			sum += h;
			xor ^= Long.rotateLeft(h, (int)h & 63);
			count++;
		}
		return combineMotionHashes(sum, xor, count);
	}

	/**
	 * Quantized, evenly sampled geometry fingerprint. It ignores sub-millimetre camera-cancellation
	 * noise, but notices ordinary translation, pose and item-bob changes without hashing the full
	 * worst-case 600k-float capture every frame.
	 */
	private static long fingerprint(float[] values, int count, long hash) {
		hash ^= count;
		hash *= 0x100000001b3L;
		int samples = Math.min(count, 384);
		for (int i = 0; i < samples; i++) {
			int index = (int) ((long) i * count / samples);
			int quantized = Math.round(values[index] * 512.0f);
			hash ^= quantized;
			hash *= 0x100000001b3L;
		}
		return hash;
	}

	public static float[] readyBuffer() {
		return readyBuf;
	}

	public static int readyCount() {
		return readyCount;
	}

	/** Monotonic frame revision for consumers which retain a shadow map across render frames. */
	public static long readyRevision() {
		return readyDirectionalRevision;
	}

	/** Revision that advances only when a visible entity appears, disappears or changes position. */
	public static long readyMotionRevision() {
		return readyPointShadowRevision;
	}

	/** Item-atlas shadow geometry (x,y,z,u,v per vertex), drawn with the ITEMS atlas + alpha discard. */
	public static float[] itemReadyBuffer() {
		return itemReady;
	}

	public static int itemReadyCount() {
		return itemReadyCount;
	}

	/** Block-atlas item shadow geometry (held/dropped block items), drawn with the BLOCK atlas. */
	public static float[] blockItemReadyBuffer() {
		return blockItemReady;
	}

	public static int blockItemReadyCount() {
		return blockItemReadyCount;
	}

	/** The capture matrices produce CAMERA-RELATIVE coords, so the matrix translation length is the
	 *  part's distance from the camera — cheap distance cap without touching the entity itself. */
	private static boolean beyondShadowDistance(Matrix4f m) {
		int cap = com.moneyakshaders.MoneyakShadersConfig.get().entityShadowDistance;
		if (cap <= 0) {
			return false;
		}
		if (entityScopeActive) {
			return entityDistanceSq > (double) cap * cap;
		}
		float tx = m.m30(), ty = m.m31(), tz = m.m32();
		return tx * tx + ty * ty + tz * tz > (float) cap * (float) cap;
	}

	/**
	 * Shadow capture happens below the entity renderer, where we intentionally have no dependency on
	 * a particular entity implementation. The pose matrix still gives us a stable camera-relative
	 * origin, however, so screen-space LOD can be selected before transforming or writing any vertex.
	 *
	 * <p>The conservative 0.75-block radius represents a normal entity's body rather than a single
	 * limb. It prevents a thin arm or a held item from independently making the whole entity disappear
	 * from the shadow stream. Near casters retain the exact captured model; middle and far LODs discard
	 * only sub-pixel detail cuboids. This keeps the capture stream bounded in crowded multiplayer areas
	 * without requiring a second model evaluation or allocating a proxy per entity.
	 */
	private static int shadowLod(Matrix4f m) {
		if (entityScopeActive) {
			return entityScopeLod;
		}
		return ShadowLodPolicy.classify(m.m30(), m.m31(), m.m32(), 0.75f);
	}

	private static boolean keepCuboidForShadowLod(int lod, float x0, float y0, float z0,
			float x1, float y1, float z1) {
		if (lod == ShadowLodPolicy.LOD_NONE) {
			return false;
		}
		if (lod == ShadowLodPolicy.LOD_NEAR) {
			return true;
		}
		float largestAxis = Math.max(Math.abs(x1 - x0), Math.max(Math.abs(y1 - y0), Math.abs(z1 - z0))) / 16.0f;
		// Mid retains ordinary body/limb geometry; far becomes a stable coarse body proxy assembled
		// from the largest posed cuboids. Thresholds are in block units after ModelPart's pixel scale.
		return largestAxis >= (lod == ShadowLodPolicy.LOD_MID ? 0.125f : 0.25f);
	}

	/** Called from the Cuboid.renderCuboid mixin: append this posed cuboid as 12 world-space triangles. */
	public static void captureCuboid(Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1) {
		if (!active || !entityScopeDetailedModelCapture || writeCount + 108 > CAP) {
			return;
		}
		if (beyondShadowDistance(m) || !keepCuboidForShadowLod(shadowLod(m), x0, y0, z0, x1, y1, z1)) {
			return; // E9.1: far entities don't cast model shadows (big win in mob farms)
		}
		recordEntityPose(m);
		// Cuboid min/max are in model PIXEL units; the rendered vertices are these /16 (ModelPart.Vertex.worldX).
		// Match that or the shadow geometry is 16× too large and flung far from the entity.
		x0 /= 16f; y0 /= 16f; z0 /= 16f;
		x1 /= 16f; y1 /= 16f; z1 /= 16f;
		corner(m, x0, y0, z0, 0);
		corner(m, x1, y0, z0, 3);
		corner(m, x1, y1, z0, 6);
		corner(m, x0, y1, z0, 9);
		corner(m, x0, y0, z1, 12);
		corner(m, x1, y0, z1, 15);
		corner(m, x1, y1, z1, 18);
		corner(m, x0, y1, z1, 21);
		quad(0, 1, 2, 3); // -Z
		quad(5, 4, 7, 6); // +Z
		quad(4, 0, 3, 7); // -X
		quad(1, 5, 6, 2); // +X
		quad(4, 5, 1, 0); // -Y
		quad(3, 2, 6, 7); // +Y
	}

	/**
	 * Capture one item {@link BakedQuad} as 2 world-space triangles, so held/dropped items cast a
	 * shadow. Item-model positions are already in block units (0..1), NOT pixel units like cuboids,
	 * so there is no /16 here — the supplied matrix carries the item's full transform (scale, spin,
	 * entity offset) and yields camera-relative coords; +cam lifts them to world space.
	 */
	public static void captureItemQuad(Matrix4f m, BakedQuad quad) {
		if (!active || !entityScopeTexturedItemCapture) {
			return;
		}
		if (beyondShadowDistance(m)) {
			return; // Respect the explicit entity-shadow distance cap.
		}
		recordEntityPose(m);
		// Route by the quad's sprite atlas: block items (held/dropped blocks) sit on the BLOCK atlas,
		// everything else on the items atlas. Each goes to its own buffer so the shadow pass can bind
		// the matching atlas for the alpha-discard silhouette.
		boolean blockAtlas = isBlockAtlas(quad);
		if (blockAtlas) {
			if (blockItemWriteCount + 30 > ICAP) {
				return;
			}
		} else if (itemWriteCount + 30 > ICAP) {
			return;
		}
		for (int i = 0; i < 4; i++) {
			Vector3fc p = quad.getPosition(i);
			m.transformPosition(p.x(), p.y(), p.z(), TMP);
			long uv = quad.getTexcoords(i);
			int o = i * 5;
			Q[o] = TMP.x + (float) camX;
			Q[o + 1] = TMP.y + (float) camY;
			Q[o + 2] = TMP.z + (float) camZ;
			Q[o + 3] = Float.intBitsToFloat((int) (uv >>> 32));        // u (atlas-space)
			Q[o + 4] = Float.intBitsToFloat((int) (uv & 0xFFFFFFFFL)); // v
		}
		putI(blockAtlas, 0); putI(blockAtlas, 1); putI(blockAtlas, 2);
		putI(blockAtlas, 0); putI(blockAtlas, 2); putI(blockAtlas, 3);
	}

	/**
	 * Replays an already-captured static-entity quad into the model-shadow stream. Static entity
	 * geometry bypasses {@code ModelPart.render} on cache hits, so without this bridge an armor stand
	 * or display would stop casting its precise shadow after its first rendered frame.
	 */
	public static void captureStaticQuad(Matrix4f m,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz) {
		if (!active || !entityScopeDetailedModelCapture || writeCount + 18 > CAP) {
			return;
		}
		int lod = shadowLod(m);
		if (beyondShadowDistance(m) || !keepStaticQuadForShadowLod(lod,
				ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz)) {
			return;
		}
		recordEntityPose(m);
		staticVertex(m, ax, ay, az);
		staticVertex(m, bx, by, bz);
		staticVertex(m, cx, cy, cz);
		staticVertex(m, ax, ay, az);
		staticVertex(m, cx, cy, cz);
		staticVertex(m, dx, dy, dz);
	}

	/**
	 * Replays one immutable cached layer into the shadow stream. All quads in the layer share the
	 * same entity-root matrix, distance and LOD, so evaluate those once and transform directly into
	 * the destination buffer. The common cached-block-entity path is translation-only; keeping that
	 * branch outside the vertex loop removes millions of redundant Matrix4f operations per second at
	 * resource-pack-heavy spawns. The general affine fallback preserves scaled/rotated displays.
	 */
	public static void captureStaticLayer(Matrix4f m, float[] vertices, int vertexCount) {
		if (!active || !entityScopeDetailedModelCapture || vertexCount < 4 || writeCount + 18 > CAP
				|| beyondShadowDistance(m)) {
			return;
		}
		int lod = shadowLod(m);
		recordEntityPose(m);
		boolean translationOnly = approximatelyIdentityLinear(m);
		float tx = m.m30() + (float) camX;
		float ty = m.m31() + (float) camY;
		float tz = m.m32() + (float) camZ;
		for (int i = 0; i + 3 < vertexCount; i += 4) {
			if (writeCount + 18 > CAP) {
				return;
			}
			int a = i * 8, b = a + 8, c = a + 16, d = a + 24;
			float ax = vertices[a], ay = vertices[a + 1], az = vertices[a + 2];
			float bx = vertices[b], by = vertices[b + 1], bz = vertices[b + 2];
			float cx = vertices[c], cy = vertices[c + 1], cz = vertices[c + 2];
			float dx = vertices[d], dy = vertices[d + 1], dz = vertices[d + 2];
			if (!keepStaticQuadForShadowLod(lod, ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz)) {
				continue;
			}
			if (translationOnly) {
				staticVertexTranslated(ax, ay, az, tx, ty, tz);
				staticVertexTranslated(bx, by, bz, tx, ty, tz);
				staticVertexTranslated(cx, cy, cz, tx, ty, tz);
				staticVertexTranslated(ax, ay, az, tx, ty, tz);
				staticVertexTranslated(cx, cy, cz, tx, ty, tz);
				staticVertexTranslated(dx, dy, dz, tx, ty, tz);
			} else {
				staticVertexDirect(m, ax, ay, az);
				staticVertexDirect(m, bx, by, bz);
				staticVertexDirect(m, cx, cy, cz);
				staticVertexDirect(m, ax, ay, az);
				staticVertexDirect(m, cx, cy, cz);
				staticVertexDirect(m, dx, dy, dz);
			}
		}
	}

	private static boolean keepStaticQuadForShadowLod(int lod,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz) {
		if (lod == ShadowLodPolicy.LOD_NEAR) {
			return true;
		}
		float ab = edgeSq(ax, ay, az, bx, by, bz);
		float bc = edgeSq(bx, by, bz, cx, cy, cz);
		float cd = edgeSq(cx, cy, cz, dx, dy, dz);
		float da = edgeSq(dx, dy, dz, ax, ay, az);
		float minAxis = lod == ShadowLodPolicy.LOD_MID ? 0.125f : 0.25f;
		return Math.max(Math.max(ab, bc), Math.max(cd, da)) >= minAxis * minAxis;
	}

	private static float edgeSq(float ax, float ay, float az, float bx, float by, float bz) {
		float x = bx - ax, y = by - ay, z = bz - az;
		return x * x + y * y + z * z;
	}

	private static void staticVertex(Matrix4f m, float x, float y, float z) {
		m.transformPosition(x, y, z, TMP);
		writeBuf[writeCount++] = TMP.x + (float)camX;
		writeBuf[writeCount++] = TMP.y + (float)camY;
		writeBuf[writeCount++] = TMP.z + (float)camZ;
	}

	private static void staticVertexTranslated(float x, float y, float z, float tx, float ty, float tz) {
		writeBuf[writeCount++] = x + tx;
		writeBuf[writeCount++] = y + ty;
		writeBuf[writeCount++] = z + tz;
	}

	private static void staticVertexDirect(Matrix4f m, float x, float y, float z) {
		writeBuf[writeCount++] = m.m00() * x + m.m10() * y + m.m20() * z + m.m30() + (float) camX;
		writeBuf[writeCount++] = m.m01() * x + m.m11() * y + m.m21() * z + m.m31() + (float) camY;
		writeBuf[writeCount++] = m.m02() * x + m.m12() * y + m.m22() * z + m.m32() + (float) camZ;
	}

	private static boolean approximatelyIdentityLinear(Matrix4f m) {
		return nearOne(m.m00()) && nearZero(m.m01()) && nearZero(m.m02())
				&& nearZero(m.m10()) && nearOne(m.m11()) && nearZero(m.m12())
				&& nearZero(m.m20()) && nearZero(m.m21()) && nearOne(m.m22());
	}

	private static boolean nearZero(float value) { return Math.abs(value) <= 1.0e-6f; }
	private static boolean nearOne(float value) { return Math.abs(value - 1.0f) <= 1.0e-6f; }

	private static boolean isBlockAtlas(BakedQuad quad) {
		try {
			return quad.sprite().getAtlasId().equals(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
		} catch (Throwable ignored) {
			return false; // default to the items atlas (the common case)
		}
	}

	private static void putI(boolean blockAtlas, int i) {
		int o = i * 5;
		if (blockAtlas) {
			blockItemWrite[blockItemWriteCount++] = Q[o];
			blockItemWrite[blockItemWriteCount++] = Q[o + 1];
			blockItemWrite[blockItemWriteCount++] = Q[o + 2];
			blockItemWrite[blockItemWriteCount++] = Q[o + 3];
			blockItemWrite[blockItemWriteCount++] = Q[o + 4];
		} else {
			itemWrite[itemWriteCount++] = Q[o];
			itemWrite[itemWriteCount++] = Q[o + 1];
			itemWrite[itemWriteCount++] = Q[o + 2];
			itemWrite[itemWriteCount++] = Q[o + 3];
			itemWrite[itemWriteCount++] = Q[o + 4];
		}
	}

	private static void corner(Matrix4f m, float x, float y, float z, int o) {
		m.transformPosition(x, y, z, TMP); // model-local → camera-relative world
		C[o] = TMP.x + (float) camX;
		C[o + 1] = TMP.y + (float) camY;
		C[o + 2] = TMP.z + (float) camZ;
	}

	private static void quad(int a, int b, int c, int d) {
		tri(a, b, c);
		tri(a, c, d);
	}

	private static void tri(int a, int b, int c) {
		put(a); put(b); put(c);
	}

	private static void put(int i) {
		int o = i * 3;
		writeBuf[writeCount++] = C[o];
		writeBuf[writeCount++] = C[o + 1];
		writeBuf[writeCount++] = C[o + 2];
	}
}

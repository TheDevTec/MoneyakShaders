package com.moneyakshaders.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.BlockRenderType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.BlockRenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.render.block.MovingBlockRenderState;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.state.BlockEntityRenderState;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.command.RenderCommandQueue;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.ColorHelper;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * "Display list" bake for STATIC block entities. See {@code MoneyakShadersConfig.blockEntityBake}.
 *
 * <p>The per-frame cost of a block entity is (a) extracting its render state and (b) posing +
 * tessellating its model into vertices. For a block entity whose geometry never changes frame to
 * frame — a server's custom decoration model, a static prop — (b) is pure repeated work. This class
 * captures that tessellated geometry <em>once</em>, camera-independent (in the block-entity's local
 * space), and on every later frame replays it straight into the real render queue via a
 * {@link OrderedRenderCommandQueue.Custom} command — skipping {@code Model.setAngles} + the whole
 * model tessellation. Lighting is <em>not</em> baked: the current frame's {@code lightmapCoordinates}
 * (already dynamic-light-boosted upstream) is applied at replay, so day/night and nearby torches
 * still light the block entity correctly with no re-bake.
 *
 * <p>Only pure model geometry is captured (submitModel / submitModelPart / submitCustom vertices).
 * Anything else a renderer emits — block-state models, items, text, fire, leashes — marks the capture
 * <em>unsupported</em>: that block entity is remembered as no-bake and keeps rendering the vanilla way
 * every frame, so nothing is ever drawn wrong, it just isn't optimised. Animated vanilla block
 * entities (chests, banners, bells, …) are blacklisted outright.
 *
 * <p>Render-thread only; no synchronisation.
 */
public final class BakedBlockEntities {
	private BakedBlockEntities() {
	}

	private static final int MAX_ENTRIES = 4096;
	/** Guard against baking a pathological (huge) BE into RAM: skip capture past this vertex count. */
	private static final int MAX_VERTS_PER_BE = 200_000;

	/** Vanilla block entity types that animate (or draw text/items) — never baked. */
	private static final Set<BlockEntityType<?>> BLACKLIST = buildBlacklist();

	/** Sentinel stored for a position whose capture came back unsupported — stop retrying. */
	private static final Baked NO_BAKE = new Baked(null, List.of());

	/** pos.asLong() → captured geometry (or {@link #NO_BAKE}). Access-ordered for cheap LRU eviction. */
	private static final Map<Long, Baked> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Baked> eldest) {
			return size() > MAX_ENTRIES;
		}
	};

	private static Set<BlockEntityType<?>> buildBlacklist() {
		Set<BlockEntityType<?>> s = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		// Lids / swings / spins / cloth sway / text / items / spinning mobs — all animate per frame.
		add(s, BlockEntityType.CHEST); add(s, BlockEntityType.TRAPPED_CHEST); add(s, BlockEntityType.ENDER_CHEST);
		add(s, BlockEntityType.SIGN); add(s, BlockEntityType.HANGING_SIGN);
		add(s, BlockEntityType.BANNER); add(s, BlockEntityType.BELL);
		add(s, BlockEntityType.CONDUIT); add(s, BlockEntityType.MOB_SPAWNER); add(s, BlockEntityType.TRIAL_SPAWNER);
		add(s, BlockEntityType.BEACON); add(s, BlockEntityType.ENCHANTING_TABLE);
		add(s, BlockEntityType.SHULKER_BOX); add(s, BlockEntityType.SKULL);
		add(s, BlockEntityType.CAMPFIRE); add(s, BlockEntityType.LECTERN);
		add(s, BlockEntityType.STRUCTURE_BLOCK); add(s, BlockEntityType.VAULT);
		return s;
	}

	private static void add(Set<BlockEntityType<?>> s, @Nullable BlockEntityType<?> t) {
		if (t != null) {
			s.add(t);
		}
	}

	/** Cheap pre-check used both at feed time and draw time. */
	public static boolean eligible(@Nullable BlockEntityType<?> type) {
		return type != null && !BLACKLIST.contains(type);
	}

	/**
	 * If this block entity is already baked, replay it into {@code queue} and return true (caller then
	 * cancels the vanilla renderer). {@link #NO_BAKE} sentinel also returns false (caller renders vanilla).
	 */
	public static boolean replayIfBaked(BlockEntityRenderState rs, MatrixStack matrices, OrderedRenderCommandQueue queue) {
		Baked baked = CACHE.get(rs.pos.asLong());
		if (baked == null || baked == NO_BAKE || baked.type != rs.type) {
			return false;
		}
		int light = rs.lightmapCoordinates;
		int tint = com.moneyakshaders.client.EntityRenderTint.current();
		for (BakedLayer bl : baked.layers) {
			queue.submitCustom(matrices, bl.layer, bl.command(light, tint));
		}
		return true; // baked (possibly empty geometry) — vanilla draw is skipped either way
	}

	/**
	 * Run {@code renderer} once into a capturing queue and store the result keyed by position. Called
	 * only on the first frame a bakeable block entity is drawn; the vanilla draw still happens this
	 * frame (caller does not cancel), so there is no one-frame blink.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void capture(@Nullable BlockEntityRenderer renderer, BlockEntityRenderState rs, MatrixStack matrices,
			CameraRenderState cameraRenderState) {
		if (renderer == null) {
			CACHE.put(rs.pos.asLong(), NO_BAKE);
			return;
		}
		// Outer translation applied by WorldRenderer: matrices top == (blockPos - camera). Strip it so the
		// captured vertices are in block-entity-local space (camera-independent), re-added at replay time.
		CaptureSession session = beginSharedCapture(matrices, 0.0, 0.0, 0.0);
		try {
			renderer.render(rs, matrices, session.queue(), cameraRenderState);
		} catch (Throwable t) {
			CACHE.put(rs.pos.asLong(), NO_BAKE);
			return;
		}
		CapturedGeometry geometry = finishSharedCapture(session);
		if (geometry == null || !geometry.texts.isEmpty()) {
			CACHE.put(rs.pos.asLong(), NO_BAKE);
			return;
		}
		CACHE.put(rs.pos.asLong(), new Baked(rs.type, geometry.layers));
	}

	/** Drop a position (block replaced / BE removed). */
	public static void invalidate(long posLong) {
		CACHE.remove(posLong);
	}

	public static void clearAll() {
		CACHE.clear();
	}

	public static int size() {
		return CACHE.size();
	}

	/**
	 * Starts a reusable model-geometry capture. {@code originX/Y/Z} is the translation applied by
	 * the caller after the current matrix entry (for entities this is the camera-relative entity
	 * offset). Captured vertices are stored relative to that origin, so the same geometry can be
	 * replayed at a different camera position without rebuilding it.
	 */
	public static CaptureSession beginSharedCapture(MatrixStack matrices, double originX, double originY, double originZ) {
		Matrix4f inversePosition = new Matrix4f(matrices.peek().getPositionMatrix())
				.translate((float)originX, (float)originY, (float)originZ)
				.invert();
		Matrix3f inverseNormal = new Matrix3f(matrices.peek().getNormalMatrix()).invert();
		return new CaptureSession(new CaptureQueue(inversePosition, inverseNormal));
	}

	/** Finalizes a shared capture. A null result means the renderer emitted a command we cannot bake safely. */
	public static @Nullable CapturedGeometry finishSharedCapture(CaptureSession session) {
		return finishSharedCapture(session, false);
	}

	/** Finalizes an entity capture and optionally tessellates stable text commands once. */
	public static @Nullable CapturedGeometry finishSharedCapture(CaptureSession session, boolean bakeStableText) {
		CaptureQueue cq = session.queue;
		if (bakeStableText && !cq.unsupported && !cq.texts.isEmpty()) {
			cq.bakeTexts();
		}
		if (cq.unsupported || cq.totalVerts > MAX_VERTS_PER_BE) {
			return null;
		}
		List<BakedLayer> layers = new ArrayList<>(cq.byLayer.size());
		for (Map.Entry<RenderLayer, CaptureBuf> e : cq.byLayer.entrySet()) {
			CaptureBuf b = e.getValue();
			if (b.count > 0) {
				layers.add(b.freeze(e.getKey()));
			}
		}
		return new CapturedGeometry(layers, cq.texts, cq.totalVerts);
	}

	/** Replays shared immutable geometry with the current frame's light value. */
	public static void replayShared(CapturedGeometry geometry, MatrixStack matrices,
			OrderedRenderCommandQueue queue, int light) {
		int tint = com.moneyakshaders.client.EntityRenderTint.current();
		for (BakedLayer layer : geometry.layers) {
			queue.submitCustom(matrices, layer.layer, layer.command(light, tint));
		}
		for (CapturedText text : geometry.texts) {
			matrices.push();
			matrices.peek().getPositionMatrix().mul(text.localPosition);
			matrices.peek().getNormalMatrix().mul(text.localNormal);
			queue.getBatchingQueue(text.order).submitText(matrices, text.x, text.y, text.text,
					text.dropShadow, text.layerType, light, text.color, text.backgroundColor, text.outlineColor);
			matrices.pop();
		}
	}

	/** Emits cached quad positions into the existing model-shadow capture without rebuilding models. */
	public static void captureSharedShadow(CapturedGeometry geometry, MatrixStack matrices) {
		if (!com.moneyakshaders.client.EntityShadowCapture.shouldCaptureStaticGeometry()) {
			return;
		}
		Matrix4f matrix = matrices.peek().getPositionMatrix();
		for (BakedLayer layer : geometry.layers) {
			com.moneyakshaders.client.EntityShadowCapture.captureStaticLayer(
					matrix, layer.fp, layer.count);
		}
	}

	public static final class CaptureSession {
		private final CaptureQueue queue;

		private CaptureSession(CaptureQueue queue) {
			this.queue = queue;
		}

		public OrderedRenderCommandQueue queue() {
			return this.queue;
		}
	}

	public static final class CapturedGeometry {
		private final List<BakedLayer> layers;
		private final List<CapturedText> texts;
		private final int vertexCount;

		private CapturedGeometry(List<BakedLayer> layers, List<CapturedText> texts, int vertexCount) {
			this.layers = List.copyOf(layers);
			this.texts = List.copyOf(texts);
			this.vertexCount = vertexCount;
		}

		public int layerCount() {
			return this.layers.size() + this.texts.size();
		}

		public int vertexCount() {
			return this.vertexCount;
		}
	}

	private record CapturedText(Matrix4f localPosition, Matrix3f localNormal, int order,
			float x, float y, OrderedText text, boolean dropShadow, TextRenderer.TextLayerType layerType,
			int color, int backgroundColor, int outlineColor) {
	}

	// ------------------------------------------------------------------ stored geometry

	private record Baked(@Nullable BlockEntityType<?> type, List<BakedLayer> layers) {
	}

	/** Immutable captured geometry for one render layer: interleaved vertex attributes. */
	private static final class BakedLayer {
		final RenderLayer layer;
		final float[] fp;   // per vertex: x,y,z, u,v, nx,ny,nz  (8 floats)
		final int[] ci;     // per vertex: colorARGB, overlay    (2 ints)
		final int count;
		private int cachedCommandLight = Integer.MIN_VALUE;
		private int cachedCommandTint = Integer.MIN_VALUE;
		private ReplayCommand cachedCommand;

		BakedLayer(RenderLayer layer, float[] fp, int[] ci, int count) {
			this.layer = layer;
			this.fp = fp;
			this.ci = ci;
			this.count = count;
		}

		ReplayCommand command(int light, int tint) {
			if (cachedCommand == null || cachedCommandLight != light || cachedCommandTint != tint) {
				cachedCommandLight = light;
				cachedCommandTint = tint;
				cachedCommand = new ReplayCommand(this, light, tint);
			}
			return cachedCommand;
		}
	}

	/** The {@link OrderedRenderCommandQueue.Custom} that re-emits one baked layer at the current light. */
	private static final ThreadLocal<Vector3f> REPLAY_NORMAL = ThreadLocal.withInitial(Vector3f::new);

	private record ReplayCommand(BakedLayer bl, int light, int tint)
			implements com.moneyakshaders.client.EntityRenderTint.PreTintedCustom {
		@Override
		public void render(MatrixStack.Entry matricesEntry, VertexConsumer vc) {
			Matrix4f mat = matricesEntry.getPositionMatrix();
			float[] fp = bl.fp;
			int[] ci = bl.ci;
			// Static-entity pose/display transforms were baked relative to the entity root during capture.
			// In the normal world pass the remaining matrix is therefore only the camera-relative root
			// translation. Avoid two full matrix transforms for every one of the ~1-2M replayed vertices/s.
			org.joml.Matrix3f normalMatrix = matricesEntry.getNormalMatrix();
			boolean translationOnly = approximatelyIdentityLinear(mat) && approximatelyIdentity(normalMatrix);
			if (translationOnly) {
				if (com.moneyakshaders.MoneyakShadersConfig.get().debugStats) {
					com.moneyakshaders.client.DebugStats.staticFastReplayVertices.addAndGet(bl.count);
				}
				float ox = mat.m30(), oy = mat.m31(), oz = mat.m32();
				if (vc instanceof com.moneyakshaders.client.BufferBuilderBulkAccess bulk
						&& bulk.moneyakshaders$appendTranslated(
								fp, ci, bl.count, ox, oy, oz, light, tint)) {
					if (com.moneyakshaders.MoneyakShadersConfig.get().debugStats) {
						com.moneyakshaders.client.DebugStats.staticBulkReplayVertices.addAndGet(bl.count);
					}
					return;
				}
				for (int i = 0, f = 0, c = 0; i < bl.count; i++, f += 8, c += 2) {
					int color = tint == -1 ? ci[c]
							: com.moneyakshaders.client.EntityRenderTint.multiply(ci[c], tint);
					vc.vertex(fp[f] + ox, fp[f + 1] + oy, fp[f + 2] + oz,
							color, fp[f + 3], fp[f + 4], ci[c + 1], light,
							fp[f + 5], fp[f + 6], fp[f + 7]);
				}
				return;
			}
			if (com.moneyakshaders.MoneyakShadersConfig.get().debugStats) {
				com.moneyakshaders.client.DebugStats.staticMatrixReplayVertices.addAndGet(bl.count);
			}
			Vector3f normal = REPLAY_NORMAL.get();
			for (int i = 0, f = 0, c = 0; i < bl.count; i++, f += 8, c += 2) {
				float x = fp[f], y = fp[f + 1], z = fp[f + 2];
				float tx = mat.m00() * x + mat.m10() * y + mat.m20() * z + mat.m30();
				float ty = mat.m01() * x + mat.m11() * y + mat.m21() * z + mat.m31();
				float tz = mat.m02() * x + mat.m12() * y + mat.m22() * z + mat.m32();
				matricesEntry.transformNormal(fp[f + 5], fp[f + 6], fp[f + 7], normal);
				int color = tint == -1 ? ci[c]
						: com.moneyakshaders.client.EntityRenderTint.multiply(ci[c], tint);
				vc.vertex(tx, ty, tz, color, fp[f + 3], fp[f + 4], ci[c + 1], light,
						normal.x, normal.y, normal.z);
			}
		}

		private static boolean approximatelyIdentityLinear(Matrix4f m) {
			return nearOne(m.m00()) && nearZero(m.m01()) && nearZero(m.m02())
					&& nearZero(m.m10()) && nearOne(m.m11()) && nearZero(m.m12())
					&& nearZero(m.m20()) && nearZero(m.m21()) && nearOne(m.m22());
		}

		private static boolean approximatelyIdentity(org.joml.Matrix3f m) {
			return nearOne(m.m00()) && nearZero(m.m01()) && nearZero(m.m02())
					&& nearZero(m.m10()) && nearOne(m.m11()) && nearZero(m.m12())
					&& nearZero(m.m20()) && nearZero(m.m21()) && nearOne(m.m22());
		}

		private static boolean nearZero(float value) { return Math.abs(value) <= 1.0e-6f; }
		private static boolean nearOne(float value) { return Math.abs(value - 1.0f) <= 1.0e-6f; }
	}

	// ------------------------------------------------------------------ capture buffers

	/** Growable interleaved buffer that a {@link CaptureConsumer} appends into. */
	private static final class CaptureBuf {
		float[] fp = new float[8 * 64];
		int[] ci = new int[2 * 64];
		int count;

		void push(float x, float y, float z, float u, float v, float nx, float ny, float nz, int color, int overlay) {
			if ((count + 1) * 8 > fp.length) {
				fp = java.util.Arrays.copyOf(fp, fp.length * 2);
				ci = java.util.Arrays.copyOf(ci, ci.length * 2);
			}
			int f = count * 8, c = count * 2;
			fp[f] = x; fp[f + 1] = y; fp[f + 2] = z;
			fp[f + 3] = u; fp[f + 4] = v;
			fp[f + 5] = nx; fp[f + 6] = ny; fp[f + 7] = nz;
			ci[c] = color; ci[c + 1] = overlay;
			count++;
		}

		BakedLayer freeze(RenderLayer layer) {
			return new BakedLayer(layer, java.util.Arrays.copyOf(fp, count * 8), java.util.Arrays.copyOf(ci, count * 2), count);
		}
	}

	/**
	 * A {@link VertexConsumer} that records vertices (subtracting the outer camera translation) into a
	 * {@link CaptureBuf}. Follows MC's builder chain: {@code vertex(x,y,z)} opens a vertex, the
	 * attribute setters fill it, the next {@code vertex} (or {@link #flush()}) commits it.
	 */
	private static final class CaptureConsumer implements VertexConsumer {
		private final CaptureBuf buf;
		private final Matrix4f inversePosition;
		private final Matrix3f inverseNormal;
		private final Vector3f transformed = new Vector3f();
		private boolean open;
		private float x, y, z, u, v, nx, ny, nz;
		private int color = 0xFFFFFFFF, overlay = net.minecraft.client.render.OverlayTexture.DEFAULT_UV;

		CaptureConsumer(CaptureBuf buf, Matrix4f inversePosition, Matrix3f inverseNormal) {
			this.buf = buf;
			this.inversePosition = inversePosition;
			this.inverseNormal = inverseNormal;
		}

		void flush() {
			if (open) {
				buf.push(x, y, z, u, v, nx, ny, nz, color, overlay);
				open = false;
				// reset per-vertex defaults for the next vertex
				color = 0xFFFFFFFF;
				overlay = net.minecraft.client.render.OverlayTexture.DEFAULT_UV;
				u = v = nx = ny = 0f;
				nz = 1f;
			}
		}

		@Override
		public VertexConsumer vertex(float px, float py, float pz) {
			flush();
			open = true;
			inversePosition.transformPosition(px, py, pz, transformed);
			x = transformed.x;
			y = transformed.y;
			z = transformed.z;
			return this;
		}

		@Override
		public VertexConsumer color(int red, int green, int blue, int alpha) {
			color = (alpha << 24) | (red << 16) | (green << 8) | blue;
			return this;
		}

		@Override
		public VertexConsumer color(int argb) {
			color = argb;
			return this;
		}

		@Override
		public VertexConsumer texture(float tu, float tv) {
			u = tu;
			v = tv;
			return this;
		}

		@Override
		public VertexConsumer overlay(int ou, int ov) {
			overlay = (ov << 16) | (ou & 0xFFFF);
			return this;
		}

		@Override
		public VertexConsumer light(int lu, int lv) {
			return this; // light is applied at replay from the live render state, never baked
		}

		@Override
		public VertexConsumer normal(float mx, float my, float mz) {
			inverseNormal.transform(mx, my, mz, transformed);
			nx = transformed.x;
			ny = transformed.y;
			nz = transformed.z;
			return this;
		}

		@Override
		public VertexConsumer lineWidth(float width) {
			return this;
		}
	}

	// ------------------------------------------------------------------ capture queue

	/**
	 * Records a block-entity renderer's model geometry per render layer. Any submit that isn't pure
	 * bakeable model geometry sets {@link #unsupported}, which makes the caller mark the position no-bake.
	 */
	private static final class CaptureQueue implements OrderedRenderCommandQueue {
		private final Matrix4f inversePosition;
		private final Matrix3f inverseNormal;
		final Map<RenderLayer, CaptureBuf> byLayer = new java.util.LinkedHashMap<>();
		final List<CapturedText> texts = new ArrayList<>();
		boolean unsupported;
		int totalVerts;
		int batchingOrder;

		CaptureQueue(Matrix4f inversePosition, Matrix3f inverseNormal) {
			this.inversePosition = inversePosition;
			this.inverseNormal = inverseNormal;
		}

		private CaptureConsumer consumerFor(RenderLayer layer) {
			CaptureBuf b = byLayer.computeIfAbsent(layer, k -> new CaptureBuf());
			return new CaptureConsumer(b, inversePosition, inverseNormal);
		}

		/** Converts immutable TextDisplay commands into the same local-space vertex cache as models. */
		private void bakeTexts() {
			int before = byLayer.values().stream().mapToInt(buffer -> buffer.count).sum();
			Map<RenderLayer, CaptureConsumer> consumers = new java.util.IdentityHashMap<>();
			Matrix4f identityPosition = new Matrix4f();
			Matrix3f identityNormal = new Matrix3f();
			VertexConsumerProvider provider = layer -> consumers.computeIfAbsent(layer, key -> {
				CaptureBuf buffer = byLayer.computeIfAbsent(key, ignored -> new CaptureBuf());
				return new CaptureConsumer(buffer, identityPosition, identityNormal);
			});
			TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
			for (CapturedText text : texts) {
				if (text.outlineColor == 0) {
					renderer.draw(text.text, text.x, text.y, text.color, text.dropShadow,
							text.localPosition, provider, text.layerType, text.backgroundColor, 0);
				} else {
					renderer.drawWithOutline(text.text, text.x, text.y, text.color, text.outlineColor,
							text.localPosition, provider, 0);
				}
			}
			consumers.values().forEach(CaptureConsumer::flush);
			int after = byLayer.values().stream().mapToInt(buffer -> buffer.count).sum();
			totalVerts += after - before;
			texts.clear();
		}

		@Override
		public RenderCommandQueue getBatchingQueue(int order) {
			this.batchingOrder = order;
			return this;
		}

		@Override
		public <S> void submitModel(Model<? super S> model, S state, MatrixStack matrices, RenderLayer renderLayer,
				int light, int overlay, int tintedColor, @Nullable Sprite sprite, int outlineColor,
				ModelCommandRenderer.@Nullable CrumblingOverlayCommand crumblingOverlay) {
			CaptureBuf before = byLayer.get(renderLayer);
			int had = before == null ? 0 : before.count;
			CaptureConsumer cc = consumerFor(renderLayer);
			VertexConsumer sink = sprite == null ? cc : sprite.getTextureSpecificVertexConsumer(cc);
			model.setAngles(state);
			model.render(matrices, sink, light, overlay, tintedColor);
			cc.flush();
			totalVerts += byLayer.get(renderLayer).count - had;
		}

		@Override
		public void submitModelPart(ModelPart part, MatrixStack matrices, RenderLayer renderLayer, int light, int overlay,
				@Nullable Sprite sprite, boolean sheeted, boolean hasGlint, int tintedColor,
				ModelCommandRenderer.@Nullable CrumblingOverlayCommand crumblingOverlay, int i) {
			CaptureBuf before = byLayer.get(renderLayer);
			int had = before == null ? 0 : before.count;
			CaptureConsumer cc = consumerFor(renderLayer);
			VertexConsumer sink = sprite == null ? cc : sprite.getTextureSpecificVertexConsumer(cc);
			part.render(matrices, sink, light, overlay, tintedColor);
			cc.flush();
			totalVerts += byLayer.get(renderLayer).count - had;
		}

		@Override
		public void submitCustom(MatrixStack matrices, RenderLayer renderLayer, OrderedRenderCommandQueue.Custom customRenderer) {
			CaptureBuf before = byLayer.get(renderLayer);
			int had = before == null ? 0 : before.count;
			CaptureConsumer cc = consumerFor(renderLayer);
			customRenderer.render(matrices.peek(), cc);
			cc.flush();
			totalVerts += byLayer.get(renderLayer).count - had;
		}

		// --- geometry we do not bake: mark unsupported so the position falls back to vanilla ---
		@Override
		public void submitBlock(MatrixStack matrices, BlockState state, int light, int overlay, int outlineColor) {
			if (outlineColor != 0) {
				unsupported = true;
				return;
			}
			if (state.getRenderType() == BlockRenderType.INVISIBLE) {
				return;
			}
			MinecraftClient client = MinecraftClient.getInstance();
			int color = client.getBlockColors().getColor(state, null, null, 0);
			float r = (color >> 16 & 0xFF) / 255.0F;
			float g = (color >> 8 & 0xFF) / 255.0F;
			float b = (color & 0xFF) / 255.0F;
			submitBlockStateModel(matrices, BlockRenderLayers.getEntityBlockLayer(state),
					client.getBlockRenderManager().getModel(state), r, g, b, light, overlay, 0);
		}

		@Override
		public void submitMovingBlock(MatrixStack matrices, MovingBlockRenderState state) {
			unsupported = true;
		}

		@Override
		public void submitBlockStateModel(MatrixStack matrices, RenderLayer renderLayer, BlockStateModel model,
				float r, float g, float b, int light, int overlay, int outlineColor) {
			if (outlineColor != 0) {
				unsupported = true;
				return;
			}
			CaptureBuf before = byLayer.get(renderLayer);
			int had = before == null ? 0 : before.count;
			CaptureConsumer cc = consumerFor(renderLayer);
			BlockModelRenderer.render(matrices.peek(), cc, model, r, g, b, light, overlay);
			cc.flush();
			totalVerts += byLayer.get(renderLayer).count - had;
		}

		@Override
		public void submitItem(MatrixStack matrices, ItemDisplayContext displayContext, int light, int overlay,
				int outlineColors, int[] tintLayers, List<BakedQuad> quads, RenderLayer renderLayer,
				ItemRenderState.Glint glintType) {
			if (outlineColors != 0 || glintType != ItemRenderState.Glint.NONE) {
				unsupported = true;
				return;
			}
			CaptureBuf before = byLayer.get(renderLayer);
			int had = before == null ? 0 : before.count;
			CaptureConsumer cc = consumerFor(renderLayer);
			for (BakedQuad quad : quads) {
				float a = 1.0F, r = 1.0F, g = 1.0F, b = 1.0F;
				if (quad.hasTint()) {
					int index = quad.tintIndex();
					int tint = index >= 0 && index < tintLayers.length ? tintLayers[index] : -1;
					a = ColorHelper.getAlpha(tint) / 255.0F;
					r = ColorHelper.getRed(tint) / 255.0F;
					g = ColorHelper.getGreen(tint) / 255.0F;
					b = ColorHelper.getBlue(tint) / 255.0F;
				}
				cc.quad(matrices.peek(), quad, r, g, b, a, light, overlay);
			}
			cc.flush();
			totalVerts += byLayer.get(renderLayer).count - had;
		}

		@Override
		public void submitCustom(OrderedRenderCommandQueue.LayeredCustom customRenderer) {
			unsupported = true;
		}

		@Override
		public void submitText(MatrixStack matrices, float x, float y, OrderedText text, boolean dropShadow,
				TextRenderer.TextLayerType layerType, int light, int color, int backgroundColor, int outlineColor) {
			Matrix4f position = new Matrix4f(inversePosition).mul(matrices.peek().getPositionMatrix());
			Matrix3f normal = new Matrix3f(inverseNormal).mul(matrices.peek().getNormalMatrix());
			texts.add(new CapturedText(position, normal, batchingOrder,
					x, y, text, dropShadow, layerType, color, backgroundColor, outlineColor));
		}

		@Override
		public void submitLabel(MatrixStack matrices, @Nullable Vec3d nameLabelPos, int y, Text label, boolean notSneaking,
				int light, double squaredDistanceToCamera, CameraRenderState cameraState) {
			unsupported = true;
		}

		@Override
		public void submitFire(MatrixStack matrices, EntityRenderState renderState, Quaternionf rotation) {
			unsupported = true;
		}

		@Override
		public void submitLeash(MatrixStack matrices, EntityRenderState.LeashData leashData) {
			unsupported = true;
		}

		@Override
		public void submitShadowPieces(MatrixStack matrices, float shadowRadius, List<EntityRenderState.ShadowPiece> shadowPieces) {
			// Blob shadow — cosmetic, not part of the model. Ignore (do NOT mark unsupported).
		}
	}
}

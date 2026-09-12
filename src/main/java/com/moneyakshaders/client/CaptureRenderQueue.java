package com.moneyakshaders.client;

import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.MovingBlockRenderState;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.command.RenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * A throwaway {@link OrderedRenderCommandQueue} that renders submitted models/parts <em>immediately</em>
 * into a no-op {@link VertexConsumer} instead of batching + drawing to the screen. While
 * {@link EntityShadowCapture} is active, the per-cuboid mixin captures the geometry — letting us
 * pose-and-capture an entity model (e.g. the first-person player, whose body MC never draws) for
 * shadows without any visible GL output.
 */
public final class CaptureRenderQueue implements OrderedRenderCommandQueue {
	private static final VertexConsumer NOOP = new NoopVertexConsumer();
	private static final CaptureRenderQueue INSTANCE = new CaptureRenderQueue();
	private static final CameraRenderState CAMERA_STATE = new CameraRenderState();

	/**
	 * Pose-and-capture the local player's own animated model into {@link EntityShadowCapture}.
	 *
	 * <p>Needed only in FIRST-PERSON: MC never renders the player's own body there, so the
	 * cuboid-capture mixin never fires for the player and they cast no self-shadow. In third-person
	 * MC renders the body normally and it is captured automatically — so this is a no-op caller side
	 * (guarded by the perspective check at the call site).
	 *
	 * <p>Must be called while {@link EntityShadowCapture#active} with the SAME camera origin passed
	 * to {@link EntityShadowCapture#begin}, so the captured cuboids land in the same world space as
	 * the other entities' geometry. Render-thread only.
	 */
	public static void captureSelf(MinecraftClient client, double camX, double camY, double camZ) {
		var player = client.player;
		if (player == null) {
			return;
		}
		EntityRenderManager mgr = client.getEntityRenderDispatcher();
		float td = client.getRenderTickCounter().getTickProgress(false);
		try {
			EntityRenderState state = mgr.getAndUpdateRenderState(player, td);
			Vec3d p = player.getLerpedPos(td); // interpolated render position, matches MC's entity pass
			// offset = entity world pos − camera → pose matrix yields camera-relative coords;
			// EntityShadowCapture.captureCuboid adds the camera origin back to reach world space.
			mgr.render(state, CAMERA_STATE, p.x - camX, p.y - camY, p.z - camZ, new MatrixStack(), INSTANCE);
		} catch (Throwable ignored) {
			// a bad pose must never break the frame — just skip the self-shadow this frame
		}
	}

	@Override
	public RenderCommandQueue getBatchingQueue(int order) {
		return this;
	}

	@Override
	public <S> void submitModel(Model<? super S> model, S state, MatrixStack matrices, RenderLayer renderLayer,
			int light, int overlay, int tintedColor, @Nullable Sprite sprite, int outlineColor,
			ModelCommandRenderer.@Nullable CrumblingOverlayCommand crumblingOverlay) {
		model.setAngles(state);
		model.render(matrices, NOOP, light, overlay, tintedColor);
	}

	@Override
	public void submitModelPart(ModelPart part, MatrixStack matrices, RenderLayer renderLayer, int light, int overlay,
			@Nullable Sprite sprite, boolean sheeted, boolean hasGlint, int tintedColor,
			ModelCommandRenderer.@Nullable CrumblingOverlayCommand crumblingOverlay, int i) {
		part.render(matrices, NOOP, light, overlay, tintedColor);
	}

	// --- everything else is irrelevant to shadow geometry: no-op ---
	@Override
	public void submitShadowPieces(MatrixStack matrices, float shadowRadius, List<EntityRenderState.ShadowPiece> shadowPieces) {
	}

	@Override
	public void submitLabel(MatrixStack matrices, @Nullable Vec3d nameLabelPos, int y, Text label, boolean notSneaking,
			int light, double squaredDistanceToCamera, CameraRenderState cameraState) {
	}

	@Override
	public void submitText(MatrixStack matrices, float x, float y, OrderedText text, boolean dropShadow,
			TextRenderer.TextLayerType layerType, int light, int color, int backgroundColor, int outlineColor) {
	}

	@Override
	public void submitFire(MatrixStack matrices, EntityRenderState renderState, Quaternionf rotation) {
	}

	@Override
	public void submitLeash(MatrixStack matrices, EntityRenderState.LeashData leashData) {
	}

	@Override
	public void submitBlock(MatrixStack matrices, BlockState state, int light, int overlay, int outlineColor) {
	}

	@Override
	public void submitMovingBlock(MatrixStack matrices, MovingBlockRenderState state) {
	}

	@Override
	public void submitBlockStateModel(MatrixStack matrices, RenderLayer renderLayer, BlockStateModel model,
			float r, float g, float b, int light, int overlay, int outlineColor) {
	}

	@Override
	public void submitItem(MatrixStack matrices, ItemDisplayContext displayContext, int light, int overlay,
			int outlineColors, int[] tintLayers, List<BakedQuad> quads, RenderLayer renderLayer,
			ItemRenderState.Glint glintType) {
		// First-person held item: MC doesn't render it during the entity pass, so the
		// ItemQuadCaptureMixin never fires for it — capture the quads here so it casts a shadow too.
		var m = matrices.peek().getPositionMatrix();
		for (int i = 0; i < quads.size(); i++) {
			EntityShadowCapture.captureItemQuad(m, quads.get(i));
		}
	}

	@Override
	public void submitCustom(MatrixStack matrices, RenderLayer renderLayer, OrderedRenderCommandQueue.Custom customRenderer) {
	}

	@Override
	public void submitCustom(OrderedRenderCommandQueue.LayeredCustom customRenderer) {
	}

	/** Discards every vertex; only present so model.render() runs (and the cuboid mixin fires). */
	private static final class NoopVertexConsumer implements VertexConsumer {
		@Override public VertexConsumer vertex(float x, float y, float z) { return this; }
		@Override public VertexConsumer color(int red, int green, int blue, int alpha) { return this; }
		@Override public VertexConsumer color(int argb) { return this; }
		@Override public VertexConsumer texture(float u, float v) { return this; }
		@Override public VertexConsumer overlay(int u, int v) { return this; }
		@Override public VertexConsumer light(int u, int v) { return this; }
		@Override public VertexConsumer normal(float x, float y, float z) { return this; }
		@Override public VertexConsumer lineWidth(float width) { return this; }
	}
}

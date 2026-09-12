package com.moneyakshaders.mixin.client;

import java.nio.ByteOrder;

import org.lwjgl.system.MemoryUtil;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.moneyakshaders.client.BufferBuilderBulkAccess;
import com.moneyakshaders.client.EntityRenderTint;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.MathHelper;

/** Contiguous native-buffer append for translation-only cached entity geometry. */
@Mixin(BufferBuilder.class)
public abstract class BufferBuilderBulkMixin implements BufferBuilderBulkAccess {
	private static final boolean OPTIMIZEDLOADING_LITTLE_ENDIAN =
			ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

	@Shadow @Final private BufferAllocator allocator;
	@Shadow private long vertexPointer;
	@Shadow private int vertexCount;
	@Shadow @Final private VertexFormat.DrawMode drawMode;
	@Shadow @Final private int vertexSizeByte;
	@Shadow @Final private int requiredMask;
	@Shadow @Final private int[] offsetsByElementId;
	@Shadow private int currentMask;
	@Shadow private boolean building;

	@Override
	public boolean moneyakshaders$appendTranslated(float[] fp, int[] ci, int count,
			float offsetX, float offsetY, float offsetZ, int light, int tint) {
		if (!building || currentMask != 0 || count <= 0
				|| vertexCount > 0xFFFFFF - count || drawMode == VertexFormat.DrawMode.LINES) {
			return false;
		}
		int supportedMask = VertexFormatElement.POSITION.mask()
				| VertexFormatElement.COLOR.mask()
				| VertexFormatElement.UV0.mask()
				| VertexFormatElement.UV1.mask()
				| VertexFormatElement.UV2.mask()
				| VertexFormatElement.NORMAL.mask();
		if ((requiredMask & ~supportedMask) != 0
				|| fp.length < (long) count * 8 || ci.length < (long) count * 2) {
			return false;
		}
		int positionOffset = elementOffset(VertexFormatElement.POSITION);
		if (positionOffset < 0) {
			return false;
		}
		int colorOffset = requiredOffset(VertexFormatElement.COLOR);
		int uvOffset = requiredOffset(VertexFormatElement.UV0);
		int overlayOffset = requiredOffset(VertexFormatElement.UV1);
		int lightOffset = requiredOffset(VertexFormatElement.UV2);
		int normalOffset = requiredOffset(VertexFormatElement.NORMAL);

		long base = allocator.allocate(Math.multiplyExact(count, vertexSizeByte));
		long pointer = base;
		for (int i = 0, f = 0, c = 0; i < count; i++, f += 8, c += 2, pointer += vertexSizeByte) {
			long positionPointer = pointer + positionOffset;
			MemoryUtil.memPutFloat(positionPointer, fp[f] + offsetX);
			MemoryUtil.memPutFloat(positionPointer + 4L, fp[f + 1] + offsetY);
			MemoryUtil.memPutFloat(positionPointer + 8L, fp[f + 2] + offsetZ);
			if (colorOffset >= 0) {
				int color = tint == -1 ? ci[c] : EntityRenderTint.multiply(ci[c], tint);
				int abgr = ColorHelper.toAbgr(color);
				MemoryUtil.memPutInt(pointer + colorOffset,
						OPTIMIZEDLOADING_LITTLE_ENDIAN ? abgr : Integer.reverseBytes(abgr));
			}
			if (uvOffset >= 0) {
				MemoryUtil.memPutFloat(pointer + uvOffset, fp[f + 3]);
				MemoryUtil.memPutFloat(pointer + uvOffset + 4L, fp[f + 4]);
			}
			if (overlayOffset >= 0) {
				putPackedInt(pointer + overlayOffset, ci[c + 1]);
			}
			if (lightOffset >= 0) {
				putPackedInt(pointer + lightOffset, light);
			}
			if (normalOffset >= 0) {
				MemoryUtil.memPutByte(pointer + normalOffset, normalByte(fp[f + 5]));
				MemoryUtil.memPutByte(pointer + normalOffset + 1L, normalByte(fp[f + 6]));
				MemoryUtil.memPutByte(pointer + normalOffset + 2L, normalByte(fp[f + 7]));
			}
		}
		vertexCount += count;
		vertexPointer = base + (long) (count - 1) * vertexSizeByte;
		return true;
	}

	@Override
	public boolean moneyakshaders$appendTransformed(float[] fp, int count, Matrix4f position, Matrix3f normal,
			int light, int overlay, int tint) {
		if (!building || currentMask != 0 || count <= 0
				|| vertexCount > 0xFFFFFF - count || drawMode == VertexFormat.DrawMode.LINES
				|| fp.length < (long) count * 8) {
			return false;
		}
		int supportedMask = VertexFormatElement.POSITION.mask()
				| VertexFormatElement.COLOR.mask()
				| VertexFormatElement.UV0.mask()
				| VertexFormatElement.UV1.mask()
				| VertexFormatElement.UV2.mask()
				| VertexFormatElement.NORMAL.mask();
		if ((requiredMask & ~supportedMask) != 0) {
			return false;
		}
		int positionOffset = elementOffset(VertexFormatElement.POSITION);
		if (positionOffset < 0) {
			return false;
		}
		int colorOffset = requiredOffset(VertexFormatElement.COLOR);
		int uvOffset = requiredOffset(VertexFormatElement.UV0);
		int overlayOffset = requiredOffset(VertexFormatElement.UV1);
		int lightOffset = requiredOffset(VertexFormatElement.UV2);
		int normalOffset = requiredOffset(VertexFormatElement.NORMAL);
		int abgr = ColorHelper.toAbgr(tint);
		int nativeColor = OPTIMIZEDLOADING_LITTLE_ENDIAN ? abgr : Integer.reverseBytes(abgr);

		long base = allocator.allocate(Math.multiplyExact(count, vertexSizeByte));
		long pointer = base;
		for (int i = 0, f = 0; i < count; i++, f += 8, pointer += vertexSizeByte) {
			float x = fp[f], y = fp[f + 1], z = fp[f + 2];
			long pp = pointer + positionOffset;
			MemoryUtil.memPutFloat(pp, position.m00() * x + position.m10() * y + position.m20() * z + position.m30());
			MemoryUtil.memPutFloat(pp + 4L, position.m01() * x + position.m11() * y + position.m21() * z + position.m31());
			MemoryUtil.memPutFloat(pp + 8L, position.m02() * x + position.m12() * y + position.m22() * z + position.m32());
			if (colorOffset >= 0) {
				MemoryUtil.memPutInt(pointer + colorOffset, nativeColor);
			}
			if (uvOffset >= 0) {
				MemoryUtil.memPutFloat(pointer + uvOffset, fp[f + 3]);
				MemoryUtil.memPutFloat(pointer + uvOffset + 4L, fp[f + 4]);
			}
			if (overlayOffset >= 0) {
				putPackedInt(pointer + overlayOffset, overlay);
			}
			if (lightOffset >= 0) {
				putPackedInt(pointer + lightOffset, light);
			}
			if (normalOffset >= 0) {
				float nx = normal.m00() * fp[f + 5] + normal.m10() * fp[f + 6] + normal.m20() * fp[f + 7];
				float ny = normal.m01() * fp[f + 5] + normal.m11() * fp[f + 6] + normal.m21() * fp[f + 7];
				float nz = normal.m02() * fp[f + 5] + normal.m12() * fp[f + 6] + normal.m22() * fp[f + 7];
				float lenSq = nx * nx + ny * ny + nz * nz;
				if (lenSq > 1.0e-12f && Math.abs(lenSq - 1.0f) > 1.0e-5f) {
					float invLen = MathHelper.inverseSqrt(lenSq);
					nx *= invLen; ny *= invLen; nz *= invLen;
				}
				MemoryUtil.memPutByte(pointer + normalOffset, normalByte(nx));
				MemoryUtil.memPutByte(pointer + normalOffset + 1L, normalByte(ny));
				MemoryUtil.memPutByte(pointer + normalOffset + 2L, normalByte(nz));
			}
		}
		vertexCount += count;
		vertexPointer = base + (long) (count - 1) * vertexSizeByte;
		return true;
	}

	private int requiredOffset(VertexFormatElement element) {
		return (requiredMask & element.mask()) != 0 ? elementOffset(element) : -1;
	}

	private int elementOffset(VertexFormatElement element) {
		int id = element.id();
		return id >= 0 && id < offsetsByElementId.length ? offsetsByElementId[id] : -1;
	}

	private static void putPackedInt(long pointer, int value) {
		if (OPTIMIZEDLOADING_LITTLE_ENDIAN) {
			MemoryUtil.memPutInt(pointer, value);
		} else {
			// Match BufferBuilder.putInt: packed UV/light values are two logical shorts, not
			// a scalar int whose complete byte order should be reversed.
			MemoryUtil.memPutShort(pointer, (short) (value & 0xFFFF));
			MemoryUtil.memPutShort(pointer + 2L, (short) ((value >>> 16) & 0xFFFF));
		}
	}

	private static byte normalByte(float value) {
		return (byte) (((int) (MathHelper.clamp(value, -1.0F, 1.0F) * 127.0F)) & 0xFF);
	}
}

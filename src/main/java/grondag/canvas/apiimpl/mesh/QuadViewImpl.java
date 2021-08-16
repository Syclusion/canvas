/*
 *  Copyright 2019, 2020 grondag
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not
 *  use this file except in compliance with the License.  You may obtain a copy
 *  of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 *  License for the specific language governing permissions and limitations under
 *  the License.
 */

package grondag.canvas.apiimpl.mesh;

import static grondag.canvas.apiimpl.mesh.MeshEncodingHelper.BASE_QUAD_STRIDE;
import static grondag.canvas.apiimpl.mesh.MeshEncodingHelper.BASE_VERTEX_STRIDE;
import static grondag.canvas.apiimpl.mesh.MeshEncodingHelper.FIRST_VERTEX_COLOR;
import static grondag.canvas.apiimpl.mesh.MeshEncodingHelper.FIRST_VERTEX_LIGHTMAP;
import static grondag.canvas.apiimpl.mesh.MeshEncodingHelper.FIRST_VERTEX_NORMAL;
import static grondag.canvas.apiimpl.mesh.MeshEncodingHelper.FIRST_VERTEX_X;
import static grondag.canvas.apiimpl.mesh.MeshEncodingHelper.FIRST_VERTEX_Y;
import static grondag.canvas.apiimpl.mesh.MeshEncodingHelper.FIRST_VERTEX_Z;
import static grondag.canvas.apiimpl.mesh.MeshEncodingHelper.HEADER_BITS;
import static grondag.canvas.apiimpl.mesh.MeshEncodingHelper.HEADER_COLOR_INDEX;
import static grondag.canvas.apiimpl.mesh.MeshEncodingHelper.HEADER_MATERIAL;
import static grondag.canvas.apiimpl.mesh.MeshEncodingHelper.HEADER_SPRITE;
import static grondag.canvas.apiimpl.mesh.MeshEncodingHelper.HEADER_STRIDE;
import static grondag.canvas.apiimpl.mesh.MeshEncodingHelper.HEADER_TAG;
import static grondag.canvas.apiimpl.mesh.MeshEncodingHelper.UV_EXTRA_PRECISION;
import static grondag.canvas.apiimpl.mesh.MeshEncodingHelper.UV_PRECISE_TO_FLOAT_CONVERSION;
import static grondag.canvas.apiimpl.mesh.MeshEncodingHelper.UV_ROUNDING_BIT;
import static grondag.canvas.apiimpl.mesh.MeshEncodingHelper.VERTEX_START;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3f;

import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.model.ModelHelper;

import grondag.canvas.apiimpl.util.GeometryHelper;
import grondag.canvas.apiimpl.util.NormalHelper;
import grondag.canvas.material.state.RenderMaterialImpl;
import grondag.canvas.mixinterface.Matrix4fExt;
import grondag.frex.api.mesh.QuadView;

/**
 * Base class for all quads / quad makers. Handles the ugly bits
 * of maintaining and encoding the quad state.
 */
public class QuadViewImpl implements QuadView {
	protected final Vec3f faceNormal = new Vec3f();
	protected int nominalFaceId = ModelHelper.NULL_FACE_ID;
	protected boolean isGeometryInvalid = true;
	protected int packedFaceNormal = -1;

	/**
	 * Flag true when sprite is assumed to be interpolated and need normalization.
	 */
	protected boolean isSpriteInterpolated = false;

	/**
	 * Size and where it comes from will vary in subtypes. But in all cases quad is fully encoded to array.
	 */
	protected int[] data;

	/**
	 * Beginning of the quad. Also the header index.
	 */
	protected int baseIndex = 0;

	/**
	 * Use when subtype is "attached" to a pre-existing array.
	 * Sets data reference and index and decodes state from array.
	 */
	final void load(int[] data, int baseIndex) {
		this.data = data;
		this.baseIndex = baseIndex;
		load();
	}

	/**
	 * Copies data from source, leaves baseIndex unchanged. For mesh iteration.
	 */
	public final void copyAndLoad(int[] source, int sourceIndex, int stride) {
		System.arraycopy(source, sourceIndex, data, baseIndex, stride);
		load();
	}

	/**
	 * Like {@link #load(int[], int)} but assumes array and index already set.
	 * Only does the decoding part.
	 */
	public final void load() {
		isGeometryInvalid = false;
		nominalFaceId = lightFaceId();
		// face normal isn't encoded
		NormalHelper.computeFaceNormal(faceNormal, this);
		packedFaceNormal = -1;
	}

	/**
	 * Reference to underlying array. Use with caution. Meant for fast renderer access
	 */
	public int[] data() {
		return data;
	}

	public int normalFlags() {
		return MeshEncodingHelper.normalFlags(data[baseIndex + HEADER_BITS]);
	}

	/**
	 * True if any vertex normal has been set.
	 */
	public boolean hasVertexNormals() {
		return normalFlags() != 0;
	}

	/**
	 * Index after header where vertex data starts (first 28 will be vanilla format.
	 */
	public int vertexStart() {
		return baseIndex + HEADER_STRIDE;
	}

	/**
	 * Length of encoded quad in array, including header.
	 */
	public final int stride() {
		return MeshEncodingHelper.stride();
	}

	/**
	 * gets flags used for lighting - lazily computed via {@link GeometryHelper#computeShapeFlags(QuadView)}.
	 */
	public int geometryFlags() {
		computeGeometry();
		return MeshEncodingHelper.geometryFlags(data[baseIndex + HEADER_BITS]);
	}

	protected void computeGeometry() {
		if (isGeometryInvalid) {
			isGeometryInvalid = false;

			NormalHelper.computeFaceNormal(faceNormal, this);
			packedFaceNormal = -1;

			final int headerIndex = baseIndex + HEADER_BITS;

			// depends on face normal
			// NB: important to save back to array because used by geometry helper
			data[headerIndex] = MeshEncodingHelper.lightFace(data[headerIndex], GeometryHelper.lightFaceId(this));

			// depends on light face
			data[baseIndex + HEADER_BITS] = MeshEncodingHelper.geometryFlags(data[headerIndex], GeometryHelper.computeShapeFlags(this));
		}
	}

	@Override
	public final void toVanilla(int[] target, int targetIndex) {
		System.arraycopy(data, baseIndex + VERTEX_START, target, targetIndex, BASE_QUAD_STRIDE);

		// Convert sprite data from fixed precision to float
		int index = targetIndex + 4;

		for (int i = 0; i < 4; ++i) {
			target[index] = Float.floatToRawIntBits(spriteU(i));
			target[index + 1] = Float.floatToRawIntBits(spriteV(i));
			index += 8;
		}
	}

	// PERF: cache this
	@Override
	public final RenderMaterialImpl material() {
		return RenderMaterialImpl.fromIndex(data[baseIndex + HEADER_MATERIAL]);
	}

	@Override
	public final int colorIndex() {
		return data[baseIndex + HEADER_COLOR_INDEX];
	}

	@Override
	public final int tag() {
		return data[baseIndex + HEADER_TAG];
	}

	public final int lightFaceId() {
		computeGeometry();
		return MeshEncodingHelper.lightFace(data[baseIndex + HEADER_BITS]);
	}

	@Override
	@Deprecated
	public final Direction lightFace() {
		return ModelHelper.faceFromIndex(lightFaceId());
	}

	public final int cullFaceId() {
		return MeshEncodingHelper.cullFace(data[baseIndex + HEADER_BITS]);
	}

	@Override
	@Deprecated
	public final Direction cullFace() {
		return ModelHelper.faceFromIndex(cullFaceId());
	}

	@Override
	public final Direction nominalFace() {
		return ModelHelper.faceFromIndex(nominalFaceId);
	}

	@Override
	public final Vec3f faceNormal() {
		computeGeometry();
		return faceNormal;
	}

	public int packedFaceNormal() {
		computeGeometry();
		int result = packedFaceNormal;

		if (result == -1) {
			result = NormalHelper.packNormal(faceNormal);
			packedFaceNormal = result;
		}

		return result;
	}

	@Override
	public void copyTo(MutableQuadView targetIn) {
		final grondag.frex.api.mesh.MutableQuadView target = (grondag.frex.api.mesh.MutableQuadView) targetIn;

		// forces geometry compute
		final int packedNormal = packedFaceNormal();

		final MutableQuadViewImpl quad = (MutableQuadViewImpl) target;

		final int len = Math.min(stride(), quad.stride());

		// copy everything except the material
		System.arraycopy(data, baseIndex + 1, quad.data, quad.baseIndex + 1, len - 1);
		quad.isSpriteInterpolated = isSpriteInterpolated;
		quad.faceNormal.set(faceNormal.getX(), faceNormal.getY(), faceNormal.getZ());
		quad.packedFaceNormal = packedNormal;
		quad.nominalFaceId = nominalFaceId;
		quad.isGeometryInvalid = false;
	}

	@Override
	public Vec3f copyPos(int vertexIndex, Vec3f target) {
		if (target == null) {
			target = new Vec3f();
		}

		final int index = baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_X;
		target.set(Float.intBitsToFloat(data[index]), Float.intBitsToFloat(data[index + 1]), Float.intBitsToFloat(data[index + 2]));
		return target;
	}

	@Override
	public float posByIndex(int vertexIndex, int coordinateIndex) {
		return Float.intBitsToFloat(data[baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_X + coordinateIndex]);
	}

	@Override
	public float x(int vertexIndex) {
		return Float.intBitsToFloat(data[baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_X]);
	}

	@Override
	public float y(int vertexIndex) {
		return Float.intBitsToFloat(data[baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_Y]);
	}

	@Override
	public float z(int vertexIndex) {
		return Float.intBitsToFloat(data[baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_Z]);
	}

	@Override
	public boolean hasNormal(int vertexIndex) {
		return (normalFlags() & (1 << vertexIndex)) != 0;
	}

	@Override
	public Vec3f copyNormal(int vertexIndex, Vec3f target) {
		if (hasNormal(vertexIndex)) {
			if (target == null) {
				target = new Vec3f();
			}

			final int normal = data[baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_NORMAL];
			target.set(NormalHelper.getPackedNormalComponent(normal, 0), NormalHelper.getPackedNormalComponent(normal, 1), NormalHelper.getPackedNormalComponent(normal, 2));
			return target;
		} else {
			return null;
		}
	}

	public int packedNormal(int vertexIndex) {
		return hasNormal(vertexIndex) ? data[baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_NORMAL] : packedFaceNormal();
	}

	@Override
	public float normalX(int vertexIndex) {
		return hasNormal(vertexIndex) ? NormalHelper.getPackedNormalComponent(data[baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_NORMAL], 0) : Float.NaN;
	}

	@Override
	public float normalY(int vertexIndex) {
		return hasNormal(vertexIndex) ? NormalHelper.getPackedNormalComponent(data[baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_NORMAL], 1) : Float.NaN;
	}

	@Override
	public float normalZ(int vertexIndex) {
		return hasNormal(vertexIndex) ? NormalHelper.getPackedNormalComponent(data[baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_NORMAL], 2) : Float.NaN;
	}

	@Override
	public int lightmap(int vertexIndex) {
		return data[baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_LIGHTMAP];
	}

	@Override
	public int vertexColor(int vertexIndex) {
		return data[baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_COLOR];
	}

	protected final boolean isSpriteNormalized() {
		return !isSpriteInterpolated;
	}

	protected final float spriteFloatU(int vertexIndex) {
		return data[baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_COLOR + 1] * UV_PRECISE_TO_FLOAT_CONVERSION;
	}

	protected final float spriteFloatV(int vertexIndex) {
		return data[baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_COLOR + 2] * UV_PRECISE_TO_FLOAT_CONVERSION;
	}

	@Override
	public float spriteU(int vertexIndex) {
		return isSpriteNormalized() && material().texture.isAtlas()
			? material().texture.atlasInfo().mapU(spriteId(), spriteFloatU(vertexIndex))
			: spriteFloatU(vertexIndex);
	}

	@Override
	public float spriteV(int vertexIndex) {
		return isSpriteNormalized() && material().texture.isAtlas()
			? material().texture.atlasInfo().mapV(spriteId(), spriteFloatV(vertexIndex))
			: spriteFloatV(vertexIndex);
	}

	/**
	 * Fixed precision value suitable for transformations.
	 */
	public int spritePreciseU(int vertexIndex) {
		assert isSpriteNormalized();
		return data[baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_COLOR + 1];
	}

	/**
	 * Fixed precision value suitable for transformations.
	 */
	public int spritePreciseV(int vertexIndex) {
		assert isSpriteNormalized();
		return data[baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_COLOR + 2];
	}

	/** Rounds precise fixed-precision sprite value to an unsigned short value. */
	public static int roundSpriteData(int rawVal) {
		return (rawVal + UV_ROUNDING_BIT) >> UV_EXTRA_PRECISION;
	}

	/**
	 * Rounded, unsigned short value suitable for vertex buffer.
	 */
	public int spriteBufferU(int vertexIndex) {
		assert isSpriteNormalized();
		return roundSpriteData(data[baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_COLOR + 1]);
	}

	/**
	 * Rounded, unsigned short value suitable for vertex buffer.
	 */
	public int spriteBufferV(int vertexIndex) {
		assert isSpriteNormalized();
		return roundSpriteData(data[baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_COLOR + 2]);
	}

	public int spriteId() {
		return data[baseIndex + HEADER_SPRITE];
	}

	public void transformAndAppendVertex(final int vertexIndex, final Matrix4fExt matrix, final VertexConsumer buff) {
		final int[] data = this.data;
		final int index = baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_X;
		final float x = Float.intBitsToFloat(data[index]);
		final float y = Float.intBitsToFloat(data[index + 1]);
		final float z = Float.intBitsToFloat(data[index + 2]);

		final float xOut = matrix.a00() * x + matrix.a01() * y + matrix.a02() * z + matrix.a03();
		final float yOut = matrix.a10() * x + matrix.a11() * y + matrix.a12() * z + matrix.a13();
		final float zOut = matrix.a20() * x + matrix.a21() * y + matrix.a22() * z + matrix.a23();

		buff.vertex(xOut, yOut, zOut);
	}

	public void transformAndAppendVertex(final int vertexIndex, final Matrix4fExt matrix, int[] target, int targetIndex) {
		final int[] data = this.data;
		final int index = baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_X;
		final float x = Float.intBitsToFloat(data[index]);
		final float y = Float.intBitsToFloat(data[index + 1]);
		final float z = Float.intBitsToFloat(data[index + 2]);

		final float xOut = matrix.a00() * x + matrix.a01() * y + matrix.a02() * z + matrix.a03();
		final float yOut = matrix.a10() * x + matrix.a11() * y + matrix.a12() * z + matrix.a13();
		final float zOut = matrix.a20() * x + matrix.a21() * y + matrix.a22() * z + matrix.a23();

		target[targetIndex] = Float.floatToRawIntBits(xOut);
		target[targetIndex + 1] = Float.floatToRawIntBits(yOut);
		target[targetIndex + 2] = Float.floatToRawIntBits(zOut);
	}

	/**
	 * The sector-relative origin should be added to the block component and
	 * is not zero-based to allow for vertices extending outside the sector.
	 *
	 * @param vertexIndex
	 * @param matrix
	 * @param target
	 * @param targetIndex
	 * @param sectorRelativeRegionOrigin 24-bit unsigned XYZ
	 */
	public void transformAndAppendRegionVertex(final int vertexIndex, final Matrix4fExt matrix, int[] target, int targetIndex, int sectorId, int sectorRelativeRegionOrigin) {
		// PERF: given the size of the call stack, possibly better to move this to the encoder

		final int[] data = this.data;
		final int index = baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_X;
		final float x = Float.intBitsToFloat(data[index]);
		final float y = Float.intBitsToFloat(data[index + 1]);
		final float z = Float.intBitsToFloat(data[index + 2]);

		final float xOut = matrix.a00() * x + matrix.a01() * y + matrix.a02() * z + matrix.a03();
		final float yOut = matrix.a10() * x + matrix.a11() * y + matrix.a12() * z + matrix.a13();
		final float zOut = matrix.a20() * x + matrix.a21() * y + matrix.a22() * z + matrix.a23();

		int xInt = MathHelper.floor(xOut);
		int yInt = MathHelper.floor(yOut);
		int zInt = MathHelper.floor(zOut);

		final int xFract = Math.round((xOut - xInt) * 0xFFFF);
		final int yFract = Math.round((yOut - yInt) * 0xFFFF);
		final int zFract = Math.round((zOut - zInt) * 0xFFFF);

		// because our integer component could be negative, we have to unpack and repack the sector components
		xInt += (sectorRelativeRegionOrigin & 0xFF);
		yInt += ((sectorRelativeRegionOrigin >> 8) & 0xFF);
		zInt += ((sectorRelativeRegionOrigin >> 16) & 0xFF);

		target[targetIndex] = sectorId | (xFract << 16);
		target[targetIndex + 1] = yFract | (zFract << 16);
		target[targetIndex + 2] = xInt | (yInt << 8) | (zInt << 16);
	}

	public void appendVertex(final int vertexIndex, int[] target, int targetIndex) {
		final int[] data = this.data;
		final int index = baseIndex + vertexIndex * BASE_VERTEX_STRIDE + FIRST_VERTEX_X;
		target[targetIndex] = data[index];
		target[targetIndex + 1] = data[index + 1];
		target[targetIndex + 2] = data[index + 2];
	}
}

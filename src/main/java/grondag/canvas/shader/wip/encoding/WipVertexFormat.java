/*
 * Copyright 2019, 2020 grondag
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package grondag.canvas.shader.wip.encoding;

import grondag.canvas.material.MaterialVertexFormat;
import grondag.canvas.material.MaterialVertextFormatElement;
import grondag.canvas.shader.wip.WipRenderState;

import static grondag.canvas.material.MaterialVertextFormatElement.BASE_RGBA_4UB;
import static grondag.canvas.material.MaterialVertextFormatElement.BASE_TEX_2US;
import static grondag.canvas.material.MaterialVertextFormatElement.LIGHTMAPS_4UB;
import static grondag.canvas.material.MaterialVertextFormatElement.MATERIAL_2US;
import static grondag.canvas.material.MaterialVertextFormatElement.NORMAL_AO_4B;
import static grondag.canvas.material.MaterialVertextFormatElement.POSITION_3F;

/**
 * Vertex formats used by Canvas with related utilities.
 *
 * There are some differences from formats used by vanilla.
 * - Formats for 3D drawing always include normals.
 *   (Points, lines and flat GUI polygons won't need them, but aren't drawn with shaders yet.)
 * - Ordering is always consistent
 * - Atlas textures are sent as normalized coordinates relative to the sprite with
 *   a separate material ID to identify information about the sprite
 * - Vanilla lightmaps are packed into two octets instead of two shorts
 * - AO shading if sent will be packed with the lightmap integer word
 *
 */
public class WipVertexFormat extends MaterialVertexFormat {
	// These are for points, lines and flat GUI elements
	public static final WipVertexFormat POSITION;
	public static final WipVertexFormat POSITION_COLOR;
	public static final WipVertexFormat POSITION_TEXTURE;
	public static final WipVertexFormat POSITION_COLOR_TEXTURE;

	// These are for 3D drawing - all have normals
	// first two octets of normal are x & z (infer y), last two are control bits
	public static final WipVertexFormat POSITION_COLOR_NORMAL;
	public static final WipVertexFormat POSITION_COLOR_LIGHT_NORMAL;

	// For single textures
	public static final WipVertexFormat POSITION_TEXTURE_NORMAL;
	public static final WipVertexFormat POSITION_COLOR_TEXTURE_NORMAL;
	public static final WipVertexFormat POSITION_COLOR_TEXTURE_LIGHT_NORMAL;

	// For atlas textures and conditional rendering - 1st short of material is sprite ID and 2nd is condition ID
	public static final WipVertexFormat POSITION_TEXTURE_MATERIAL_NORMAL;
	public static final WipVertexFormat POSITION_COLOR_TEXTURE_MATERIAL_NORMAL;
	public static final WipVertexFormat POSITION_COLOR_TEXTURE_MATERIAL_LIGHT_NORMAL;

	static {
		POSITION = new WipVertexFormat(POSITION_3F);
		POSITION_COLOR = new WipVertexFormat(POSITION_3F, BASE_RGBA_4UB);
		POSITION_TEXTURE = new WipVertexFormat(POSITION_3F, BASE_TEX_2US);
		POSITION_COLOR_TEXTURE = new WipVertexFormat(POSITION_3F, BASE_RGBA_4UB, BASE_TEX_2US);

		POSITION_COLOR_NORMAL = new WipVertexFormat(POSITION_3F, BASE_RGBA_4UB, NORMAL_AO_4B);
		POSITION_COLOR_LIGHT_NORMAL = new WipVertexFormat(POSITION_3F, BASE_RGBA_4UB, LIGHTMAPS_4UB, NORMAL_AO_4B);

		POSITION_TEXTURE_NORMAL = new WipVertexFormat(POSITION_3F, BASE_TEX_2US, NORMAL_AO_4B);
		POSITION_COLOR_TEXTURE_NORMAL = new WipVertexFormat(POSITION_3F, BASE_RGBA_4UB, BASE_TEX_2US, NORMAL_AO_4B);
		POSITION_COLOR_TEXTURE_LIGHT_NORMAL = new WipVertexFormat(POSITION_3F, BASE_RGBA_4UB, BASE_TEX_2US, LIGHTMAPS_4UB, NORMAL_AO_4B);

		POSITION_TEXTURE_MATERIAL_NORMAL = new WipVertexFormat(POSITION_3F, BASE_TEX_2US, MATERIAL_2US, NORMAL_AO_4B);
		POSITION_COLOR_TEXTURE_MATERIAL_NORMAL = new WipVertexFormat(POSITION_3F, BASE_RGBA_4UB, BASE_TEX_2US, MATERIAL_2US, NORMAL_AO_4B);
		POSITION_COLOR_TEXTURE_MATERIAL_LIGHT_NORMAL = new WipVertexFormat(POSITION_3F, BASE_RGBA_4UB, BASE_TEX_2US, LIGHTMAPS_4UB, MATERIAL_2US, NORMAL_AO_4B);
	}

	public final int colorIndex;
	public final int textureIndex;
	public final int materialIndex;
	public final int lightIndex;
	public final int normalIndex;


	private WipVertexFormat(MaterialVertextFormatElement... elements) {
		super(elements);

		assert elements[0] == POSITION_3F;

		final int limit = elements.length;
		int nextIndex = 3;

		int colorIndex = 0;
		int textureIndex = 0;
		int materialIndex = 0;
		int lightIndex = 0;
		int normalIndex = 0;

		for (int i = 1; i < limit; ++i) {
			final MaterialVertextFormatElement e = elements[i];

			if (e == BASE_RGBA_4UB) {
				colorIndex = nextIndex++;
			} else if (e == BASE_TEX_2US) {
				textureIndex = nextIndex++;
			} else if (e == LIGHTMAPS_4UB) {
				lightIndex = nextIndex++;
			} else if (e == MATERIAL_2US) {
				materialIndex = nextIndex++;
			} else if (e == NORMAL_AO_4B) {
				normalIndex = nextIndex++;
			} else {
				throw new IllegalArgumentException("Encountered unrecognized vertex element");
			}
		}

		// position unused elements after used elements so vertex collector
		// won't fail if they are to be deliberately ignored
		if (colorIndex == 0) {
			colorIndex = nextIndex++;
		}

		if (textureIndex == 0) {
			textureIndex = nextIndex++;
		}

		if (materialIndex == 0) {
			materialIndex = nextIndex++;
		}

		if (lightIndex == 0) {
			lightIndex = nextIndex++;
		}

		if (normalIndex == 0) {
			normalIndex = nextIndex++;
		}

		assert nextIndex == 8;

		this.colorIndex = colorIndex;
		this.textureIndex = textureIndex;
		this.materialIndex = materialIndex;
		this.lightIndex = lightIndex;
		this.normalIndex = normalIndex;
	}

	public static WipVertexFormat forState(WipRenderState state) {
		return POSITION_COLOR_TEXTURE_MATERIAL_LIGHT_NORMAL;
	}
}

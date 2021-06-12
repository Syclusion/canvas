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

package grondag.canvas.terrain.occlusion;

import static grondag.canvas.terrain.occlusion.Constants.CAMERA_PRECISION_BITS;
import static grondag.canvas.terrain.occlusion.Constants.CAMERA_PRECISION_UNITY;
import static grondag.canvas.terrain.occlusion.Constants.DOWN;
import static grondag.canvas.terrain.occlusion.Constants.EAST;
import static grondag.canvas.terrain.occlusion.Constants.EMPTY_BITS;
import static grondag.canvas.terrain.occlusion.Constants.NORTH;
import static grondag.canvas.terrain.occlusion.Constants.PIXEL_HEIGHT;
import static grondag.canvas.terrain.occlusion.Constants.PIXEL_WIDTH;
import static grondag.canvas.terrain.occlusion.Constants.SOUTH;
import static grondag.canvas.terrain.occlusion.Constants.TILE_COUNT;
import static grondag.canvas.terrain.occlusion.Constants.UP;
import static grondag.canvas.terrain.occlusion.Constants.V000;
import static grondag.canvas.terrain.occlusion.Constants.V001;
import static grondag.canvas.terrain.occlusion.Constants.V010;
import static grondag.canvas.terrain.occlusion.Constants.V011;
import static grondag.canvas.terrain.occlusion.Constants.V100;
import static grondag.canvas.terrain.occlusion.Constants.V101;
import static grondag.canvas.terrain.occlusion.Constants.V110;
import static grondag.canvas.terrain.occlusion.Constants.V111;
import static grondag.canvas.terrain.occlusion.Constants.WEST;

import java.io.File;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import grondag.bitraster.Matrix4L;
import grondag.bitraster.PackedBox;
import grondag.canvas.CanvasMod;
import grondag.canvas.mixinterface.Matrix4fExt;
import grondag.canvas.render.TerrainFrustum;
import grondag.canvas.terrain.region.BuiltRenderRegion;

public class TerrainOccluder {
	/** How close face must be to trigger aggressive refresh of occlusion. */
	private static final int NEAR_RANGE = 8 << CAMERA_PRECISION_BITS;

	private final Matrix4L baseMvpMatrix = new Matrix4L();

	private final Rasterizer raster = new Rasterizer();
	private int occluderVersion = 1;
	private final BoxTest[] boxTests = new BoxTest[128];
	private final BoxDraw[] boxDraws = new BoxDraw[128];
	private long viewX;
	private long viewY;
	private long viewZ;
	// Add these to region-relative box coordinates to get camera-relative coordinates
	// They are in camera fixed precision.
	private int offsetX;
	private int offsetY;
	private int offsetZ;
	private int occlusionRange;
	private int regionSquaredChunkDist;
	private int viewVersion = -1;
	private int regionVersion = -1;
	private volatile boolean forceRedraw = false;
	private boolean needsRedraw = false;
	private int maxSquaredChunkDistance;
	private boolean hasNearOccluders = false;

	/**
	 * This frustum is a snapshot of the view frustum and may lag behind for a frame or two.
	 * A snapshot is used because occlusion test can happen off the main render thread and we
	 * need a stable frustum for each occlusion update.
	 */
	private final TerrainFrustum occlusionFrustum = new TerrainFrustum();
	private final BlockPos.Mutable originForTracing = new BlockPos.Mutable();

	/**
	 * Synchronizes our frustum snapshot with the input, typically the active terrain view frustum.
	 * Should be called from the main thread when the source is known to be stable and correct.
	 * The snapshot will be used (potentially off thread) for all occlusion tests until the next update.
	 */
	public void updateFrustum(TerrainFrustum source) {
		occlusionFrustum.copy(source);
	}

	public int frustumViewVersion() {
		return occlusionFrustum.viewVersion();
	}

	public int frustumPositionVersion() {
		return occlusionFrustum.occlusionPositionVersion();
	}

	public Vec3d frustumCameraPos() {
		return occlusionFrustum.lastCameraPos();
	}

	public boolean isRegionVisible(BuiltRenderRegion builtRenderRegion) {
		return occlusionFrustum.isRegionVisible(builtRenderRegion);
	}

	@Override
	public String toString() {
		return String.format("OccluderVersion:%d  viewX:%d  viewY:%d  viewZ:%d  offsetX:%d  offsetY:%d  offsetZ:%d viewVersion:%d  regionVersion:%d  forceRedraw:%b  needsRedraw:%b  matrix:%s",
				occluderVersion, viewX, viewY, viewZ, offsetX, offsetY, offsetZ, viewVersion, regionVersion, forceRedraw, needsRedraw, raster.mvpMatrix.toString());
	}

	{
		boxTests[0] = (x0, y0, z0, x1, y1, z1) -> {
			return false;
		};

		boxTests[UP] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			return raster.testQuad(V110, V010, V011, V111);
		};

		boxTests[DOWN] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			return raster.testQuad(V000, V100, V101, V001);
		};

		boxTests[EAST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			return raster.testQuad(V101, V100, V110, V111);
		};

		boxTests[WEST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			return raster.testQuad(V000, V001, V011, V010);
		};

		boxTests[NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V110, x1, y1, z0);
			return raster.testQuad(V100, V000, V010, V110);
		};

		boxTests[SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V111, x1, y1, z1);
			return raster.testQuad(V001, V101, V111, V011);
		};

		// NB: Split across two quads to give more evenly-sized test regions vs potentially one big and one very small
		boxTests[UP | EAST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			return raster.testQuad(V010, V011, V111, V101)
					|| raster.testQuad(V101, V100, V110, V010);
		};

		boxTests[UP | WEST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			return raster.testQuad(V111, V110, V010, V000)
					|| raster.testQuad(V000, V001, V011, V111);
		};

		boxTests[UP | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			return raster.testQuad(V011, V111, V110, V100)
					|| raster.testQuad(V100, V000, V010, V011);
		};

		boxTests[UP | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			return raster.testQuad(V110, V010, V011, V001)
					|| raster.testQuad(V001, V101, V111, V110);
		};

		boxTests[DOWN | EAST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			return raster.testQuad(V001, V000, V100, V110)
					|| raster.testQuad(V110, V111, V101, V001);
		};

		boxTests[DOWN | WEST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			return raster.testQuad(V100, V101, V001, V011)
					|| raster.testQuad(V011, V010, V000, V100);
		};

		boxTests[DOWN | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			return raster.testQuad(V101, V001, V000, V010)
					|| raster.testQuad(V010, V110, V100, V101);
		};

		boxTests[DOWN | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V111, x1, y1, z1);
			return raster.testQuad(V000, V100, V101, V111)
					|| raster.testQuad(V111, V011, V001, V000);
		};

		boxTests[NORTH | EAST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			return raster.testQuad(V000, V010, V110, V111)
					|| raster.testQuad(V111, V101, V100, V000);
		};

		boxTests[NORTH | WEST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V110, x1, y1, z0);
			return raster.testQuad(V110, V100, V000, V001)
					|| raster.testQuad(V001, V011, V010, V110);
		};

		boxTests[SOUTH | EAST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			return raster.testQuad(V011, V001, V101, V100)
					|| raster.testQuad(V100, V110, V111, V011);
		};

		boxTests[SOUTH | WEST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V111, x1, y1, z1);
			return raster.testQuad(V101, V111, V011, V010)
					|| raster.testQuad(V010, V000, V001, V101);
		};

		// NB: When three faces are visible, omit nearest vertex and draw two quads instead of three.

		boxTests[UP | EAST | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V111, x1, y1, z1);
			return raster.testQuad(V011, V111, V101, V100)
					|| raster.testQuad(V100, V000, V010, V011);
		};

		boxTests[UP | WEST | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			return raster.testQuad(V111, V110, V100, V000)
					|| raster.testQuad(V000, V001, V011, V111);
		};

		boxTests[UP | EAST | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			return raster.testQuad(V010, V011, V001, V101)
					|| raster.testQuad(V101, V100, V110, V010);
		};

		boxTests[UP | WEST | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			return raster.testQuad(V110, V010, V000, V001)
					|| raster.testQuad(V001, V101, V111, V110);
		};

		boxTests[DOWN | EAST | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			return raster.testQuad(V001, V000, V010, V110)
					|| raster.testQuad(V110, V111, V101, V001);
		};

		boxTests[DOWN | WEST | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			return raster.testQuad(V101, V001, V011, V010)
					|| raster.testQuad(V010, V110, V100, V101);
		};

		boxTests[DOWN | EAST | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			return raster.testQuad(V000, V100, V110, V111)
					|| raster.testQuad(V111, V011, V001, V000);
		};

		boxTests[DOWN | WEST | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V111, x1, y1, z1);
			return raster.testQuad(V100, V101, V111, V011)
					|| raster.testQuad(V011, V010, V000, V100);
		};

		////

		boxDraws[0] = (x0, y0, z0, x1, y1, z1) -> {
			// NOOP
		};

		boxDraws[UP] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			raster.drawQuad(V110, V010, V011, V111);
		};

		boxDraws[DOWN] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.drawQuad(V000, V100, V101, V001);
		};

		boxDraws[EAST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			raster.drawQuad(V101, V100, V110, V111);
		};

		boxDraws[WEST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.drawQuad(V000, V001, V011, V010);
		};

		boxDraws[NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V110, x1, y1, z0);
			raster.drawQuad(V100, V000, V010, V110);
		};

		boxDraws[SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V111, x1, y1, z1);
			raster.drawQuad(V001, V101, V111, V011);
		};

		// NB: Split across two quads to give more evenly-sized test regions vs potentially one big and one very small
		boxDraws[UP | EAST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			raster.drawQuad(V010, V011, V111, V101);
			raster.drawQuad(V101, V100, V110, V010);
		};

		boxDraws[UP | WEST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			raster.drawQuad(V111, V110, V010, V000);
			raster.drawQuad(V000, V001, V011, V111);
		};

		boxDraws[UP | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			raster.drawQuad(V011, V111, V110, V100);
			raster.drawQuad(V100, V000, V010, V011);
		};

		boxDraws[UP | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			raster.drawQuad(V110, V010, V011, V001);
			raster.drawQuad(V001, V101, V111, V110);
		};

		boxDraws[DOWN | EAST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			raster.drawQuad(V001, V000, V100, V110);
			raster.drawQuad(V110, V111, V101, V001);
		};

		boxDraws[DOWN | WEST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.drawQuad(V100, V101, V001, V011);
			raster.drawQuad(V011, V010, V000, V100);
		};

		boxDraws[DOWN | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.drawQuad(V101, V001, V000, V010);
			raster.drawQuad(V010, V110, V100, V101);
		};

		boxDraws[DOWN | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V111, x1, y1, z1);
			raster.drawQuad(V000, V100, V101, V111);
			raster.drawQuad(V111, V011, V001, V000);
		};

		boxDraws[NORTH | EAST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			raster.drawQuad(V000, V010, V110, V111);
			raster.drawQuad(V111, V101, V100, V000);
		};

		boxDraws[NORTH | WEST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V110, x1, y1, z0);
			raster.drawQuad(V110, V100, V000, V001);
			raster.drawQuad(V001, V011, V010, V110);
		};

		boxDraws[SOUTH | EAST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			raster.drawQuad(V011, V001, V101, V100);
			raster.drawQuad(V100, V110, V111, V011);
		};

		boxDraws[SOUTH | WEST] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V111, x1, y1, z1);
			raster.drawQuad(V101, V111, V011, V010);
			raster.drawQuad(V010, V000, V001, V101);
		};

		// NB: When three faces are visible, omit nearest vertex and draw two quads instead of three.

		boxDraws[UP | EAST | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V111, x1, y1, z1);
			raster.drawQuad(V011, V111, V101, V100);
			raster.drawQuad(V100, V000, V010, V011);
		};

		boxDraws[UP | WEST | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			raster.drawQuad(V111, V110, V100, V000);
			raster.drawQuad(V000, V001, V011, V111);
		};

		boxDraws[UP | EAST | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.drawQuad(V010, V011, V001, V101);
			raster.drawQuad(V101, V100, V110, V010);
		};

		boxDraws[UP | WEST | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			raster.drawQuad(V110, V010, V000, V001);
			raster.drawQuad(V001, V101, V111, V110);
		};

		boxDraws[DOWN | EAST | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			raster.drawQuad(V001, V000, V010, V110);
			raster.drawQuad(V110, V111, V101, V001);
		};

		boxDraws[DOWN | WEST | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V110, x1, y1, z0);
			raster.drawQuad(V101, V001, V011, V010);
			raster.drawQuad(V010, V110, V100, V101);
		};

		boxDraws[DOWN | EAST | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V001, x0, y0, z1);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V110, x1, y1, z0);
			raster.setupVertex(V111, x1, y1, z1);
			raster.drawQuad(V000, V100, V110, V111);
			raster.drawQuad(V111, V011, V001, V000);
		};

		boxDraws[DOWN | WEST | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			raster.setupVertex(V000, x0, y0, z0);
			raster.setupVertex(V010, x0, y1, z0);
			raster.setupVertex(V011, x0, y1, z1);
			raster.setupVertex(V100, x1, y0, z0);
			raster.setupVertex(V101, x1, y0, z1);
			raster.setupVertex(V111, x1, y1, z1);
			raster.drawQuad(V100, V101, V111, V011);
			raster.drawQuad(V011, V010, V000, V100);
		};
	}

	public void copyFrom(TerrainOccluder source) {
		baseMvpMatrix.copyFrom(source.baseMvpMatrix);
		raster.copyFrom(source.raster);
		viewX = source.viewX;
		viewY = source.viewY;
		viewZ = source.viewZ;

		offsetX = source.offsetX;
		offsetY = source.offsetY;
		offsetZ = source.offsetZ;

		occlusionRange = source.occlusionRange;

		viewVersion = source.viewVersion;
		regionVersion = source.regionVersion;
		occluderVersion = source.occluderVersion;
		maxSquaredChunkDistance = source.maxSquaredChunkDistance;

		forceRedraw = source.forceRedraw;
		needsRedraw = source.needsRedraw;
	}

	/**
	 * Previously tested regions can reuse test results if their version matches.
	 * However, they must still be drawn (if visible) if indicated by {@link #clearSceneIfNeeded(int, int)}.
	 */
	public int version() {
		return occluderVersion;
	}

	/**
	 * Force update to new version.
	 */
	public void invalidate() {
		if (TerrainIterator.TRACE_OCCLUSION_OUTCOMES) {
			CanvasMod.LOG.info("Invalidating terrain occluder");
		}

		forceRedraw = true;
	}

	public void prepareRegion(BlockPos origin, int occlusionRange, int squaredChunkDistance) {
		this.occlusionRange = occlusionRange;
		regionSquaredChunkDist = squaredChunkDistance;

		if (TerrainIterator.TRACE_OCCLUSION_OUTCOMES) {
			originForTracing.set(origin);
		}

		// PERF: could perhaps reuse CameraRelativeCenter values in BuildRenderRegion that are used by Frustum
		offsetX = (int) ((origin.getX() << CAMERA_PRECISION_BITS) - viewX);
		offsetY = (int) ((origin.getY() << CAMERA_PRECISION_BITS) - viewY);
		offsetZ = (int) ((origin.getZ() << CAMERA_PRECISION_BITS) - viewZ);

		final Matrix4L mvpMatrix = raster.mvpMatrix;
		mvpMatrix.copyFrom(baseMvpMatrix);
		mvpMatrix.translate(offsetX, offsetY, offsetZ, CAMERA_PRECISION_BITS);
	}

	public void outputRaster() {
		outputRaster("canvas_occlusion_raster.png", false);
	}

	public void outputRaster(String fileName, boolean force) {
		final long t = System.currentTimeMillis();

		if (!force && t >= raster.nextRasterOutputTime) {
			force = true;
			raster.nextRasterOutputTime = t + 1000;
		}

		if (force) {
			final NativeImage nativeImage = new NativeImage(PIXEL_WIDTH, PIXEL_HEIGHT, false);

			for (int x = 0; x < PIXEL_WIDTH; x++) {
				for (int y = 0; y < PIXEL_HEIGHT; y++) {
					nativeImage.setPixelColor(x, y, raster.testPixel(x, y) ? -1 : 0xFF000000);
				}
			}

			nativeImage.mirrorVertically();

			@SuppressWarnings("resource") final File file = new File(MinecraftClient.getInstance().runDirectory, fileName);

			Util.getIoWorkerExecutor().execute(() -> {
				try {
					nativeImage.writeFile(file);
				} catch (final Exception e) {
					CanvasMod.LOG.warn("Couldn't save occluder image", e);
				} finally {
					nativeImage.close();
				}
			});
		}
	}

	/**
	 * Check if needs redrawn and prep for redraw if so.
	 * When false, regions should be drawn only if their occluder version is not current.
	 */
	public boolean prepareScene() {
		final int viewVersion = occlusionFrustum.viewVersion();

		if (this.viewVersion != viewVersion) {
			final Matrix4L baseMvpMatrix = this.baseMvpMatrix;
			final Matrix4L tempMatrix = raster.mvpMatrix;
			final Matrix4fExt projectionMatrix = occlusionFrustum.projectionMatrix();
			final Matrix4fExt modelMatrix = occlusionFrustum.modelMatrix();

			baseMvpMatrix.loadIdentity();

			projectionMatrix.copyTo(tempMatrix);
			baseMvpMatrix.multiply(tempMatrix);

			modelMatrix.copyTo(tempMatrix);
			baseMvpMatrix.multiply(tempMatrix);

			final Vec3d cameraPos = occlusionFrustum.lastCameraPos();
			viewX = Math.round(cameraPos.getX() * CAMERA_PRECISION_UNITY);
			viewY = Math.round(cameraPos.getY() * CAMERA_PRECISION_UNITY);
			viewZ = Math.round(cameraPos.getZ() * CAMERA_PRECISION_UNITY);
		}

		if (forceRedraw || this.viewVersion != viewVersion) {
			if (TerrainIterator.TRACE_OCCLUSION_OUTCOMES) {
				if (forceRedraw) {
					CanvasMod.LOG.info("Terrain occluder redrawing due to force redraw");
				} else {
					CanvasMod.LOG.info("Terrain occluder redrawing due to view change");
				}
			}

			this.viewVersion = viewVersion;
			System.arraycopy(EMPTY_BITS, 0, raster.tiles, 0, TILE_COUNT);
			forceRedraw = false;
			needsRedraw = true;
			hasNearOccluders = false;
			maxSquaredChunkDistance = 0;
			++occluderVersion;
		} else {
			needsRedraw = false;
		}

		return needsRedraw;
	}

	/**
	 * True if occlusion includes geometry within the near region.
	 * When true, simple movement distance test isn't sufficient for knowing if redraw is needed.
	*/
	public boolean hasNearOccluders() {
		return hasNearOccluders;
	}

	public boolean needsRedraw() {
		return needsRedraw;
	}

	/**
	 * Does not rely on winding order but instead the distance from
	 * plane with known facing to camera position.
	 */
	public boolean isBoxVisible(int packedBox) {
		final int x0 = PackedBox.x0(packedBox) - 1;
		final int y0 = PackedBox.y0(packedBox) - 1;
		final int z0 = PackedBox.z0(packedBox) - 1;
		final int x1 = PackedBox.x1(packedBox) + 1;
		final int y1 = PackedBox.y1(packedBox) + 1;
		final int z1 = PackedBox.z1(packedBox) + 1;

		final int offsetX = this.offsetX;
		final int offsetY = this.offsetY;
		final int offsetZ = this.offsetZ;

		int outcome = 0;

		// if camera below top face can't be seen
		if (offsetY < -(y1 << CAMERA_PRECISION_BITS)) {
			outcome |= UP;
		} else if (offsetY > -(y0 << CAMERA_PRECISION_BITS)) {
			outcome |= DOWN;
		}

		if (offsetX < -(x1 << CAMERA_PRECISION_BITS)) {
			outcome |= EAST;
		} else if (offsetX > -(x0 << CAMERA_PRECISION_BITS)) {
			outcome |= WEST;
		}

		if (offsetZ < -(z1 << CAMERA_PRECISION_BITS)) {
			outcome |= SOUTH;
		} else if (offsetZ > -(z0 << CAMERA_PRECISION_BITS)) {
			outcome |= NORTH;
		}

		return boxTests[outcome].apply(x0, y0, z0, x1, y1, z1);
	}

	public boolean isEmptyRegionVisible(BlockPos origin) {
		prepareRegion(origin, 0, 0);
		return isBoxVisible(PackedBox.FULL_BOX);
	}

	/**
	 * Does not rely on winding order but instead the distance from
	 * plane with known facing to camera position.
	 */
	private void occludeInner(int packedBox) {
		final int x0 = PackedBox.x0(packedBox);
		final int y0 = PackedBox.y0(packedBox);
		final int z0 = PackedBox.z0(packedBox);
		final int x1 = PackedBox.x1(packedBox);
		final int y1 = PackedBox.y1(packedBox);
		final int z1 = PackedBox.z1(packedBox);

		final int offsetX = this.offsetX;
		final int offsetY = this.offsetY;
		final int offsetZ = this.offsetZ;

		int outcome = 0;

		boolean hasNear = true;

		final int top = (y1 << CAMERA_PRECISION_BITS) + offsetY;

		// NB: entirely possible for neither top or bottom to be visible.
		// This happens when camera is between them.

		if (top < 0) {
			// camera above top face
			outcome |= UP;
			hasNear &= top > -NEAR_RANGE;
		} else {
			final int bottom = (y0 << CAMERA_PRECISION_BITS) + offsetY;

			if (bottom > 0) {
				// camera below bottom face
				outcome |= DOWN;
				hasNear &= bottom < NEAR_RANGE;
			}
		}

		final int east = (x1 << CAMERA_PRECISION_BITS) + offsetX;

		if (east < 0) {
			outcome |= EAST;
			hasNear &= east > -NEAR_RANGE;
		} else {
			final int west = (x0 << CAMERA_PRECISION_BITS) + offsetX;

			if (west > 0) {
				outcome |= WEST;
				hasNear &= west < NEAR_RANGE;
			}
		}

		final int south = (z1 << CAMERA_PRECISION_BITS) + offsetZ;

		if (south < 0) {
			outcome |= SOUTH;
			hasNear &= south > -NEAR_RANGE;
		} else {
			final int north = (z0 << CAMERA_PRECISION_BITS) + offsetZ;

			if (north > 0) {
				outcome |= NORTH;
				hasNear &= north < NEAR_RANGE;
			}
		}

		hasNearOccluders |= hasNear;

		boxDraws[outcome].apply(x0, y0, z0, x1, y1, z1);
	}

	public void occlude(int[] visData) {
		final int occlusionRange = this.occlusionRange;
		final int limit = visData.length;

		if (limit > 1) {
			boolean updateDist = false;

			for (int i = 1; i < limit; i++) {
				final int box = visData[i];

				if (occlusionRange > PackedBox.range(box)) {
					break;
				}

				updateDist = true;
				occludeInner(box);
			}

			if (updateDist) {
				if (TerrainIterator.TRACE_OCCLUSION_OUTCOMES && regionSquaredChunkDist < maxSquaredChunkDistance) {
					CanvasMod.LOG.warn("Terrain Occluder went backwards in chunkdistance @" + originForTracing.toShortString());
				}

				if (maxSquaredChunkDistance < regionSquaredChunkDist) {
					if (TerrainIterator.TRACE_OCCLUSION_OUTCOMES) {
						CanvasMod.LOG.info("Occluder advancing to dist " + regionSquaredChunkDist);
					}

					maxSquaredChunkDistance = regionSquaredChunkDist;
				}
			}
		}
	}

	/**
	 * Returns value with face flags set when all such
	 * faces in the region are at least 64 blocks away camera.
	 *
	 * @param region
	 * @return
	 */
	int backfaceVisibilityFlags(BuiltRenderRegion region) {
		final int offsetX = this.offsetX;
		final int offsetY = this.offsetY;
		final int offsetZ = this.offsetZ;

		int outcome = 0;

		// if offsetY is positive, chunk origin is above camera
		// if offsetY is negative, chunk origin is below camera;
		/**
		 * offsets are origin - camera
		 * if looking directly at chunk center, two values will be -8
		 *
		 * pos face check: -8 < -(16) == false
		 * neg face check: -8 > -(0) == false
		 *
		 * if 32 blocks above/positive to origin two values will be -32
		 *
		 * pos face check: -32 < -(16) == true
		 * neg face check: -32 > -(0) == false
		 *
		 * if 32 blocks below/positive to origin two values will be 32
		 *
		 * pos face check: 32 < -(16) == false
		 * neg face check: 32 > -(0) == true
		 *
		 *
		 * if looking directly at chunk center, two values will be -8
		 *
		 * pos face check: -8 < -(16) == false
		 * neg face check: -8 > -(0) == false
		 *
		 * if 64 blocks above/positive to origin two values will be -64
		 * neg face check: -64 > -(16) == false
		 *
		 * neg face > -64
		 *
		 * if 64 blocks below/positive to origin two values will be 64
		 *
		 * pos face check: 64 < -16 == false
		 *
		 * pos face culled when offset > 48
		 * neg face culled when offset < -72
		 *
		 *
		 * pos face visible when offset <= 48
		 * neg face visible when offset >= -72
		 */
		if (offsetY < (48 << CAMERA_PRECISION_BITS)) {
			outcome |= UP;
		} else if (offsetY > -(72 << CAMERA_PRECISION_BITS)) {
			outcome |= DOWN;
		}

		if (offsetX < (48 << CAMERA_PRECISION_BITS)) {
			outcome |= EAST;
		} else if (offsetX > -(72 << CAMERA_PRECISION_BITS)) {
			outcome |= WEST;
		}

		if (offsetZ < (48 << CAMERA_PRECISION_BITS)) {
			outcome |= SOUTH;
		} else if (offsetZ > -(72 << CAMERA_PRECISION_BITS)) {
			outcome |= NORTH;
		}

		return outcome;
	}

	@FunctionalInterface
	interface BoxTest {
		boolean apply(int x0, int y0, int z0, int x1, int y1, int z1);
	}

	@FunctionalInterface
	interface BoxDraw {
		void apply(int x0, int y0, int z0, int x1, int y1, int z1);
	}

	public int maxSquaredChunkDistance() {
		return maxSquaredChunkDistance;
	}
}

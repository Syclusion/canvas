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

package grondag.canvas.terrain.region;

import java.util.function.Consumer;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import net.fabricmc.fabric.api.renderer.v1.model.ModelHelper;

import grondag.canvas.apiimpl.util.FaceConstants;
import grondag.canvas.terrain.occlusion.geometry.OcclusionResult;
import grondag.canvas.varia.BlockPosHelper;

/** Caches directly adjacent regions for fast access and provides visitor operations for terrain iteration. */
public class NeighborRegions {
	private final RenderRegion owner;
	private final boolean isBottom;
	private final boolean isTop;
	private final RenderRegion[] neighbors = new RenderRegion[6];

	NeighborRegions(RenderRegion owner) {
		this.owner = owner;
		BlockPos origin = owner.origin;
		ClientWorld world = owner.worldRenderState.getWorld();
		isBottom = origin.getY() == world.getBottomY();
		isTop = origin.getY() == world.getTopY() - 16;
	}

	void close() {
		for (int i = 0; i < 6; ++i) {
			final RenderRegion nr = neighbors[i];

			if (nr != null) {
				nr.neighbors.notifyNeighborClosed(BlockPosHelper.oppositeFaceIndex(i), owner);
			}
		}
	}

	public void forEachAvailable(Consumer<RenderRegion> operation) {
		operation.accept(getNeighbor(FaceConstants.EAST_INDEX));
		operation.accept(getNeighbor(FaceConstants.WEST_INDEX));
		operation.accept(getNeighbor(FaceConstants.NORTH_INDEX));
		operation.accept(getNeighbor(FaceConstants.SOUTH_INDEX));

		if (!isTop) {
			operation.accept(getNeighbor(FaceConstants.UP_INDEX));
		}

		if (!isBottom) {
			operation.accept(getNeighbor(FaceConstants.DOWN_INDEX));
		}
	}

	private RenderRegion getNeighbor(int faceIndex) {
		RenderRegion region = neighbors[faceIndex];

		if (region == null || region.isClosed()) {
			// this check is now done in all callers
			//if ((faceIndex == FaceConstants.UP_INDEX && isTop) || (faceIndex == FaceConstants.DOWN_INDEX && isBottom)) {
			//	return null;
			//}

			final Direction face = ModelHelper.faceFromIndex(faceIndex);
			BlockPos origin = owner.origin;
			region = owner.storage.getOrCreateRegion(origin.getX() + face.getOffsetX() * 16, origin.getY() + face.getOffsetY() * 16, origin.getZ() + face.getOffsetZ() * 16);
			neighbors[faceIndex] = region;
			region.neighbors.attachOrConfirmVisitingNeighbor(BlockPosHelper.oppositeFaceIndex(faceIndex), owner);
		}

		return region;
	}

	private void attachOrConfirmVisitingNeighbor(int visitingFaceIndex, RenderRegion visitingNeighbor) {
		assert neighbors[visitingFaceIndex] == null || neighbors[visitingFaceIndex] == visitingNeighbor
			: "Visting render region is attaching to a position that already has a non-null region";

		neighbors[visitingFaceIndex] = visitingNeighbor;
	}

	private void notifyNeighborClosed(int closedFaceIndex, RenderRegion closingNeighbor) {
		assert neighbors[closedFaceIndex] == closingNeighbor
			: "Closing neighbor render region does not match current attachment";

		neighbors[closedFaceIndex] = null;
	}

	public void enqueueUnvistedCameraNeighbors(final long mutalOcclusionFaceFlags) {
		final int mySquaredDist = owner.origin.squaredCameraChunkDistance();
		final int entryFaceFlags = owner.cameraVisibility.entryFaceFlags();

		if (OcclusionResult.canVisitFace(mutalOcclusionFaceFlags, entryFaceFlags, FaceConstants.EAST_INDEX)) {
			getNeighbor(FaceConstants.EAST_INDEX).cameraVisibility.addIfFrontFacing(FaceConstants.WEST_FLAG, mySquaredDist);
		}

		if (OcclusionResult.canVisitFace(mutalOcclusionFaceFlags, entryFaceFlags, FaceConstants.WEST_INDEX)) {
			getNeighbor(FaceConstants.WEST_INDEX).cameraVisibility.addIfFrontFacing(FaceConstants.EAST_FLAG, mySquaredDist);
		}

		if (OcclusionResult.canVisitFace(mutalOcclusionFaceFlags, entryFaceFlags, FaceConstants.NORTH_INDEX)) {
			getNeighbor(FaceConstants.NORTH_INDEX).cameraVisibility.addIfFrontFacing(FaceConstants.SOUTH_FLAG, mySquaredDist);
		}

		if (OcclusionResult.canVisitFace(mutalOcclusionFaceFlags, entryFaceFlags, FaceConstants.SOUTH_INDEX)) {
			getNeighbor(FaceConstants.SOUTH_INDEX).cameraVisibility.addIfFrontFacing(FaceConstants.NORTH_FLAG, mySquaredDist);
		}

		if (!isTop && OcclusionResult.canVisitFace(mutalOcclusionFaceFlags, entryFaceFlags, FaceConstants.UP_INDEX)) {
			getNeighbor(FaceConstants.UP_INDEX).cameraVisibility.addIfFrontFacing(FaceConstants.DOWN_FLAG, mySquaredDist);
		}

		if (!isBottom && OcclusionResult.canVisitFace(mutalOcclusionFaceFlags, entryFaceFlags, FaceConstants.DOWN_INDEX)) {
			getNeighbor(FaceConstants.DOWN_INDEX).cameraVisibility.addIfFrontFacing(FaceConstants.UP_FLAG, mySquaredDist);
		}
	}

	public void enqueueUnvistedShadowNeighbors() {
		getNeighbor(FaceConstants.EAST_INDEX).shadowVisibility.addIfValid(FaceConstants.WEST_FLAG);
		getNeighbor(FaceConstants.WEST_INDEX).shadowVisibility.addIfValid(FaceConstants.EAST_FLAG);
		getNeighbor(FaceConstants.NORTH_INDEX).shadowVisibility.addIfValid(FaceConstants.SOUTH_FLAG);
		getNeighbor(FaceConstants.SOUTH_INDEX).shadowVisibility.addIfValid(FaceConstants.NORTH_FLAG);

		if (!isTop) {
			getNeighbor(FaceConstants.UP_INDEX).shadowVisibility.addIfValid(FaceConstants.DOWN_FLAG);
		}

		if (!isBottom) {
			getNeighbor(FaceConstants.DOWN_INDEX).shadowVisibility.addIfValid(FaceConstants.UP_FLAG);
		}
	}
}

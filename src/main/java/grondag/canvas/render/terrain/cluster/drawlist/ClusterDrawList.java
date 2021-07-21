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

package grondag.canvas.render.terrain.cluster.drawlist;

import java.util.IdentityHashMap;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;

import grondag.canvas.render.terrain.cluster.Slab;
import grondag.canvas.render.terrain.cluster.VertexCluster;
import grondag.canvas.render.terrain.cluster.VertexClusterRealm;
import grondag.canvas.render.terrain.cluster.ClusteredDrawableStorage;
import grondag.canvas.varia.GFX;

public class ClusterDrawList {
	record DrawSpec(Slab slab, IndexSlab indexSlab, int triVertexCount, int indexBaseByteAddress) { }

	final ObjectArrayList<ClusteredDrawableStorage> stores = new ObjectArrayList<>();
	final VertexCluster cluster;
	final RealmDrawList owner;
	private final ObjectArrayList<DrawSpec> drawSpecs = new ObjectArrayList<>();

	ClusterDrawList(VertexCluster cluster, RealmDrawList owner) {
		this.cluster = cluster;
		this.owner = owner;
	}

	IndexSlab build(IndexSlab indexSlab) {
		assert drawSpecs.isEmpty();

		if (cluster.owner == VertexClusterRealm.TRANSLUCENT) {
			indexSlab = buildTranslucent(indexSlab);
		} else {
			indexSlab = buildSolid(indexSlab);
		}

		return indexSlab;
	}

	/** Maintains region sort order at the cost of extra binds/calls if needed. */
	private IndexSlab buildTranslucent(IndexSlab indexSlab) {
		Slab lastSlab = null;
		final ObjectArrayList<ClusteredDrawableStorage> specRegions = new ObjectArrayList<>();
		int specQuadVertexCount = 0;

		for (var region : stores) {
			if (region.slab() != lastSlab) {
				// NB: addSpec checks for empty region list (will be true for first region)
				// and also clears the list when done.
				indexSlab = addSpec(indexSlab, specRegions, specQuadVertexCount);
				specQuadVertexCount = 0;
				lastSlab = region.slab();
			}

			specRegions.add(region);
			specQuadVertexCount += region.quadVertexCount;
		}

		indexSlab = addSpec(indexSlab, specRegions, specQuadVertexCount);

		return indexSlab;
	}

	private static class SolidSpecList extends ObjectArrayList<ClusteredDrawableStorage> {
		private int specQuadVertexCount;
	}

	/** Minimizes binds/calls. */
	private IndexSlab buildSolid(IndexSlab indexSlab) {
		final IdentityHashMap<Slab, SolidSpecList> map = new IdentityHashMap<>();

		// first group regions by slab
		for (var region : stores) {
			var list = map.get(region.slab());

			if (list == null) {
				list = new SolidSpecList();
				map.put(region.slab(), list);
			}

			list.add(region);
			list.specQuadVertexCount += region.quadVertexCount;
		}

		for (var list: map.values()) {
			assert list.specQuadVertexCount <= IndexSlab.MAX_INDEX_SLAB_QUAD_VERTEX_COUNT;
			indexSlab = addSpec(indexSlab, list, list.specQuadVertexCount);
		}

		return indexSlab;
	}

	/**
	 * Returns the index slab that should be used for next call.
	 * Does nothing if region list is empty and clears region list when done.
	 */
	private @Nullable IndexSlab addSpec(@Nullable IndexSlab indexSlab, ObjectArrayList<ClusteredDrawableStorage> specRegions, int specQuadVertexCount) {
		assert specQuadVertexCount >= 0;

		if (specQuadVertexCount == 0) {
			return indexSlab;
		}

		assert !specRegions.isEmpty() : "Vertex count is non-zero but region list is empty.";

		if (indexSlab == null || indexSlab.availableQuadVertexCount() < specQuadVertexCount) {
			indexSlab = IndexSlab.claim();
			owner.indexSlabs.add(indexSlab);
		}

		final int byteOffset = indexSlab.nextByteOffset();
		var slab = specRegions.get(0).slab();

		for (var region : specRegions) {
			assert region.slab() == slab;
			indexSlab.allocateAndLoad(region.baseQuadVertexIndex(), region.quadVertexCount);
		}

		assert byteOffset + specQuadVertexCount * IndexSlab.INDEX_QUAD_VERTEX_TO_TRIANGLE_BYTES_MULTIPLIER == indexSlab.nextByteOffset();

		drawSpecs.add(new DrawSpec(slab, indexSlab, specQuadVertexCount / 4 * 6, byteOffset));
		specRegions.clear();

		return indexSlab;
	}

	public void draw() {
		drawNew();
	}

	public void drawNew() {
		final int limit = drawSpecs.size();

		for (int i = 0; i < limit; ++i) {
			var spec = drawSpecs.get(i);

			spec.slab.bind();
			spec.indexSlab.bind();
			GFX.drawElements(GFX.GL_TRIANGLES, spec.triVertexCount, GFX.GL_UNSIGNED_SHORT, spec.indexBaseByteAddress);
		}
	}

	// WIP: use a version of this for new lists and gradually compact?
	public void drawOld() {
		final int limit = stores.size();

		Slab lastSlab = null;

		for (int regionIndex = 0; regionIndex < limit; ++regionIndex) {
			ClusteredDrawableStorage store = stores.get(regionIndex);
			Slab slab = store.slab();

			if (slab != lastSlab) {
				slab.bind();
				IndexSlab.fullSlabIndex().bind();
				lastSlab = slab;
			}

			// NB offset is baseQuadVertexIndex * 3 because the offset is in bytes
			// six tri vertices per four quad vertices at 2 bytes each gives 6 / 4 * 2 = 3
			GFX.drawElements(GFX.GL_TRIANGLES, store.triVertexCount, GFX.GL_UNSIGNED_SHORT, store.baseQuadVertexIndex() * IndexSlab.INDEX_QUAD_VERTEX_TO_TRIANGLE_BYTES_MULTIPLIER);
		}

		IndexSlab.fullSlabIndex().unbind();
	}

	public void add(ClusteredDrawableStorage storage) {
		assert storage.getCluster() == cluster;
		stores.add(storage);
	}

	public void invalidate() {
		owner.invalidate();
	}
}

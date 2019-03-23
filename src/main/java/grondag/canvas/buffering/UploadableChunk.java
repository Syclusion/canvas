package grondag.canvas.buffering;

import java.nio.IntBuffer;

import grondag.canvas.core.RenderPipelineImpl;
import grondag.canvas.core.VertexCollectorList;
import grondag.canvas.core.VertexPackingList;
import grondag.canvas.core.VertexPackingList.VertexPackingConsumer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

public abstract class UploadableChunk<V extends DrawableChunk> {
    protected final ObjectArrayList<DrawableDelegate> delegates = DelegateLists.getReadyDelegateList();

    public static class UploadConsumer implements VertexPackingConsumer {
        ObjectArrayList<DrawableDelegate> delegates;
        VertexCollectorList collectorList;
        int intOffset = 0;
        AllocationProvider allocator;
        
        /** Does not retain packing list reference */
        void prepare(ObjectArrayList<DrawableDelegate> delegates, VertexPackingList packingList, VertexCollectorList collectorList) {
            this.delegates = delegates;
            this.collectorList = collectorList;
            allocator = BufferManager.ALLOCATION_MANAGER.getAllocator(packingList.totalBytes());
        }

        @Override
        public void accept(RenderPipelineImpl pipeline, int vertexCount) {
            final int stride = pipeline.piplineVertexFormat().vertexStrideBytes;
            // array offset will be zero unless multiple buffers are needed
            intOffset = 0;
            allocator.claimAllocation(pipeline, vertexCount * stride, ref -> {
                final int byteOffset = ref.byteOffset();
                final int byteCount = ref.byteCount();
                final int intLength = byteCount / 4;

                ref.lockForUpload();
                final IntBuffer intBuffer = ref.intBuffer();
                intBuffer.position(byteOffset / 4);
                intBuffer.put(collectorList.get(pipeline).rawData(), intOffset, intLength);
                ref.unlockForUpload();

                intOffset += intLength;
                delegates.add(DrawableDelegate.claim(ref, pipeline, byteCount / stride));
            });
        }
    }

    ThreadLocal<UploadConsumer> uploadConsumer = ThreadLocal.withInitial(UploadConsumer::new);

    /** Does not retain packing list reference */
    protected UploadableChunk(VertexPackingList packingList, VertexCollectorList collectorList) {
        UploadConsumer uc = uploadConsumer.get();
        uc.prepare(delegates, packingList, collectorList);
        packingList.forEach(uc);
    }

    @Override
    protected void finalize() {
        assert this.delegates != null;

    }

    /**
     * Will be called from client thread - is where flush/unmap needs to happen.
     */
    public abstract V produceDrawable();

    /**
     * Called if {@link #produceDrawable()} will not be called, so can release
     * MappedBuffer(s).
     */
    public final void cancel() {
        final int limit = delegates.size();
        for (int i = 0; i < limit; i++)
            delegates.get(i).release();

        delegates.clear();
    }

    public static class Solid extends UploadableChunk<DrawableChunk.Solid> {
        public Solid(VertexPackingList packing, VertexCollectorList collectorList) {
            super(packing, collectorList);
        }

        @Override
        public DrawableChunk.Solid produceDrawable() {
            final int limit = delegates.size();
            for (int i = 0; i < limit; i++)
                delegates.get(i).flush();
            return new DrawableChunk.Solid(delegates);
        }
    }

    public static class Translucent extends UploadableChunk<DrawableChunk.Translucent> {
        public Translucent(VertexPackingList packing, VertexCollectorList collectorList) {
            super(packing, collectorList);
        }

        @Override
        public DrawableChunk.Translucent produceDrawable() {
            final int limit = delegates.size();
            for (int i = 0; i < limit; i++)
                delegates.get(i).flush();
            return new DrawableChunk.Translucent(delegates);
        }
    }
}

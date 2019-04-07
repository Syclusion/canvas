/*******************************************************************************
 * Copyright 2019 grondag
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
 ******************************************************************************/

package grondag.canvas.buffering;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.GlStateManager;

import grondag.canvas.core.ConditionalPipeline;
import grondag.canvas.core.PipelineVertexFormat;
import grondag.canvas.opengl.CanvasGlHelper;
import grondag.canvas.opengl.VaoStore;
import net.minecraft.client.render.VertexFormatElement;

public class DrawableDelegate {
    private static final ArrayBlockingQueue<DrawableDelegate> store = new ArrayBlockingQueue<DrawableDelegate>(4096);
    
    public static DrawableDelegate claim(AbstractBufferDelegate<?> bufferDelegate, ConditionalPipeline pipeline, int vertexCount) {
        DrawableDelegate result = store.poll();
        if(result == null) {
            result = new DrawableDelegate();
        }
        result.bufferDelegate = bufferDelegate;
        result.conditionalPipeline = pipeline;
        result.vertexCount = vertexCount;
        result.isReleased = false;
        result.vertexBinder = bufferDelegate.isVbo() 
                ? (CanvasGlHelper.isVaoEnabled() ? result::bindVao : result::bindVbo)
                : result::bindBuffer;
        bufferDelegate.retain(result);
        return result;
    }

    private AbstractBufferDelegate<?> bufferDelegate;
    private ConditionalPipeline conditionalPipeline;
    private int vertexCount;
    private boolean isReleased = false;
    private Consumer<PipelineVertexFormat> vertexBinder;
    
    /**
     * VAO Buffer name if enabled and initialized.
     */
    private int vaoBufferId = -1;

    private DrawableDelegate() {
        super();
    }

    public AbstractBufferDelegate<?> bufferDelegate() {
        return this.bufferDelegate;
    }

    /**
     * Instances that share the same GL buffer will have the same ID. Allows sorting
     * in solid layer to avoid rebinding buffers for draws that will have the same
     * vertex buffer and pipeline/format.
     */
    public int bufferId() {
        return this.bufferDelegate.glBufferId();
    }

    /**
     * The pipeline (and vertex format) associated with this delegate.
     */
    public ConditionalPipeline getPipeline() {
        return this.conditionalPipeline;
    }

    /**
     * Won't bind buffer if this buffer same as last - will only do vertex
     * attributes. Returns the buffer Id that is bound, or input if unchanged.
     */
    public int bind(int lastBufferId) {
        if (this.bufferDelegate.isDisposed())
            return lastBufferId;

        if (this.bufferDelegate.glBufferId() != lastBufferId) {
            this.bufferDelegate.bind();
            lastBufferId = this.bufferDelegate.glBufferId();
        }

        this.vertexBinder.accept(conditionalPipeline.pipeline.piplineVertexFormat());
        
        return lastBufferId;
    }

    /**
     * Assumes pipeline has already been activated and buffer has already been bound
     * via {@link #bind()}
     */
    public void draw() {
        assert !isReleased;

        if (this.bufferDelegate.isDisposed())
            return;

        GlStateManager.drawArrays(GL11.GL_QUADS, 0, vertexCount);
    }

    public void release() {
        if (!isReleased) {
            isReleased = true;
            bufferDelegate.release(this);
            if (vaoBufferId != -1) {
                VaoStore.releaseVertexArray(vaoBufferId);
                vaoBufferId = -1;
            }
            bufferDelegate = null;
            conditionalPipeline =  null;
            store.offer(this);
        }
    }

    public void flush() {
        assert !isReleased;
        this.bufferDelegate.flush();
    }
    
    void bindVao(PipelineVertexFormat format) {
        if (vaoBufferId == -1) {
            vaoBufferId = VaoStore.claimVertexArray();
            CanvasGlHelper.glBindVertexArray(vaoBufferId);
            GlStateManager.enableClientState(GL11.GL_VERTEX_ARRAY);
            CanvasGlHelper.enableAttributesVao(format.attributeCount);
            GlStateManager.vertexPointer(3, VertexFormatElement.Format.FLOAT.getGlId(), format.vertexStrideBytes, bufferDelegate.byteOffset());
            format.bindAttributeLocations(bufferDelegate.byteOffset());
        } else {
            CanvasGlHelper.glBindVertexArray(vaoBufferId);
        }
    }
    
    void bindVbo(PipelineVertexFormat format) {
        GlStateManager.vertexPointer(3, VertexFormatElement.Format.FLOAT.getGlId(), format.vertexStrideBytes, bufferDelegate.byteOffset());
        format.enableAndBindAttributes(bufferDelegate.byteOffset());
    }

    void bindBuffer(PipelineVertexFormat format) {
        ByteBuffer buffer = bufferDelegate.buffer.byteBuffer();
        final int baseOffset = bufferDelegate.byteOffset();
        buffer.position(baseOffset);
        GlStateManager.enableClientState(GL11.GL_VERTEX_ARRAY);
        GlStateManager.vertexPointer(3, VertexFormatElement.Format.FLOAT.getGlId(), format.vertexStrideBytes, buffer);
        format.enableAndBindAttributes(buffer, baseOffset);
    }
}

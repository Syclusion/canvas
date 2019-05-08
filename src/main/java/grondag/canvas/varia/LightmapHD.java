package grondag.canvas.varia;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.lwjgl.opengl.GL21;

import grondag.canvas.Canvas;
import grondag.canvas.apiimpl.QuadViewImpl;
import grondag.canvas.apiimpl.util.AoFaceData;
import net.minecraft.util.math.MathHelper;

public class LightmapHD {
    public static final int TEX_SIZE = 2048;
    private static final int LIGHTMAP_SIZE = 4;
    public static final int PADDED_SIZE = LIGHTMAP_SIZE + 2;
    private static final int LIMIT_INCLUSIVE = LIGHTMAP_SIZE - 1;
    private static final int RADIUS = LIGHTMAP_SIZE / 2;
    private static final int PADDED_RADIUS = PADDED_SIZE / 2;
    private static final int LIGHTMAP_PIXELS = PADDED_SIZE * PADDED_SIZE;
    private static final int IDX_SIZE = TEX_SIZE / PADDED_SIZE;
    private static final int MAX_COUNT = IDX_SIZE * IDX_SIZE;
    // UGLY - consider making this a full unsigned short
    // for initial pass didn't want to worry about signed value mistakes
    /** Scale of texture units sent to shader. Shader should divide by this. */
    private static final int BUFFER_SCALE = 0x8000;
    private static final float TEXTURE_TO_BUFFER = (float) BUFFER_SCALE / TEX_SIZE;
    
    private static final LightmapHD[] maps = new LightmapHD[MAX_COUNT];
    
    private static final AtomicInteger nextIndex = new AtomicInteger();
    
    public static void forceReload() {
        nextIndex.set(0);
        MAP.clear();
    }
    
    public static void forEach(Consumer<LightmapHD> consumer) {
        final int limit = Math.min(MAX_COUNT, nextIndex.get());
        for(int i = 0; i < limit; i++) {
            consumer.accept(maps[i]);
        }
    }
    
    private static class Key {
        private int[] light = new int[LIGHTMAP_PIXELS];
        private int hashCode;
        
        Key() {
        }

        Key(int[] light) {
            System.arraycopy(light, 0, this.light, 0, LIGHTMAP_PIXELS);
            computeHash();
        }
        
        /**
         * Call after mutating {@link #light}
         */
        void computeHash() {
            this.hashCode = Arrays.hashCode(light);
        }
        
        @Override
        public boolean equals(Object other) {
            if(other == null || !(other instanceof Key)) {
                return false;
            }
            int[] otherLight = ((Key)other).light;
            return Arrays.equals(light, otherLight);
        }
        
        @Override
        public int hashCode() {
            return hashCode;
        }
    }
    
    static final ThreadLocal<Key> TEMPLATES = ThreadLocal.withInitial(Key::new);
    
    static final ConcurrentHashMap<Key, LightmapHD> MAP = new ConcurrentHashMap<>();
    
    public static LightmapHD findBlock(AoFaceData faceData) {
        return find(faceData, LightmapHD::mapBlock);
    }
    
    public static LightmapHD findSky(AoFaceData faceData) {
        return find(faceData, LightmapHD::mapSky);
    }
    
    private static void mapBlock(AoFaceData faceData, int[] search) {
        /** v - 1 */
        final float top = input(faceData.light1);
        /** v + 1 */
        final float bottom = input(faceData.light0);
        /** u + 1 */
        final float right = input(faceData.light3);
        /** u - 1 */
        final float left = input(faceData.light2);
        
        final float topLeft = input(faceData.cLight2);
        final float topRight = input(faceData.cLight3);
        final float bottomRight = input(faceData.cLight1);
        final float bottomLeft = input(faceData.cLight0);
        
        final float center = input(faceData.lightCenter);

        compute(search, center, top, bottom, right, left, topLeft, topRight, bottomRight, bottomLeft);
    }
    
    private static LightmapHD find(AoFaceData faceData, BiConsumer<AoFaceData, int[]> mapper) {
        Key key = TEMPLATES.get();
        int[] search = key.light;
        
        mapper.accept(faceData, search);

        key.computeHash();
        
        LightmapHD result = MAP.get(key);
        
        if(result == null) {
            // create new key object to avoid putting threadlocal into map
            key = new Key(search);
            result = MAP.computeIfAbsent(key, k -> new LightmapHD(k.light));
        }
        
        return result;
    }
    
    private static float input(int b) {
        b &= 0xFF;
        if(b > 240) {
            b = 240;
        }
        return b / 16f;
    }
    
    private static void mapSky(AoFaceData faceData, int[] search) {
        /** v - 1 */
        final float top = input(faceData.light1 >>> 16);
        /** v + 1 */
        final float bottom = input(faceData.light0 >>> 16);
        /** u + 1 */
        final float right = input(faceData.light3 >>> 16);
        /** u - 1 */
        final float left = input(faceData.light2 >>> 16);
        
        final float topLeft = input(faceData.cLight2 >>> 16);
        final float topRight = input(faceData.cLight3 >>> 16);
        final float bottomRight = input(faceData.cLight1 >>> 16);
        final float bottomLeft = input(faceData.cLight0 >>> 16);
        
        final float center = input(faceData.lightCenter >>> 16);
        
        //TODO: remove
//      if(center == 15 && top == 15 && bottom == 15 && left == 15 && right == 15 && topLeft == 15 && topRight == 15 
//              && bottomLeft == 15 && bottomRight == 15) {
//          System.out.println("boop");
//      }
      
        compute(search, center, top, bottom, right, left, topLeft, topRight, bottomRight, bottomLeft);
    }
    
    public final int uMinImg;
    public final int vMinImg;
    private final int[] light;
    
    private LightmapHD(int[] light) {
        final int index = nextIndex.getAndIncrement();
        final int s = index % IDX_SIZE;
        final int t = index / IDX_SIZE;
        uMinImg = s * PADDED_SIZE;
        vMinImg = t * PADDED_SIZE;
        this.light = new int[LIGHTMAP_PIXELS];
        System.arraycopy(light, 0, this.light, 0, LIGHTMAP_PIXELS);
        
        if(index >= MAX_COUNT) {
            //TODO: put back
            //assert false : "Out of lightmap space.";
            Canvas.LOG.info("Out of lightmap space for index = " + index);
            return;
        }
        
        maps[index] = this;
        
        SmoothLightmapTexture.instance().setDirty();
    }
    
    private static void compute(int[] light, float center, 
            float top, float bottom, float right, float left,
            float topLeft, float topRight, float bottomRight, float bottomLeft) {

        for(int i = -1; i < RADIUS; i++) {
            for(int j = -1; j < RADIUS; j++) {
                //PERF save calcs
                light[index(i, j)] = output(inside(center, left, top, topLeft, i, j));
                light[index(RADIUS + 1 + i, j)] = output(inside(center, right, top, topRight, RADIUS + 1 + i, j));
                light[index(RADIUS + 1 + i, RADIUS + 1 + j)] = output(inside(center, right, bottom, bottomRight, RADIUS + 1 + i, RADIUS + 1 + j));
                light[index(i, RADIUS + 1 + j)] = output(inside(center, left, bottom, bottomLeft, i, RADIUS + 1 + j));
            }
        }
        
        //TODO: remove
//      if(center == 15 && bottom == 15 && top == 15 && left == 15 && right == 15 
//              && topLeft == 15 && topRight == 15 && bottomLeft == 15 && bottomRight == 15) {
//          for(int i : light) {
//              if(i != 248)
//              System.out.println("boop");
//          }
//      }
    }
    
    private static float pclamp(float in) {
        return in < 0f ? 0f : in;
    }
    
    public static int index(int u, int v) {
        return (v + 1) * PADDED_SIZE + u + 1;
    }
    
    private static int output(float in) {
        int result = Math.round(in * 17f);
        
        if(result < 0) {
            result = 0;
        } else if(result > 255) {
            result = 255;
        }
        return result;
    }
    
    /**
     * Handles padding
     */
    public int pixel(int u, int v) {
        return light[v * PADDED_SIZE + u];
    }
    
    public int coord(QuadViewImpl q, int i) {
        //PERF could compress coordinates sent to shader by 
        // sending lightmap/shademap index with same uv for each
        // would probably save 2 bytes - send 2 + 3 shorts instead of 6
        // or put each block/sky/ao combination in a texture and send 4 shorts...
        // 2 for uv and 2 to lookup the combinations
//        float u = uMinImg + 0.5f + q.u[i] * (LIGHTMAP_SIZE - 1);
//        float v = vMinImg + 0.5f + q.v[i] * (LIGHTMAP_SIZE - 1);
        int u = Math.round((uMinImg + 1) * TEXTURE_TO_BUFFER  + q.u[i] * (LIGHTMAP_SIZE * TEXTURE_TO_BUFFER));
        int v = Math.round((vMinImg + 1) * TEXTURE_TO_BUFFER  + q.v[i] * (LIGHTMAP_SIZE * TEXTURE_TO_BUFFER));
        
        
        //TODO: config option to show whole texture
//        int u = Math.round((uMinImg) * TEXTURE_TO_BUFFER  + q.u[i] * (PADDED_SIZE * TEXTURE_TO_BUFFER));
//        int v = Math.round((vMinImg) * TEXTURE_TO_BUFFER  + q.v[i] * (PADDED_SIZE * TEXTURE_TO_BUFFER));
        
        return u | (v << 16);
        //TODO: remove
//        return Math.round(q.u[i] * 32768) | ((Math.round(q.v[i] * 32768) << 16));
    }
    
    private static float max(float a, float b, float c, float d) {
        return Math.max(Math.max(a, b), Math.max(c, d));
    }
    
    private static float inside(float self, float uVal, float vVal, float cornerVal, int u, int v) {
        if(self == uVal && self == vVal && self == cornerVal) {
            return cornerVal;
        }
        int du = pixelDist(u);
        int dv = pixelDist(v);
        float selfFact = distUV(u, v);
        float uFact = distRadius(CELL_DISTANCE - du, dv);
        float vFact = distRadius(du, CELL_DISTANCE - dv);
        float cornerFact = distRadius(CELL_DISTANCE - du, CELL_DISTANCE - dv);
        float radial = max(self - selfFact, pclamp(uVal - uFact), pclamp(vVal - vFact), pclamp(cornerVal - cornerFact));
        
        
        float nz = nonZeroMin(self, uVal, vVal, cornerVal);
        if(self == 0) self = nz;
        if(cornerVal == 0) cornerVal = nz;
        if(uVal == 0) uVal = nz;
        if(vVal == 0) vVal = nz;
        
        float uLinear = 1f - (du + 0.5f) / LIGHTMAP_SIZE;
        float vLinear = 1f - (dv + 0.5f) / LIGHTMAP_SIZE;
        assert uLinear >= 0 && uLinear <= 1f;
        assert vLinear >= 0 && vLinear <= 1f;
        float linear = self * (uLinear * vLinear) 
                + cornerVal * (1 - uLinear) * (1 - vLinear)
                + uVal * ((1 - uLinear) * (vLinear))
                + vVal * ((uLinear) * (1 - vLinear));
//        float vol = (uLinear * vLinear) 
//                + (1 - uLinear) * (1 - vLinear)
//                + ((1 - uLinear) * (vLinear))
//                + ((uLinear) * (1 - vLinear));

        //TODO: remove
//        if(self == 15 && uVal == 15 && vVal == 15 && cornerVal == 15) {
//        if(self == 15 && (uVal == 14 || vVal == 14)) {
//            System.out.println("boop");
//        }
        return Math.max(radial, linear);
//        return linear;
    }
    
    static final int CELL_DISTANCE = RADIUS * 2 - 1;
    static final float INVERSE_CELL_DISTANCE = 1f / CELL_DISTANCE;
    
    private static int pixelDist(int c) {
        return c >= RADIUS ? c - RADIUS : RADIUS - 1 - c;
    }
    
    private static float distUV(int u, int v) {
        return distRadius(pixelDist(u), pixelDist(v));
    }
    
    private static float distRadius(int uRadius, int vRadius) {
        return MathHelper.sqrt((uRadius * uRadius + vRadius * vRadius)) * INVERSE_CELL_DISTANCE;
    }
    
    private static float nonZeroMin(float a, float b) {
        if(a == 0) return b;
        if(b == 0) return a;
        return Math.min(a, b);
    }
    
    private static float nonZeroMin(float a, float b, float c, float d) {
        return nonZeroMin(nonZeroMin(a, b), nonZeroMin(c, d));
    }
}

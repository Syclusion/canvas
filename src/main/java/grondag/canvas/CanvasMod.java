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

package grondag.canvas;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import grondag.canvas.apiimpl.Canvas;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;

//FIX: lighting on dodecs (again)
//TODO: configurable disable chunk matrix
//TODO: configurable compact vertex formats - GPU side  white, face, unlit, pixel-aligned, etc.
//TODO: configurable cutout single pass, separate pass may give early cull in solid
//FEAT: configurable compressed vertex formats - CPU side (maybe wait for Brocade Mesh)
//FEAT: per chunk occlusion mesh - for sky shadow mask
//FEAT: per chunk depth mesh - addendum to occlusion mesh to render for depth pass - includes translucent cutout
//FEAT: first person dynamic light
//PERF: improve light smoothing performance
//PERF: manage buffers to avoid heap fragmentation


public class CanvasMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Configurator.init();
        RendererAccess.INSTANCE.registerRenderer(Canvas.INSTANCE);
    }

    public static final String MODID = "canvas";
    
    public static final Logger LOG = LogManager.getLogger("Canvas");
}

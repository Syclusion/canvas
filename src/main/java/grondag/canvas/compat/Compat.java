/*
 * This file is part of Canvas Renderer and is licensed to the project under
 * terms that are compatible with the GNU Lesser General Public License.
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership and licensing.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package grondag.canvas.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;

import io.vram.frex.api.renderloop.DebugRenderListener;
import io.vram.frex.api.renderloop.EntityRenderPostListener;
import io.vram.frex.api.renderloop.EntityRenderPreListener;
import io.vram.frex.api.renderloop.FrustumSetupListener;
import io.vram.frex.api.renderloop.RenderReloadListener;
import io.vram.frex.api.renderloop.TranslucentPostListener;
import io.vram.frex.api.renderloop.WorldRenderLastListener;
import io.vram.frex.api.renderloop.WorldRenderPostListener;
import io.vram.frex.api.renderloop.WorldRenderStartListener;

import grondag.canvas.render.world.CanvasWorldRenderer;

public class Compat {
	private Compat() { }

	public static void init() {
		WorldRenderStartListener.register(ctx -> {
			LambDynLightsHolder.updateAll.accept(ctx.worldRenderer());
			NEAHolder.worldRenderStart();
		});

		FrustumSetupListener.register(ctx -> {
			FlywheelHolder.handler.beginFrame(ctx.world(), ctx.camera(), ctx.frustum());
			LitematicaHolder.litematicaTerrainSetup.accept(ctx.frustum());
		});

		EntityRenderPreListener.register(ctx -> {
			FlywheelHolder.handler.renderLayer(ctx, RenderType.solid());
			FlywheelHolder.handler.renderLayer(ctx, RenderType.cutoutMipped());
			FlywheelHolder.handler.renderLayer(ctx, RenderType.cutout());

			LitematicaHolder.litematicaRenderSolids.accept(ctx.poseStack(), ctx.projectionMatrix());
			//SatinHolder.beforeEntitiesRenderEvent.beforeEntitiesRender(ctx.camera(), ctx.frustum(), ctx.tickDelta());
		});

		EntityRenderPostListener.register(ctx -> {
			GOMLHolder.HANDLER.render(ctx);
			CampanionHolder.HANDLER.render(ctx);
			//SatinHolder.onEntitiesRenderedEvent.onEntitiesRendered(ctx.camera(), ctx.frustum(), ctx.tickDelta());

			// Expects an identity matrix stack
			LitematicaHolder.litematicaEntityHandler.handle(ctx.poseStack(), ctx.tickDelta());

			DynocapsHolder.handler.render(ctx.profiler(), ctx.poseStack(), (BufferSource) ctx.consumers(), ctx.camera().getPosition());
		});

		DebugRenderListener.register(ctx -> {
			//ClothHolder.clothDebugPreEvent.run();
			BborHolder.render(ctx);
		});

		TranslucentPostListener.register(ctx -> {
			FlywheelHolder.handler.renderLayer(ctx, RenderType.translucent());

			JustMapHolder.justMapRender.renderWaypoints(ctx.poseStack(), ctx.camera(), ctx.tickDelta());
			LitematicaHolder.litematicaRenderTranslucent.accept(ctx.poseStack(), ctx.projectionMatrix());
			LitematicaHolder.litematicaRenderOverlay.accept(ctx.poseStack(), ctx.projectionMatrix());
			final Vec3 cameraPos = ctx.camera().getPosition();
			VoxelMapHolder.postRenderLayerHandler.render(ctx.worldRenderer(), RenderType.translucent(), ctx.poseStack(), cameraPos.x(), cameraPos.y(), cameraPos.z());

			// litematica overlay uses fabulous buffers so must run before translucent shader when active
			// It expects view matrix to be pre-applied because it normally happens in weather render
			if (ctx.advancedTranslucency()) {
				// The view matrix is one pop away
				ctx.poseStack().popPose();
				MaliLibHolder.maliLibRenderWorldLast.render(ctx.poseStack(), ctx.projectionMatrix(), Minecraft.getInstance());
				ctx.poseStack().pushPose();
				ctx.poseStack().setIdentity();
			}
		});

		WorldRenderLastListener.register(ctx -> {
			BborHolder.deferred();
			//SatinHolder.onWorldRenderedEvent.onWorldRendered(ctx.matrixStack(), ctx.camera(), ctx.tickDelta(), ctx.limitTime());

			// litematica overlay expects to render on top of translucency when fabulous is off
			if (!ctx.advancedTranslucency()) {
				MaliLibHolder.maliLibRenderWorldLast.render(ctx.poseStack(), ctx.projectionMatrix(), Minecraft.getInstance());
			}
		});

		WorldRenderPostListener.register(ctx -> {
			VoxelMapHolder.postRenderHandler.render(ctx.worldRenderer(), ctx.poseStack(), ctx.tickDelta(), ctx.limitTime(), ctx.blockOutlines(), ctx.camera(), ctx.gameRenderer(), ctx.lightmapTexture(), ctx.projectionMatrix());
		});

		RenderReloadListener.register(() -> {
			LitematicaHolder.litematicaReload.run();
			FlywheelHolder.handler.refresh(CanvasWorldRenderer.instance().worldRenderState.getWorld());
		});
	}
}

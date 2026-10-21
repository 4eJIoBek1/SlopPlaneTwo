/*
 * Adapted from The MIT License (MIT)
 *
 * Copyright (c) 2020-2025 DaPorkchop_
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software
 * is furnished to do so, subject to the following conditions:
 *
 * Any persons and/or organizations using this software must include the above copyright notice and this permission notice,
 * provide sufficient credit to the original authors of the project (IE: DaPorkchop_), as well as provide a link to the original project.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.daporkchop.fp2.impl.mc.forge1_12_2.asm.core.client.renderer;

import lombok.val;
import net.daporkchop.fp2.common.util.DirectBufferHackery;
import net.daporkchop.fp2.core.client.FP2Client;
import net.daporkchop.fp2.core.client.MatrixHelper;
import net.daporkchop.fp2.core.client.player.IFarPlayerClient;
import net.daporkchop.fp2.core.client.render.state.CameraState;
import net.daporkchop.fp2.core.client.render.state.DrawState;
import net.daporkchop.fp2.core.config.FP2Config;
import net.daporkchop.fp2.core.engine.api.ctx.IFarClientContext;
import net.daporkchop.fp2.core.engine.client.AbstractFarRenderer;
import net.daporkchop.fp2.core.util.GlobalAllocators;
import net.daporkchop.fp2.impl.mc.forge1_12_2.asm.at.client.ATMinecraft1_12;
import net.daporkchop.fp2.impl.mc.forge1_12_2.asm.interfaz.client.network.IMixinEntityRenderer1_12;
import net.daporkchop.lib.common.pool.array.ArrayAllocator;
import net.daporkchop.lib.unsafe.PUnsafe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import org.lwjgl.BufferUtils;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;

import static net.daporkchop.fp2.common.util.TypeSize.*;
import static net.daporkchop.fp2.core.FP2Core.*;
import static net.daporkchop.fp2.impl.mc.forge1_12_2.compat.of.OFHelper1_12.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;

/**
 * @author DaPorkchop_
 */
@Mixin(EntityRenderer.class)
abstract class MixinEntityRenderer1_12 implements IMixinEntityRenderer1_12 {
    @Shadow
    @Final
    private Minecraft mc;

    @Shadow
    private float farPlaneDistance;

    @Unique
    private final FloatBuffer fp2_tempMatrix = BufferUtils.createFloatBuffer(MatrixHelper.MAT4_ELEMENTS);

    @Unique
    private final CameraState fp2_cameraState = new CameraState();

    @Inject(method = "Lnet/minecraft/client/renderer/EntityRenderer;renderWorldPass(IFJ)V",
            at = @At("HEAD"))
    private void fp2_renderWorldPass_pre(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        if (OF && of_Config_isShaders()) {
            //shader packs don't get along with our reversed-Z projection, as they read the depth buffer assuming the standard
            //  depth range. the far terrain is therefore rendered with a standard projection and ends up in the shader pack's
            //  g-buffer along with the rest of the world, where the pack's own shader programs will process it.
            return;
        }

        val reversedZ = fp2().client().renderManager().reversedZ();
        if (reversedZ != null) {
            reversedZ.activate();
        }
    }

    @Inject(method = "Lnet/minecraft/client/renderer/EntityRenderer;renderWorldPass(IFJ)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/ITextureObject;restoreLastBlurMipmap()V",
                    shift = At.Shift.AFTER,
                    ordinal = 1),
            require = 1, allow = 1)
    private void fp2_renderWorldPass_doFP2Render(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        //directly after vanilla CUTOUT pass

        fp2().client().currentPlayer().ifPresent(player -> {
            IFarClientContext context = player.activeContext();
            if (context == null) {
                return;
            }

            AbstractFarRenderer renderer = context.renderer();
            if (renderer != null && renderer.needsReloadForShaderPackChange()) {
                //shader pack compatibility requires a render index implementation which draws each tile individually, but the render index is selected when
                //  the renderer is created. recreate the renderer here (outside of rendering, as reloading closes the renderer!) to switch between the
                //  normal and shader pack compatible render paths when the shader pack is enabled or disabled.
                context.reloadRenderer();
                renderer = context.renderer();
            }
            if (renderer == null) {
                return;
            }

            this.mc.profiler.startSection("fp2_render");

            GlStateManager.disableAlpha();
            ((ATMinecraft1_12) this.mc).getTextureMapBlocks().setBlurMipmapDirect(false, this.mc.gameSettings.mipmapLevels > 0);

            //actually render stuff!
            //TODO: we assume that the camera state hasn't changed... maybe we should add an assertion?
            renderer.render(this.fp2_cameraState, this.fp2_drawState());

            ((ATMinecraft1_12) this.mc).getTextureMapBlocks().restoreLastBlurMipmap();
            GlStateManager.enableAlpha();

            this.mc.profiler.endSection();
        });
    }

    @Unique
    private void fp2_cameraState(CameraState cameraState, FP2Client client, float partialTicks) {
        //ModelViewProjection matrix
        ArrayAllocator<float[]> alloc = GlobalAllocators.ALLOC_FLOAT.get();

        float[] modelView = alloc.exactly(MatrixHelper.MAT4_ELEMENTS);
        float[] projection = alloc.exactly(MatrixHelper.MAT4_ELEMENTS);
        try {
            //load both matrices into arrays
            glGetFloat(GL_MODELVIEW_MATRIX, (FloatBuffer) this.fp2_tempMatrix.clear());
            this.fp2_tempMatrix.get(modelView);
            glGetFloat(GL_PROJECTION_MATRIX, (FloatBuffer) this.fp2_tempMatrix.clear());
            this.fp2_tempMatrix.get(projection);

            //configure the camera state
            cameraState.setModelViewMatrixAndProjectionMatrix(modelView, projection, client);

            //zNear is already set by fp2_$everything$_dontUseGluPerspective(), we assume it hasn't changed
        } finally {
            alloc.release(projection);
            alloc.release(modelView);
        }

        { //position
            Entity entity = this.mc.getRenderViewEntity();
            double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
            double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
            double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;
            cameraState.positionDouble(x, y, z);
        }
    }

    @Unique
    private DrawState fp2_drawState() {
        DrawState drawState = new DrawState();

        //optifine compatibility: disable fog if it's turned off, because optifine only does this itself if no vanilla terrain is being rendered
        //  (e.g. it's all being discarded in frustum culling)
        if (OF && (PUnsafe.getInt(this.mc.gameSettings, OF_FOGTYPE_OFFSET) == OF_OFF && PUnsafe.getBoolean(this.mc.entityRenderer, OF_ENTITYRENDERER_FOGSTANDARD_OFFSET))) {
            GlStateManager.disableFog();
        }

        { //fog
            this.fp2_initFogColor(drawState);

            drawState.fogMode = glGetBoolean(GL_FOG) ? DrawState.FogMode.fromGlName(glGetInteger(GL_FOG_MODE)) : DrawState.FogMode.DISABLED;
            drawState.fogDensity = glGetFloat(GL_FOG_DENSITY);
            drawState.fogStart = glGetFloat(GL_FOG_START);
            drawState.fogEnd = glGetFloat(GL_FOG_END);
        }

        { //misc GL state
            drawState.alphaRefCutout = 0.1f;
        }

        return drawState;
    }

    @Unique
    private void fp2_initFogColor(DrawState drawState) {
        //buffer needs to fit 16 elements, but only the first 4 will be used
        long addr = PUnsafe.allocateMemory(16 * FLOAT_SIZE);
        try {
            FloatBuffer buffer = DirectBufferHackery.wrapFloat(addr, 16);
            glGetFloat(GL_FOG_COLOR, buffer);

            drawState.fogColorR = buffer.get(0);
            drawState.fogColorG = buffer.get(1);
            drawState.fogColorB = buffer.get(2);
            drawState.fogColorA = buffer.get(3);
        } finally {
            PUnsafe.freeMemory(addr);
        }
    }

    //use reversed-z projection with infinite zFar everywhere

    @Redirect(method = "*",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V"))
    private void fp2_$everything$_dontUseGluPerspective(float fov, float aspect, float zNear, float zFar) {
        //save the near clipping plane distance (should always be 0.05f)
        assert zNear == 0.05f : zNear;
        this.fp2_cameraState.zNear = zNear;

        if (OF && of_Config_isShaders()) {
            //a shader pack is active: it assumes a standard depth range with a finite far plane and derives all of its
            //  depth-based effects (fog, water, SSAO, sky detection, ...) from the projection matrix, so we use vanilla's
            //  projection. this is the exact matrix Project.gluPerspective() would have written, but we build it by hand
            //  instead of calling the original method, because this redirect is applied to every method in EntityRenderer -
            //  calling it from within the handler would redirect our own call (i.e. recurse until the stack overflows).
            FloatBuffer dst = (FloatBuffer) this.fp2_tempMatrix.clear();
            for (int i = 0; i < MatrixHelper.MAT4_ELEMENTS; i++) {
                dst.put(i, 0.0f);
            }

            //these are the exact matrix values written by Project.gluPerspective(fov, aspect, zNear, zFar)
            float f = (float) (1.0d / Math.tan(Math.toRadians(fov) * 0.5d));
            float deltaZ = zFar - zNear;
            dst.put(0, f / aspect);
            dst.put(5, f);
            dst.put(10, -(zFar + zNear) / deltaZ);
            dst.put(11, -1.0f); //m[3][2]
            dst.put(14, -2.0f * zNear * zFar / deltaZ); //m[2][3]
            glMultMatrix(dst);
            return;
        }

        MatrixHelper.reversedZ((FloatBuffer) this.fp2_tempMatrix.clear(), fov, aspect, zNear);
        glMultMatrix(this.fp2_tempMatrix);
    }

    //set farPlaneDistance to the value in config

    @Redirect(method = "Lnet/minecraft/client/renderer/EntityRenderer;setupCameraTransform(FI)V",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;farPlaneDistance:F",
                    opcode = Opcodes.PUTFIELD))
    private void fp2_setupCameraTransform_increaseFarPlaneDistance(EntityRenderer renderer, float farPlaneDistance) {
        IFarClientContext context = fp2().client().currentPlayer().map(IFarPlayerClient::activeContext).orElse(null);
        if (context != null) {
            FP2Config config = context.config();
            farPlaneDistance = config.effectiveRenderDistanceBlocks();
            //TODO: i need a better system for computing this

            if (OF && of_Config_isShaders()) {
                //a shader pack is active: the configured render distance is far larger than the far terrain actually extends,
                //  and a shader pack's depth buffer would be useless if its far plane were placed beyond it (everything except
                //  the few blocks closest to the camera would end up squeezed into the last depth values). we therefore clamp
                //  the far plane to a distance which is comfortably larger than any far terrain level containing actual data.
                farPlaneDistance = Math.min(farPlaneDistance, 16384.0f);
            }
        }

        this.farPlaneDistance = farPlaneDistance;
    }

    @Redirect(method = "Lnet/minecraft/client/renderer/EntityRenderer;setupFog(IF)V",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;farPlaneDistance:F"))
    private float fp2_setupFog_lowerFogFarPlaneDistanceForSkyboxRendering(EntityRenderer entityRenderer, int startCoords, float partialTicks) {
        float farPlaneDistance = this.farPlaneDistance;
        if (startCoords == -1) {
            //render the skybox with vanilla's own fog distance so that it ends up looking exactly like vanilla. if the fog is
            // allowed to reach further, the edges of the sky plane don't get hidden by the fog anymore and show up as sharp
            // borders in the sky
            farPlaneDistance = Math.min(farPlaneDistance, this.mc.gameSettings.renderDistanceChunks * 16);
        }
        return farPlaneDistance;
    }

    //optifine changes this value for us, but we need to manually change it if optifine isn't around

    @ModifyConstant(method = "Lnet/minecraft/client/renderer/EntityRenderer;enableLightmap()V",
            constant = @Constant(intValue = GL_CLAMP),
            allow = 2)
    private int fp2_enableLightmap_setLightmapEdgeClampModeToEmulateOptifine(int value) {
        return GL_CLAMP_TO_EDGE;
    }

    @Override
    public final CameraState fp2_cameraState() {
        return this.fp2_cameraState;
    }

    @Override
    public final void fp2_captureCameraState(float partialTicks) {
        this.fp2_cameraState(this.fp2_cameraState, fp2().client(), partialTicks);
    }
}

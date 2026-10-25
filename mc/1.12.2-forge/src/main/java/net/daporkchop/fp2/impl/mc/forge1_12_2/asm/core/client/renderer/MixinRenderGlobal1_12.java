/*
 * Adapted from The MIT License (MIT)
 *
 * Copyright (c) 2020-2024 DaPorkchop_
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

import net.daporkchop.fp2.core.client.IFrustum;
import net.daporkchop.fp2.core.client.render.ShaderPackCompat;
import net.daporkchop.fp2.core.engine.client.AbstractFarRenderer;
import net.daporkchop.fp2.core.engine.api.ctx.IFarClientContext;
import net.daporkchop.fp2.impl.mc.forge1_12_2.asm.at.client.ATMinecraft1_12;
import net.daporkchop.fp2.impl.mc.forge1_12_2.asm.interfaz.client.network.IMixinEntityRenderer1_12;
import net.daporkchop.fp2.impl.mc.forge1_12_2.asm.interfaz.client.renderer.IMixinRenderGlobal1_12;
import net.daporkchop.fp2.impl.mc.forge1_12_2.client.TerrainRenderingBlockedTracker1_12;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static net.daporkchop.fp2.core.FP2Core.*;
import static net.daporkchop.fp2.impl.mc.forge1_12_2.compat.of.OFHelper1_12.*;
import static net.daporkchop.lib.common.util.PorkUtil.*;

/**
 * @author DaPorkchop_
 */
@Mixin(RenderGlobal.class)
abstract class MixinRenderGlobal1_12 implements IMixinRenderGlobal1_12 {
    @Shadow
    private WorldClient world;

    @Shadow
    @Final
    private Minecraft mc;

    @Unique
    private TerrainRenderingBlockedTracker1_12 fp2_vanillaRenderabilityTracker;

    @Inject(method = "Lnet/minecraft/client/renderer/RenderGlobal;setWorldAndLoadRenderers(Lnet/minecraft/client/multiplayer/WorldClient;)V",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;viewFrustum:Lnet/minecraft/client/renderer/ViewFrustum;",
                    opcode = Opcodes.PUTFIELD,
                    shift = At.Shift.AFTER),
            require = 1)
    private void fp2_loadRenderers_onDestroyViewFrustum(CallbackInfo ci) {
        this.fp2_vanillaRenderabilityTracker.close();
        this.fp2_vanillaRenderabilityTracker = null;
    }

    @Inject(method = "Lnet/minecraft/client/renderer/RenderGlobal;loadRenderers()V",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;viewFrustum:Lnet/minecraft/client/renderer/ViewFrustum;",
                    opcode = Opcodes.PUTFIELD,
                    shift = At.Shift.BEFORE),
            require = 1)
    private void fp2_loadRenderers_onCreateViewFrustum(CallbackInfo ci) {
        //only create a new renderability tracker if none existed before.
        //  we don't want multiple instances lying around!
        if (this.fp2_vanillaRenderabilityTracker == null) {
            this.fp2_vanillaRenderabilityTracker = new TerrainRenderingBlockedTracker1_12(fp2().client());
        }
    }

    @Inject(method = "Lnet/minecraft/client/renderer/RenderGlobal;setupTerrain(Lnet/minecraft/entity/Entity;DLnet/minecraft/client/renderer/culling/ICamera;IZ)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/profiler/Profiler;endSection()V"),
            slice = @Slice(
                    from = @At(value = "INVOKE_STRING",
                            target = "Lnet/minecraft/profiler/Profiler;startSection(Ljava/lang/String;)V",
                            args = "ldc=iteration"),
                    to = @At(value = "INVOKE_STRING",
                            target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V",
                            args = "ldc=captureFrustum")),
            require = 1, allow = 1)
    private void fp2_setupTerrain_updateVanillaRenderability(Entity viewEntity, double partialTicks, ICamera camera, int frameCount, boolean playerSpectator, CallbackInfo ci) {
        this.mc.profiler.startSection("fp2_vanillaRenderabilityTracker_update");
        this.fp2_vanillaRenderabilityTracker.update(uncheckedCast(this), camera, frameCount);
        this.mc.profiler.endSection();
    }

    @Inject(method = "Lnet/minecraft/client/renderer/RenderGlobal;setupTerrain(Lnet/minecraft/entity/Entity;DLnet/minecraft/client/renderer/culling/ICamera;IZ)V",
            at = @At(value = "INVOKE_STRING",
                    target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V",
                    args = "ldc=captureFrustum"),
            require = 1, allow = 1)
    private void fp2_setupTerrain_prepare(Entity viewEntity, double partialTicks, ICamera camera, int frameCount, boolean playerSpectator, CallbackInfo ci) {
        //capture the camera state before using it. this has to happen here rather than in EntityRenderer.renderWorldPass(), because
        //  optifine's shader pipeline replaces the ICamera.setPosition() call with ShadersRender.setFrustrumPosition() (and the
        //  RenderGlobal.setupTerrain() call with its own ShadersRender.setupTerrain(), which delegates to this method), so a hook
        //  on the former would simply never run while a shader pack is active. at this point the matrices have been fully set up
        //  for terrain rendering in both cases.
        ((IMixinEntityRenderer1_12) this.mc.entityRenderer).fp2_captureCameraState((float) partialTicks);

        fp2().client().currentPlayer().ifPresent(player -> {
            IFarClientContext context = player.activeContext();
            AbstractFarRenderer renderer;
            if (context != null && (renderer = context.renderer()) != null) {
                this.mc.profiler.startSection("fp2_prepare");
                renderer.prepare(((IMixinEntityRenderer1_12) this.mc.entityRenderer).fp2_cameraState(), (IFrustum) camera);
                this.mc.profiler.endSection();
            }
        });
    }

    @Inject(method = "Lnet/minecraft/client/renderer/RenderGlobal;renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At(value = "HEAD"),
            require = 1, allow = 1)
    private void fp2_renderBlockLayer_beforeTranslucent(BlockRenderLayer layer, double partialTicks, int pass, Entity entityIn, CallbackInfoReturnable<Integer> cir) {
        if (layer != BlockRenderLayer.TRANSLUCENT || of_Shaders_isShadowPass()) {
            return;
        }

        //This is called from inside Optifine's water rendering phase: the shader pack's water shader program is active (and its deferred pass has already
        //  run), so this is where the far terrain's translucent pass has to be drawn. It has to happen before the vanilla translucent chunks are drawn, so
        //  that vanilla water ends up on top of far terrain water wherever they overlap.
        fp2().client().currentPlayer().ifPresent(player -> {
            IFarClientContext context = player.activeContext();
            AbstractFarRenderer renderer;
            if (context != null && (renderer = context.renderer()) != null) {
                renderer.renderShaderPackWater();
            }
        });

        if (!ShaderPackCompat.isActive()) {
            GlStateManager.depthMask(true);
        }
    }

    @Inject(method = "Lnet/minecraft/client/renderer/RenderGlobal;renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At(value = "RETURN"),
            require = 1, allow = 2)
    private void fp2_renderBlockLayer_afterTranslucent(BlockRenderLayer layer, double partialTicks, int pass, Entity entityIn, CallbackInfoReturnable<Integer> cir) {
        if (layer != BlockRenderLayer.TRANSLUCENT || of_Shaders_isShadowPass()) {
            return;
        }

        fp2().client().currentPlayer().ifPresent(player -> {
            IFarClientContext context = player.activeContext();
            AbstractFarRenderer renderer;
            if (context != null && (renderer = context.renderer()) != null) {
                this.mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
                ((ATMinecraft1_12) this.mc).getTextureMapBlocks().setBlurMipmapDirect(false, this.mc.gameSettings.mipmapLevels > 0);
                renderer.renderTranslucentPass();
                ((ATMinecraft1_12) this.mc).getTextureMapBlocks().restoreLastBlurMipmap();
            }
        });

        GlStateManager.depthMask(false);
    }

    @Override
    public TerrainRenderingBlockedTracker1_12 fp2_vanillaRenderabilityTracker() {
        return this.fp2_vanillaRenderabilityTracker;
    }
}

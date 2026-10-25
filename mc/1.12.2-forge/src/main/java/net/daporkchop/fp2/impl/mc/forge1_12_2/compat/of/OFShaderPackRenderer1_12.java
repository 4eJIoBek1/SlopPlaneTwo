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

package net.daporkchop.fp2.impl.mc.forge1_12_2.compat.of;

import net.daporkchop.fp2.core.client.render.ShaderPackCompat;
import net.daporkchop.fp2.core.engine.client.RenderConstants;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL20;

import static net.daporkchop.fp2.impl.mc.forge1_12_2.compat.of.OFHelper1_12.*;

/**
 * Renderer which draws FP2's far terrain using the shader pack's own terrain shader program, so that all of the pack's custom g-buffer attachments
 * are filled in correctly and the pack's lighting and atmospheric effects are applied to the far terrain.
 * <p>
 * The geometry which is drawn through the pack's program has already been converted into the vertex format which vanilla chunk rendering uses (see
 * {@code CPUCulledUniformRenderIndex#drawShaderPackTiles}), so the pack's program reads the same attributes it would read for a vanilla chunk.
 *
 * @author DaPorkchop_
 */
@SideOnly(Side.CLIENT)
public final class OFShaderPackRenderer1_12 implements ShaderPackCompat.Renderer {
    public static final OFShaderPackRenderer1_12 INSTANCE = new OFShaderPackRenderer1_12();

    private OFShaderPackRenderer1_12() {
    }

    @Override
    public boolean isActive() {
        //Optifine's "(internal)" pack resolves its shader sources from Optifine's own classpath, which contains none - all of its programs therefore end up
        //  with an id of 0, and drawing through them silently does nothing (the captured geometry uses generic vertex attributes, which the fixed-function
        //  pipeline ignores, while vanilla chunks still draw because they use fixed-function client arrays). a pack whose terrain program failed to compile
        //  behaves the same way, so both fall back to fp2's own shaders instead of losing the far terrain entirely.
        return OF && of_Config_isShaders() && of_Shaders_programTerrainId() > 0;
    }

    @Override
    public int entityAttribute() {
        return OF_SHADERS_ENTITYATTRIB;
    }

    @Override
    public int waterBlockAliasId() {
        return of_BlockAliases_getBlockAliasId(9, 0);
    }

    @Override
    public boolean hasWaterProgram() {
        return of_Shaders_programWaterId() > 0;
    }

    @Override
    public boolean isShadowPass() {
        return of_Shaders_isShadowPass();
    }

    @Override
    public void beginPass(int level, int pass) {
        //OptiFine caches the currently active program. As FP2 binds its own shader programs for drawing, OptiFine's cache thinks that its terrain
        //  program is still active, which would cause the "begin" call below to do nothing! Switching to a different program first makes OptiFine
        //  actually bind the terrain program again. Note that the program which was bound before is restored after each transform feedback capture,
        //  so the cache is consistent again by the time this returns.
        of_ShadersRender_endTerrain();

        if (pass == RenderConstants.LAYER_CUTOUT || pass == RenderConstants.LAYER_TRANSPARENT) {
            of_ShadersRender_beginTerrainCutout();
        } else {
            of_ShadersRender_beginTerrainSolid();
        }

        this.setConstantAttributes();
    }

    @Override
    public void endPass(int level, int pass) {
        //no-op
    }

    @Override
    public void beginWaterPass(int level) {
        //The shader pack's water program is bound by OptiFine for the duration of the vanilla translucent render pass, and the pack's deferred pass has
        //  already run by then - which is why the far terrain's translucent pass is drawn here rather than together with the solid and cutout passes.
        //  Note that OptiFine's Shaders.beginWater() must NOT be called here, as it would cause the deferred pass to run a second time!

        //FP2's state preserver unbinds the active shader program before drawing, but OptiFine's program cache still thinks that the water program is
        //  active - which means that OptiFine wouldn't bind it again if we asked it to. Bind it directly instead.
        int waterProgram = of_Shaders_programWaterId();
        if (waterProgram > 0) {
            GL20.glUseProgram(waterProgram);
        }

        this.setConstantAttributes();
    }

    @Override
    public void endWaterPass(int level) {
        //no-op
    }

    /**
     * Sets the vertex attributes which aren't present in the captured geometry to constant values.
     */
    private void setConstantAttributes() {
        //- mc_midTexCoord: unused, as long as the pack's parallax occlusion mapping and waving plants are disabled
        //- at_tangent: a dummy unit tangent, used to build the TBN matrix
        GL20.glVertexAttrib4f(OF_SHADERS_MIDTEXCOORDATTRIB, 0.0f, 0.0f, 0.0f, 0.0f);
        GL20.glVertexAttrib4f(OF_SHADERS_TANGENTATTRIB, 1.0f, 0.0f, 0.0f, 1.0f);
    }
}

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

package net.daporkchop.fp2.core.engine.client;

import com.google.common.collect.ImmutableMap;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.val;
import net.daporkchop.fp2.api.util.Identifier;
import net.daporkchop.fp2.common.util.alloc.DirectMemoryAllocator;
import net.daporkchop.fp2.core.FP2Core;
import net.daporkchop.fp2.core.client.FP2Client;
import net.daporkchop.fp2.core.client.IFrustum;
import net.daporkchop.fp2.core.client.render.GlobalRenderer;
import net.daporkchop.fp2.core.client.render.LevelRenderer;
import net.daporkchop.fp2.core.client.render.ShaderPackCompat;
import net.daporkchop.fp2.core.client.render.state.CameraState;
import net.daporkchop.fp2.core.client.render.state.CameraStateUniforms;
import net.daporkchop.fp2.core.client.render.state.DrawState;
import net.daporkchop.fp2.core.client.render.state.DrawStateUniforms;
import net.daporkchop.fp2.core.client.render.textureuvs.gpu.GpuQuadLists;
import net.daporkchop.fp2.core.client.shader.ReloadableShaderProgram;
import net.daporkchop.fp2.core.client.shader.ReloadableShaderRegistry;
import net.daporkchop.fp2.core.client.shader.ShaderMacros;
import net.daporkchop.fp2.core.client.shader.ShaderRegistration;
import net.daporkchop.fp2.core.config.FP2Config;
import net.daporkchop.fp2.core.engine.EngineConstants;
import net.daporkchop.fp2.core.engine.api.ctx.IFarClientContext;
import net.daporkchop.fp2.core.engine.client.bake.IRenderBaker;
import net.daporkchop.fp2.core.engine.client.bake.VoxelBaker;
import net.daporkchop.fp2.core.engine.client.bake.storage.BakeStorage;
import net.daporkchop.fp2.core.engine.client.bake.storage.PerLevelBakeStorage;
import net.daporkchop.fp2.core.engine.client.bake.storage.SimpleBakeStorage;
import net.daporkchop.fp2.core.engine.client.index.RenderIndex;
import net.daporkchop.fp2.core.engine.client.index.RenderIndexType;
import net.daporkchop.fp2.core.engine.client.struct.VoxelLocalAttributes;
import net.daporkchop.fp2.gl.GLExtension;
import net.daporkchop.fp2.gl.GLExtensionSet;
import net.daporkchop.fp2.gl.OpenGL;
import net.daporkchop.fp2.gl.attribute.AttributeFormat;
import net.daporkchop.fp2.gl.attribute.AttributeStruct;
import net.daporkchop.fp2.gl.attribute.UniformBuffer;
import net.daporkchop.fp2.gl.texture.TextureTarget;
import net.daporkchop.fp2.gl.buffer.IndexedBufferTarget;
import net.daporkchop.fp2.gl.buffer.upload.BufferUploader;
import net.daporkchop.fp2.gl.buffer.upload.ImmediateBufferUploader;
import net.daporkchop.fp2.gl.buffer.upload.ScratchCopyBufferUploader;
import net.daporkchop.fp2.gl.buffer.upload.UnsynchronizedMapBufferUploader;
import net.daporkchop.fp2.gl.draw.DrawMode;
import net.daporkchop.fp2.gl.draw.index.IndexFormat;
import net.daporkchop.fp2.gl.shader.DrawShaderProgram;
import net.daporkchop.fp2.gl.shader.ShaderType;
import net.daporkchop.fp2.gl.state.StatePreserver;
import net.daporkchop.lib.common.closeable.PResourceUtil;
import net.daporkchop.lib.common.misc.release.AbstractReleasable;

import java.util.ArrayList;
import java.util.List;

import static net.daporkchop.fp2.core.FP2Core.*;
import static net.daporkchop.fp2.core.debug.FP2Debug.*;
import static net.daporkchop.fp2.gl.OpenGLConstants.*;

/**
 * @author DaPorkchop_
 */
@Getter
public abstract class AbstractFarRenderer<VertexType extends AttributeStruct> extends AbstractReleasable {
    /**
     * @author DaPorkchop_
     */
    @RequiredArgsConstructor
    @EqualsAndHashCode
    @ToString
    private static final class DrawShaderVariant {
        /**
         * The output variables which are captured by the transform feedback pass, in the order in which they're written to the buffer.
         * <p>
         * This is synced with {@code resources/assets/fp2/shaders/geom/xfb.geom} and the {@code SHADER_PACK_XFB_*_OFFSET} constants in
         * {@link RenderConstants}.
         */
        private static final String[] XFB_OUTPUT_VARIABLES = {
                "xfb_pos", "xfb_colorNormal", "xfb_uv", "xfb_misc"
        };

        final @NonNull FP2Config.Debug.DebugColorMode debugColorMode;
        final @NonNull DrawState.FogMode fogMode;
        final @NonNull RenderIndex.PosTechnique posTechnique;
        final @NonNull GpuQuadLists.QuadsTechnique quadsTechnique;
        final boolean cutout;
        final boolean stencil;
        /**
         * Whether this variant is a transform feedback program which captures geometry in the vertex format expected by shader packs, rather than a
         * regular drawing program.
         */
        final boolean xfb;

        public ImmutableMap<String, Object> defines() {
            ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
            builder.put("FP2_DEBUG_COLOR_MODE", this.debugColorMode.ordinal());
            builder.put("FP2_FOG_MODE", this.fogMode.ordinal());
            builder.put("FP2_TILE_POS_TECHNIQUE", this.posTechnique.ordinal());
            builder.put("FP2_TEXTURE_UVS_TECHNIQUE", this.quadsTechnique.ordinal());
            if (this.cutout) {
                builder.put("FP2_CUTOUT", true);
            }
            return builder.build();
        }

        public ReloadableShaderProgram.SetupFunction<DrawShaderProgram.Builder> setupFunction(FP2Client client, GlobalRenderer globalRenderer) {
            return builder -> {
                builder.addUBO(RenderConstants.CAMERA_STATE_UNIFORMS_UBO_BINDING, RenderConstants.CAMERA_STATE_UNIFORMS_UBO_NAME);
                builder.addUBO(RenderConstants.DRAW_STATE_UNIFORMS_UBO_BINDING, RenderConstants.DRAW_STATE_UNIFORMS_UBO_NAME);
                builder.addUBO(RenderConstants.TILE_POS_ARRAY_UBO_BINDING, RenderConstants.TILE_POS_ARRAY_UBO_NAME);
                builder.addSampler(client.terrainTextureUnit(), RenderConstants.TEXTURE_ATLAS_SAMPLER_NAME);
                builder.addSampler(client.lightmapTextureUnit(), RenderConstants.LIGHTMAP_SAMPLER_NAME);
                builder.vertexAttributesWithPrefix("a_", globalRenderer.voxelVertexAttributesFormat);
                builder.vertexAttributesWithPrefix("a_", globalRenderer.voxelInstancedAttributesFormat);

                switch (this.quadsTechnique) {
                    case SSBO:
                        builder.addSSBO(RenderConstants.TEXTURE_UVS_LISTS_SSBO_BINDING, RenderConstants.TEXTURE_UVS_LISTS_SSBO_NAME);
                        builder.addSSBO(RenderConstants.TEXTURE_UVS_QUADS_SSBO_BINDING, RenderConstants.TEXTURE_UVS_QUADS_SSBO_NAME);
                        break;
                    case BUFFER_TEXTURE:
                        builder.addSampler(RenderConstants.TEXTURE_UVS_LISTS_SAMPLERBUFFER_BINDING, RenderConstants.TEXTURE_UVS_LISTS_SAMPLERBUFFER_NAME);
                        builder.addSampler(RenderConstants.TEXTURE_UVS_QUADS_COORD_SAMPLERBUFFER_BINDING, RenderConstants.TEXTURE_UVS_QUADS_COORD_SAMPLERBUFFER_NAME);
                        builder.addSampler(RenderConstants.TEXTURE_UVS_QUADS_TINT_SAMPLERBUFFER_BINDING, RenderConstants.TEXTURE_UVS_QUADS_TINT_SAMPLERBUFFER_NAME);
                        break;
                    case TEXTURE_2D:
                        builder.addSampler(RenderConstants.TEXTURE_UVS_LISTS_SAMPLER2D_BINDING, RenderConstants.TEXTURE_UVS_LISTS_SAMPLER2D_NAME);
                        builder.addSampler(RenderConstants.TEXTURE_UVS_QUADS_COORD_SAMPLER2D_BINDING, RenderConstants.TEXTURE_UVS_QUADS_COORD_SAMPLER2D_NAME);
                        builder.addSampler(RenderConstants.TEXTURE_UVS_QUADS_TINT_SAMPLER2D_BINDING, RenderConstants.TEXTURE_UVS_QUADS_TINT_SAMPLER2D_NAME);
                        break;
                    default:
                        throw new IllegalArgumentException(this.quadsTechnique.name());
                }

                if (this.xfb) {
                    builder.transformFeedbackVaryings(XFB_OUTPUT_VARIABLES, GL_INTERLEAVED_ATTRIBS);
                }
            };
        }

        public static List<DrawShaderVariant> allVariants(@NonNull OpenGL gl) {
            FP2Config.Debug.DebugColorMode[] debugColorModes = FP2Config.Debug.DebugColorMode.values();
            DrawState.FogMode[] fogModes = DrawState.FogMode.values();
            RenderIndex.PosTechnique[] posTechniques = RenderIndex.PosTechnique.values();
            GpuQuadLists.QuadsTechnique[] quadsTechniques = GpuQuadLists.QuadsTechnique.values();

            List<DrawShaderVariant> result = new ArrayList<>(debugColorModes.length * fogModes.length * posTechniques.length * quadsTechniques.length * 3);
            for (val debugColorMode : debugColorModes) {
                for (val fogMode : fogModes) {
                    for (val posTechnique : posTechniques) {
                        if (!gl.supports(posTechnique.requiredExtensions())) {
                            continue;
                        }

                        for (val quadsTechnique : quadsTechniques) {
                            if (!gl.supports(quadsTechnique.requiredExtensions())) {
                                continue;
                            }

                            //Iterate over the tuples (cutout, stencil): { (false, false), (false, true), (true, false) }
                            //We never use the variant with both cutout and stencil enabled, so we won't bother compiling it!
                            for (int i = 0b00; i < 0b11; i++) {
                                boolean cutout = (i & 0b01) != 0;
                                boolean stencil = (i & 0b10) != 0;

                                result.add(new DrawShaderVariant(debugColorMode, fogMode, posTechnique, quadsTechnique, cutout, stencil, false));
                            }
                        }
                    }
                }
            }
            return result;
        }
    }

    /**
     * @author DaPorkchop_
     */
    public static final class RegisterShaders extends ShaderRegistration {
        public RegisterShaders() {
            super(GLExtensionSet.empty());
        }

        @Override
        public void registerShaders(@NonNull GlobalRenderer globalRenderer, @NonNull ReloadableShaderRegistry.Builder shaderRegistryBuilder, @NonNull ShaderMacros shaderMacros, @NonNull FP2Client client, @NonNull OpenGL gl) {
            for (DrawShaderVariant variant : DrawShaderVariant.allVariants(gl)) {
                val builder = shaderRegistryBuilder.registerDraw(variant, shaderMacros.withDefined(variant.defines()), variant.setupFunction(client, globalRenderer));
                builder.addShader(ShaderType.VERTEX, Identifier.from(MODID, "shaders/vert/voxel/voxel.vert"));
                if (!variant.stencil) {
                    builder.addShader(ShaderType.FRAGMENT, Identifier.from(MODID, "shaders/frag/block.frag"));
                }
            }

            //transform feedback programs which capture fp2's geometry in the vertex format expected by shader packs. these are used to draw the far terrain
            //  through a shader pack's own shader programs, but are registered unconditionally so that they're already available when a pack gets enabled.
            for (val posTechnique : RenderIndex.PosTechnique.values()) {
                if (!gl.supports(posTechnique.requiredExtensions())) {
                    continue;
                }

                for (val quadsTechnique : GpuQuadLists.QuadsTechnique.values()) {
                    if (!gl.supports(quadsTechnique.requiredExtensions())) {
                        continue;
                    }

                    val variant = new DrawShaderVariant(FP2Config.Debug.DebugColorMode.DISABLED, DrawState.FogMode.DISABLED, posTechnique, quadsTechnique, false, false, true);
                    val builder = shaderRegistryBuilder.registerDraw(variant, shaderMacros.withDefined(variant.defines()), variant.setupFunction(client, globalRenderer));
                    builder.addShader(ShaderType.VERTEX, Identifier.from(MODID, "shaders/vert/voxel/voxel.vert"));
                    builder.addShader(ShaderType.GEOMETRY, Identifier.from(MODID, "shaders/geom/xfb.geom"));
                    builder.addShader(ShaderType.FRAGMENT, Identifier.from(MODID, "shaders/frag/xfb.frag"));
                }
            }
        }
    }

    protected final FP2Core fp2;
    protected final LevelRenderer levelRenderer;
    protected final OpenGL gl;

    protected final IFarClientContext context;

    protected final AttributeFormat<VertexType> vertexFormat;
    protected final IndexFormat indexFormat;

    protected final DirectMemoryAllocator alloc = new DirectMemoryAllocator(false);
    protected final BufferUploader bufferUploader;

    protected final UniformBuffer<CameraStateUniforms> cameraStateUniformsBuffer;
    protected final UniformBuffer<DrawStateUniforms> drawStateUniformsBuffer;

    protected final IRenderBaker<VertexType> baker;
    protected final BakeStorage<VertexType> bakeStorage;
    protected final RenderIndex<VertexType> renderIndex;
    protected final BakeManager<VertexType> bakeManager;

    /**
     * Whether a shader pack was active when this renderer was created.
     * <p>
     * Shader pack compatibility needs a render index implementation which is able to draw each tile individually, but the implementation is selected once
     * when the renderer is created. If this value doesn't match the current state, the renderer needs to be recreated.
     */
    protected final boolean shaderPackActive;

    /**
     * The camera state with which the current frame's translucent pass would have to be drawn through a shader pack's water shader program, or
     * {@code null} if there is nothing to draw.
     * <p>
     * The translucent pass can't be drawn together with the others when a shader pack is active, as its water shader program only becomes active during
     * the vanilla translucent render pass. It is therefore deferred to {@link #renderShaderPackWater()} instead.
     */
    protected CameraState shaderPackWaterCameraState;

    /**
     * The transform feedback program which was used to capture the current frame's geometry for shader pack compatibility, or {@code null} if it wasn't
     * captured.
     * <p>
     * This is only used to capture the translucent pass in {@link #renderShaderPackWater()}.
     */
    protected DrawShaderProgram shaderPackXfbShaderProgram;

    protected RenderIndex.DrawableMask deferredTranslucentDrawableMask;
    protected RenderIndex.DrawArguments deferredTranslucentDrawArguments;
    protected CapturedShaderPrograms deferredTranslucentShaderPrograms;

    protected final RenderIndex.PosTechnique tilePosTechnique;
    protected final GpuQuadLists.QuadsTechnique textureQuadsTechnique;
    protected final DrawMode drawMode;

    protected final StatePreserver statePreserverSelect;
    protected final StatePreserver statePreserverDraw;

    @SuppressWarnings("unchecked")
    public AbstractFarRenderer(@NonNull IFarClientContext context) {
        try {
            this.fp2 = context.fp2();
            this.context = context;

            FP2Client client = this.fp2.client();
            GlobalRenderer globalRenderer = client.globalRenderer();

            this.levelRenderer = context.level().renderer();
            this.gl = client.gl();

            this.shaderPackActive = ShaderPackCompat.isActive();

            RenderIndexType renderIndexType = this.chooseRenderIndexType();

            this.vertexFormat = (AttributeFormat<VertexType>) globalRenderer.voxelVertexAttributesFormat;
            this.indexFormat = renderIndexType.absoluteIndices()
                    ? globalRenderer.unsignedIntIndexFormat
                    : globalRenderer.unsignedShortIndexFormat;

            //If GL_ARB_compatibility is supported, we can draw using quads instead of triangles.
            //Based on tests, this seems to very slightly improve performance - modern GPUs seem to be able to split indexed quads into triangles
            //  in hardware, and it reduces the size of our index buffers.
            //  Shader pack compatibility requires triangles, as geometry shaders can't accept quads as an input primitive type.
            this.drawMode = !this.shaderPackActive && this.fp2.globalConfig().performance().renderQuads() && this.gl.supports(GLExtension.GL_ARB_compatibility)
                    ? DrawMode.QUADS
                    : DrawMode.TRIANGLES;

            //use the most efficient buffer uploader implementation supported by the current OpenGL context
            this.bufferUploader = this.gl.supports(UnsynchronizedMapBufferUploader.REQUIRED_EXTENSIONS)
                    ? new UnsynchronizedMapBufferUploader(this.gl, 8 << 20) //8 MiB
                    : this.gl.supports(ScratchCopyBufferUploader.REQUIRED_EXTENSIONS)
                    ? new ScratchCopyBufferUploader(this.gl)
                    : ImmediateBufferUploader.instance();

            this.cameraStateUniformsBuffer = globalRenderer.cameraStateUniformsFormat.createUniformBuffer();
            this.drawStateUniformsBuffer = globalRenderer.drawStateUniformsFormat.createUniformBuffer();

            this.baker = this.createBaker(context, this.drawMode);
            this.bakeStorage = new PerLevelBakeStorage<>(this.gl, this.bufferUploader, this.vertexFormat, this.indexFormat, renderIndexType.absoluteIndices(), this.drawMode,
                    (level, absoluteIndices) -> new SimpleBakeStorage<>(this.gl, this.bufferUploader, this.vertexFormat, this.indexFormat, absoluteIndices, this.drawMode));
            this.renderIndex = renderIndexType.createRenderIndex(this.gl, this.bakeStorage, this.alloc, globalRenderer, this.cameraStateUniformsBuffer);
            this.bakeManager = new BakeManager<>(this, context.tileCache(), this.baker);

            this.tilePosTechnique = this.renderIndex.posTechnique();
            this.textureQuadsTechnique = this.levelRenderer.textureUVs().gpuQuadLists().quadsTechnique;

            {
                StatePreserver.Builder statePreserverBuilder = StatePreserver.builder(this.gl);
                this.renderIndex.preservedSelectState(statePreserverBuilder);
                this.statePreserverSelect = statePreserverBuilder.build();
            }

            {
                StatePreserver.Builder statePreserverBuilder = StatePreserver.builder(this.gl)
                        .activeProgram()
                        .vao()
                        .fixedFunctionDrawState()
                        .texture(TextureTarget.TEXTURE_2D, client.terrainTextureUnit())
                        .texture(TextureTarget.TEXTURE_2D, client.lightmapTextureUnit())
                        .indexedBuffer(IndexedBufferTarget.UNIFORM_BUFFER, RenderConstants.CAMERA_STATE_UNIFORMS_UBO_BINDING)
                        .indexedBuffer(IndexedBufferTarget.UNIFORM_BUFFER, RenderConstants.DRAW_STATE_UNIFORMS_UBO_BINDING);
                this.levelRenderer.textureUVs().gpuQuadLists().preservedBindState(statePreserverBuilder);
                this.renderIndex.preservedDrawState(statePreserverBuilder);
                this.statePreserverDraw = statePreserverBuilder.build();
            }
        } catch (Throwable t) {
            throw PResourceUtil.closeSuppressed(t, this);
        }
    }

    @Override
    protected void doRelease() {
        PResourceUtil.closeAll(
                this.bakeManager,
                this.renderIndex,
                this.bakeStorage,
                this.drawStateUniformsBuffer,
                this.cameraStateUniformsBuffer,
                this.bufferUploader,
                this.alloc);
    }

    private RenderIndexType chooseRenderIndexType() {
        for (val implementation : RenderIndexType.getTypes(this.fp2)) {
            if (!implementation.enabled(this.fp2.globalConfig())) {
                this.fp2.log().debug("Render index implementation " + implementation + " is disabled by config");
            } else if (!this.gl.supports(implementation.requiredExtensions())) {
                //TODO: show a warning message ingame, somehow
                this.fp2.log().warn("Render index implementation " + implementation + " is enabled, but your OpenGL implementation doesn't support it! " + this.gl.unsupportedMsg(implementation.requiredExtensions()));
            } else {
                this.fp2.log().info("Using " + implementation);
                return implementation;
            }
        }

        throw new UnsupportedOperationException("No render index implementations are supported! (see log)");
    }

    protected abstract IRenderBaker<VertexType> createBaker(@NonNull IFarClientContext context, @NonNull DrawMode drawMode);

    /**
     * Called before rendering a frame to prepare the render system for drawing the frame.
     *
     * @param frustum the current view frustum
     */
    public void prepare(@NonNull CameraState cameraState, @NonNull IFrustum frustum) {
        this.bufferUploader.tick();
        this.bakeManager.tick(this.bufferUploader, this.bakeStorage, this.renderIndex);

        //update camera state uniforms
        try (val uniforms = this.cameraStateUniformsBuffer.update()) {
            cameraState.configureUniforms(uniforms);
        }

        try (val ignored = this.statePreserverSelect.backup()) { //back up opengl state prior to selecting
            this.renderIndex.select(frustum, this.levelRenderer.blockedTracker());
        }
    }

    /**
     * A container holding the actual shader programs that we're going to draw with.
     *
     * @author DaPorkchop_
     */
    @RequiredArgsConstructor
    private static final class CapturedShaderPrograms {
        final DrawShaderProgram blockShaderProgram;
        final DrawShaderProgram blockCutoutShaderProgram;
        final DrawShaderProgram blockStencilShaderProgram;
        final DrawShaderProgram xfbShaderProgram;
    }

    private CapturedShaderPrograms captureShaderPrograms(@NonNull FP2Config.Debug.DebugColorMode debugColorMode, @NonNull DrawState.FogMode fogMode) {
        ReloadableShaderRegistry shaderRegistry = this.fp2.client().globalRenderer().shaderRegistry;

        return new CapturedShaderPrograms(
                shaderRegistry.<DrawShaderProgram>get(new DrawShaderVariant(debugColorMode, fogMode, this.tilePosTechnique, this.textureQuadsTechnique, false, false, false)).get(),
                shaderRegistry.<DrawShaderProgram>get(new DrawShaderVariant(debugColorMode, fogMode, this.tilePosTechnique, this.textureQuadsTechnique, true, false, false)).get(),
                shaderRegistry.<DrawShaderProgram>get(new DrawShaderVariant(debugColorMode, fogMode, this.tilePosTechnique, this.textureQuadsTechnique, false, true, false)).get(),
                shaderRegistry.<DrawShaderProgram>get(new DrawShaderVariant(FP2Config.Debug.DebugColorMode.DISABLED, DrawState.FogMode.DISABLED, this.tilePosTechnique, this.textureQuadsTechnique, false, false, true)).get());
    }

    /**
     * Renders a frame.
     */
    /**
     * Checks whether the shader pack state has changed since this renderer was created.
     * <p>
     * If this returns {@code true}, the renderer must be recreated (see {@link IFarClientContext#reloadRenderer()}) so that the appropriate render index
     * implementation gets selected. This must not be called from within {@link #render(CameraState, DrawState)}, as reloading the renderer closes it!
     *
     * @return whether this renderer needs to be recreated in order to match the current shader pack state
     */
    public boolean needsReloadForShaderPackChange() {
        return this.shaderPackActive != ShaderPackCompat.isActive();
    }

    public void render(@NonNull CameraState cameraState, @NonNull DrawState drawState) {
        this.shaderPackWaterCameraState = null; //reset the pending translucent pass draw, it will be re-initialized below if needed
        this.shaderPackXfbShaderProgram = null;
        this.deferredTranslucentDrawableMask = null;
        this.deferredTranslucentDrawArguments = null;
        this.deferredTranslucentShaderPrograms = null;

        RenderIndex.DrawableMask drawableMask = this.renderIndex.drawableMask();

        if (ShaderPackCompat.isActive()) {
            ShaderPackCompat.Renderer shaderPack = ShaderPackCompat.renderer();
            if (shaderPack != null && shaderPack.isShadowPass()) {
                //the shader pack's shadow area only covers a small part of the world around the player, which is entirely covered by the finest detail
                //  level. the rest of the far terrain would have to be captured and drawn a second time without contributing any visible shadows, so skip it.
                drawableMask = drawableMask.copyOfLevelRange(drawableMask.minLevelToDraw(), Math.min(drawableMask.minLevelToDraw() + 1, EngineConstants.MAX_LODS));
            }
        }

        if (FP2_DEBUG && !this.fp2.globalConfig().debug().levelZeroRendering()) {
            //if level-zero rendering is turned off, use a mask with level 0 disabled
            drawableMask = drawableMask.copyOfLevelRange(1, EngineConstants.MAX_LODS);
        }

        if (!drawableMask.shouldDrawAnything()) { //early exit if nothing is drawable
            return;
        }

        //update draw state uniforms
        try (val uniforms = this.drawStateUniformsBuffer.update()) {
            drawState.configureUniforms(uniforms);
        }

        //determine which shader programs we're going to use
        FP2Config.Debug.DebugColorMode debugColorMode = FP2_DEBUG ? this.fp2.globalConfig().debug().debugColors() : FP2Config.Debug.DebugColorMode.DISABLED;
        val capturedShaderPrograms = this.captureShaderPrograms(debugColorMode, drawState.fogMode);

        RenderIndex.DrawArguments drawArguments = new RenderIndex.DrawArguments(
                cameraState, this.cameraStateUniformsBuffer,
                drawState, this.drawStateUniformsBuffer,
                this.levelRenderer.blockedTracker(),
                this.fp2.client().renderManager().isReversedZSupportedAndActive(),
                capturedShaderPrograms.xfbShaderProgram);

        try (val ignored = this.statePreserverDraw.backup()) { //back up opengl state prior to rendering
            this.preRender(drawArguments);

            //in order to properly render overlapping layers while ensuring that low-detail levels always get placed on top of high-detail ones, we'll need to do the following:
            //- for each detail level:
            //  - render both the SOLID and CUTOUT passes at once, using the stencil to ensure that previously rendered SOLID and CUTOUT terrain at higher detail levels is left untouched
            //- render the TRANSPARENT pass at all detail levels at once, using the stencil to not only prevent low-detail from rendering over high-detail, but also fp2 transparent water
            //  from rendering over vanilla water

            if (drawableMask.shouldDrawAnythingForLayer(RenderConstants.LAYER_SOLID) || drawableMask.shouldDrawAnythingForLayer(RenderConstants.LAYER_CUTOUT)) {
                for (int level = drawableMask.minLevelToDraw(); level < drawableMask.maxLevelToDraw(); level++) {
                    if (drawableMask.shouldDrawAnythingForLevelLayer(level, RenderConstants.LAYER_SOLID)) {
                        this.renderSolid(drawArguments, capturedShaderPrograms, level);
                    }
                    if (drawableMask.shouldDrawAnythingForLevelLayer(level, RenderConstants.LAYER_CUTOUT)) {
                        this.renderCutout(drawArguments, capturedShaderPrograms, level);
                    }
                }
            }

            if (drawableMask.shouldDrawAnythingForLayer(RenderConstants.LAYER_TRANSPARENT)) {
                ShaderPackCompat.Renderer shaderPack = ShaderPackCompat.isActive() ? ShaderPackCompat.renderer() : null;
                if (shaderPack != null && shaderPack.hasWaterProgram()) {
                    //a shader pack is active: the translucent pass must be rendered through the pack's water shader program, which only becomes active
                    //  during the vanilla translucent render pass. defer it to renderShaderPackWater(), which will be called from there.
                    this.shaderPackWaterCameraState = cameraState;
                    this.shaderPackXfbShaderProgram = capturedShaderPrograms.xfbShaderProgram;
                } else if (shaderPack != null) {
                    //the pack doesn't have a usable water shader program (e.g. Optifine's "(internal)" pack, which has no shader sources at all), so draw the
                    //  translucent geometry as part of the regular opaque pass instead - there is no point in deferring it to the water pass, as there would be
                    //  no program to draw it with
                    for (int level = drawableMask.minLevelToDraw(); level < drawableMask.maxLevelToDraw(); level++) {
                        if (drawableMask.shouldDrawAnythingForLevelLayer(level, RenderConstants.LAYER_TRANSPARENT)) {
                            this.renderShaderPackTransparent(drawArguments, capturedShaderPrograms, level);
                        }
                    }
                } else {
                    this.deferredTranslucentDrawableMask = drawableMask;
                    this.deferredTranslucentDrawArguments = drawArguments;
                    this.deferredTranslucentShaderPrograms = capturedShaderPrograms;
                }
            }

            this.postRender(drawArguments);
        }
    }

    /**
     * Draws the deferred translucent pass of the far terrain after vanilla translucent terrain has been rendered.
     * <p>
     * The far water is drawn at its true height. It used to be shifted 1/9 of a block down (by raising the camera for this pass) so that vanilla water, which is
     *  rendered first and is lower than a full water block, would win where the two overlap. That shift left a 1/9 block tall step between the far water and the
     *  far shoreline, which showed up as a thin bright line along every shore, so it was removed - the overlap with vanilla water has to be handled per tile.
     */
    public void renderTranslucentPass() {
        val drawableMask = this.deferredTranslucentDrawableMask;
        val drawArguments = this.deferredTranslucentDrawArguments;
        val capturedShaderPrograms = this.deferredTranslucentShaderPrograms;
        if (drawableMask == null || drawArguments == null || capturedShaderPrograms == null) {
            return;
        }
        this.deferredTranslucentDrawableMask = null;
        this.deferredTranslucentDrawArguments = null;
        this.deferredTranslucentShaderPrograms = null;

        try (val ignored = this.statePreserverDraw.backup()) {
            this.preRenderTranslucent(drawArguments);

            this.renderTransparentStencilPass(drawableMask, drawArguments, capturedShaderPrograms);
            this.renderTransparentFragmentPass(drawableMask, drawArguments, capturedShaderPrograms);

            this.postRender(drawArguments);
        }
    }

    /**
     * Draws the translucent pass of the far terrain through the active shader pack's water shader program.
     * <p>
     * This has to be called from within the shader pack's water rendering phase (while the vanilla translucent render pass is being rendered), as the pack's
     * water shader program is only active at that point in the frame - and its deferred pass has already run, so the pack will apply its water shading to
     * the far terrain exactly like it does for vanilla water.
     * <p>
     * If no shader pack is active, or the far terrain has no translucent tiles to draw, this does nothing.
     */
    public void renderShaderPackWater() {
        CameraState cameraState = this.shaderPackWaterCameraState;
        DrawShaderProgram xfbShaderProgram = this.shaderPackXfbShaderProgram;
        if (cameraState == null || xfbShaderProgram == null) { //there is nothing to draw in this frame
            return;
        }
        this.shaderPackWaterCameraState = null;
        this.shaderPackXfbShaderProgram = null;

        ShaderPackCompat.Renderer shaderPack = ShaderPackCompat.renderer();
        if (shaderPack == null || !shaderPack.isActive()) { //shader pack compatibility is not available (any more)
            return;
        }

        try (val ignored = this.statePreserverDraw.backup()) { //back up opengl state prior to rendering
            //the uniform buffers are restored to their previous values when the regular render pass ends, but the captured geometry still needs the camera
            //  state in order to compute camera-relative vertex positions. note that these binding points are the same ones used by preRender(). the camera
            //  state itself is left as-is: the far water is drawn at its true height, see renderTranslucentPass().
            this.gl.glBindBufferBase(GL_UNIFORM_BUFFER, RenderConstants.CAMERA_STATE_UNIFORMS_UBO_BINDING, this.cameraStateUniformsBuffer.buffer().id());
            this.gl.glBindBufferBase(GL_UNIFORM_BUFFER, RenderConstants.DRAW_STATE_UNIFORMS_UBO_BINDING, this.drawStateUniformsBuffer.buffer().id());

            //fp2's state preserver resets the fixed-function state before drawing, which includes disabling depth testing. the translucent pass is drawn
            //  outside of the regular render pass, so it doesn't go through preRender() and has to set up the state it needs on its own.
            this.gl.glEnable(GL_DEPTH_TEST);
            this.gl.glDepthFunc(this.fp2.client().renderManager().isReversedZSupportedAndActive() ? GL_GREATER : GL_LEQUAL);
            this.gl.glDepthMask(true);

            //bind the block texture UVs, which are used to look up the texture coordinates of the block face each vertex belongs to
            this.levelRenderer.textureUVs().gpuQuadLists().bind(this.gl);

            this.renderIndex.drawShaderPackWater(cameraState, shaderPack, xfbShaderProgram);

            try (val uniforms = this.cameraStateUniformsBuffer.update()) {
                cameraState.configureUniforms(uniforms);
            }
        }
    }

    private void preRenderTranslucent(RenderIndex.DrawArguments drawArguments) {
        this.renderIndex.preDraw(drawArguments);

        //bind uniform buffers
        this.gl.glBindBufferBase(GL_UNIFORM_BUFFER, RenderConstants.CAMERA_STATE_UNIFORMS_UBO_BINDING, this.cameraStateUniformsBuffer.buffer().id());
        this.gl.glBindBufferBase(GL_UNIFORM_BUFFER, RenderConstants.DRAW_STATE_UNIFORMS_UBO_BINDING, this.drawStateUniformsBuffer.buffer().id());

        this.gl.glEnable(GL_DEPTH_TEST);
        this.gl.glDepthFunc(drawArguments.reversedZ ? GL_GREATER : GL_LEQUAL);

        //bind texture UVs
        this.levelRenderer.textureUVs().gpuQuadLists().bind(this.gl);

        if (!FP2_DEBUG || this.fp2.globalConfig().debug().backfaceCulling()) {
            this.gl.glEnable(GL_CULL_FACE);
        }

        this.gl.glEnable(GL_STENCIL_TEST);
        this.gl.glStencilMask(0xFF);
    }

    private void preRender(RenderIndex.DrawArguments drawArguments) {
        this.renderIndex.preDraw(drawArguments);

        //bind uniform buffers
        this.gl.glBindBufferBase(GL_UNIFORM_BUFFER, RenderConstants.CAMERA_STATE_UNIFORMS_UBO_BINDING, this.cameraStateUniformsBuffer.buffer().id());
        this.gl.glBindBufferBase(GL_UNIFORM_BUFFER, RenderConstants.DRAW_STATE_UNIFORMS_UBO_BINDING, this.drawStateUniformsBuffer.buffer().id());

        this.gl.glEnable(GL_DEPTH_TEST);
        this.gl.glDepthFunc(drawArguments.reversedZ ? GL_GREATER : GL_LESS);

        //bind texture UVs
        this.levelRenderer.textureUVs().gpuQuadLists().bind(this.gl);

        if (!FP2_DEBUG || this.fp2.globalConfig().debug().backfaceCulling()) {
            this.gl.glEnable(GL_CULL_FACE);
        }

        this.gl.glEnable(GL_STENCIL_TEST);
        this.gl.glStencilMask(0xFF);

        //clear stencil buffer to 0x7F
        this.gl.glClearStencil(0x7F);
        this.gl.glClear(GL_STENCIL_BUFFER_BIT);
    }

    private void postRender(RenderIndex.DrawArguments drawArguments) {
        this.renderIndex.postDraw(drawArguments);
    }

    private void renderSolid(RenderIndex.DrawArguments drawArguments, CapturedShaderPrograms capturedShaderPrograms, int level) {
        //GlStateManager.disableAlpha();

        this.gl.glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        this.gl.glStencilFunc(GL_LEQUAL, level, 0x7F);

        val shader = capturedShaderPrograms.blockShaderProgram;
        val uniformSetter = shader.bindUnsafe();
        this.renderIndex.draw(drawArguments, level, RenderConstants.LAYER_SOLID, shader, uniformSetter);

        //GlStateManager.enableAlpha();
    }

    private void renderCutout(RenderIndex.DrawArguments drawArguments, CapturedShaderPrograms capturedShaderPrograms, int level) {
        //MC.getTextureManager().getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE).setBlurMipmap(false, MC.gameSettings.mipmapLevels > 0);

        this.gl.glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        this.gl.glStencilFunc(GL_LEQUAL, level, 0x7F);

        val shader = capturedShaderPrograms.blockCutoutShaderProgram;
        val uniformSetter = shader.bindUnsafe();
        this.renderIndex.draw(drawArguments, level, RenderConstants.LAYER_CUTOUT, shader, uniformSetter);

        //MC.getTextureManager().getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE).restoreLastBlurMipmap();
    }

    /**
     * Draws the translucent tiles of the given detail level using the shader pack's cutout program.
     * <p>
     * This is only used when the pack doesn't have a usable water shader program: the translucent geometry is then drawn as part of the regular opaque pass
     * (as opaque geometry), so that it is at least lit and textured by the pack instead of not being rendered at all.
     */
    private void renderShaderPackTransparent(RenderIndex.DrawArguments drawArguments, CapturedShaderPrograms capturedShaderPrograms, int level) {
        this.gl.glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        this.gl.glStencilFunc(GL_LEQUAL, level, 0x7F);

        val shader = capturedShaderPrograms.blockCutoutShaderProgram;
        val uniformSetter = shader.bindUnsafe();
        this.renderIndex.draw(drawArguments, level, RenderConstants.LAYER_TRANSPARENT, shader, uniformSetter);
    }

    private void renderTransparentStencilPass(RenderIndex.DrawableMask drawableMask, RenderIndex.DrawArguments drawArguments, CapturedShaderPrograms capturedShaderPrograms) {
        this.gl.glColorMask(false, false, false, false);
        this.gl.glDepthMask(false);
        this.gl.glDepthFunc(drawArguments.reversedZ ? GL_GREATER : GL_LEQUAL);

        val shader = capturedShaderPrograms.blockStencilShaderProgram;
        val uniformSetter = shader.bindUnsafe();

        this.gl.glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        for (int level = drawableMask.minLevelToDraw(); level < drawableMask.maxLevelToDraw(); level++) {
            if (drawableMask.shouldDrawAnythingForLevelLayer(level, RenderConstants.LAYER_TRANSPARENT)) {
                this.gl.glStencilFunc(GL_GEQUAL, 0x80 | (EngineConstants.MAX_LODS - level), 0xFF);
                this.renderIndex.draw(drawArguments, level, RenderConstants.LAYER_TRANSPARENT, shader, uniformSetter);
            }
        }

        this.gl.glDepthMask(true);
        this.gl.glColorMask(true, true, true, true);
    }

    private void renderTransparentFragmentPass(RenderIndex.DrawableMask drawableMask, RenderIndex.DrawArguments drawArguments, CapturedShaderPrograms capturedShaderPrograms) {
        this.gl.glEnable(GL_BLEND);
        this.gl.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO);
        this.gl.glDepthFunc(drawArguments.reversedZ ? GL_GREATER : GL_LEQUAL);

        //GlStateManager.alphaFunc(GL_GREATER, 0.1f);

        val shader = capturedShaderPrograms.blockShaderProgram;
        val uniformSetter = shader.bindUnsafe();

        this.gl.glStencilMask(0);
        this.gl.glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        for (int level = drawableMask.minLevelToDraw(); level < drawableMask.maxLevelToDraw(); level++) {
            if (drawableMask.shouldDrawAnythingForLevelLayer(level, RenderConstants.LAYER_TRANSPARENT)) {
                this.gl.glStencilFunc(GL_EQUAL, 0x80 | (EngineConstants.MAX_LODS - level), 0xFF);
                this.renderIndex.draw(drawArguments, level, RenderConstants.LAYER_TRANSPARENT, shader, uniformSetter);
            }
        }

        this.gl.glDisable(GL_BLEND);
    }

    public final Stats stats() {
        return new Stats(this.bakeStorage.stats(), this.renderIndex.stats());
    }

    /**
     * Debug statistics for the renderer.
     *
     * @author DaPorkchop_
     */
    @Data
    public static final class Stats {
        private final @NonNull BakeStorage.Stats bakeStorage;
        private final @NonNull RenderIndex.Stats renderIndex;
    }

    /**
     * @author DaPorkchop_
     */
    public static class ShaderMultidraw extends AbstractFarRenderer<VoxelLocalAttributes> {
        public ShaderMultidraw(@NonNull IFarClientContext context) {
            super(context);
        }

        @Override
        protected IRenderBaker<VoxelLocalAttributes> createBaker(@NonNull IFarClientContext context, @NonNull DrawMode drawMode) {
            return new VoxelBaker(context, drawMode);
        }
    }
}

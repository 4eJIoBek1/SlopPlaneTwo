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

package net.daporkchop.fp2.core.engine.client.index.uniform;

import lombok.NonNull;
import lombok.val;
import net.daporkchop.fp2.common.util.alloc.DirectMemoryAllocator;
import net.daporkchop.fp2.core.client.IFrustum;
import net.daporkchop.fp2.core.client.render.GlobalRenderer;
import net.daporkchop.fp2.core.client.render.ShaderPackCompat;
import net.daporkchop.fp2.core.client.render.TerrainRenderingBlockedTracker;
import net.daporkchop.fp2.core.client.render.state.CameraState;
import net.daporkchop.fp2.core.client.render.state.CameraStateUniforms;
import net.daporkchop.fp2.core.engine.DirectTilePosAccess;
import net.daporkchop.fp2.core.engine.TilePos;
import net.daporkchop.fp2.core.engine.client.RenderConstants;
import net.daporkchop.fp2.core.engine.client.bake.storage.BakeStorage;
import net.daporkchop.fp2.core.engine.client.index.AbstractRenderIndex;
import net.daporkchop.fp2.core.engine.client.index.RenderIndex;
import net.daporkchop.fp2.core.engine.client.index.RenderIndexType;
import net.daporkchop.fp2.gl.GLExtension;
import net.daporkchop.fp2.gl.GLExtensionSet;
import net.daporkchop.fp2.gl.OpenGL;
import net.daporkchop.fp2.gl.attribute.AttributeBuffer;
import net.daporkchop.fp2.gl.attribute.AttributeFormat;
import net.daporkchop.fp2.gl.attribute.AttributeStruct;
import net.daporkchop.fp2.gl.attribute.UniformBuffer;
import net.daporkchop.fp2.gl.attribute.vao.VertexArrayObject;
import net.daporkchop.fp2.gl.attribute.vao.VertexArrayVertexBuffer;
import net.daporkchop.fp2.gl.attribute.vao.VertexAttributeFormat;
import net.daporkchop.fp2.gl.shader.DrawShaderProgram;
import net.daporkchop.fp2.gl.shader.ShaderProgram;
import net.daporkchop.fp2.gl.state.StatePreserver;
import net.daporkchop.lib.common.util.PArrays;

import java.util.Map;
import java.util.stream.Stream;

import static net.daporkchop.fp2.core.engine.EngineConstants.*;
import static net.daporkchop.fp2.gl.OpenGLConstants.*;
import static net.daporkchop.lib.common.util.PorkUtil.*;

/**
 * @author DaPorkchop_
 */
public class CPUCulledUniformRenderIndex<VertexType extends AttributeStruct> extends AbstractRenderIndex.OnlyVertexTypeAttribs<VertexType> {
    /**
     * @author DaPorkchop_
     */
    public static class TypeBaseVertex extends RenderIndexType {
        public static final GLExtensionSet REQUIRED_EXTENSIONS = OnlyVertexTypeAttribs.REQUIRED_EXTENSIONS
                .add(GLExtension.GL_ARB_draw_elements_base_vertex);

        public TypeBaseVertex() {
            super(REQUIRED_EXTENSIONS);
        }

        @Override
        public <VertexType extends AttributeStruct> RenderIndex<VertexType> createRenderIndex(OpenGL gl, BakeStorage<VertexType> bakeStorage, DirectMemoryAllocator alloc, GlobalRenderer globalRenderer, UniformBuffer<CameraStateUniforms> cameraStateUniformsBuffer) {
            return new CPUCulledUniformRenderIndex<>(gl, bakeStorage, alloc);
        }
    }

    /**
     * @author DaPorkchop_
     */
    public static final class TypeAbsoluteIndices extends RenderIndexType {
        public static final GLExtensionSet REQUIRED_EXTENSIONS = OnlyVertexTypeAttribs.REQUIRED_EXTENSIONS;

        public TypeAbsoluteIndices() {
            super(REQUIRED_EXTENSIONS);
        }

        @Override
        public boolean absoluteIndices() {
            return true;
        }

        @Override
        public <VertexType extends AttributeStruct> RenderIndex<VertexType> createRenderIndex(OpenGL gl, BakeStorage<VertexType> bakeStorage, DirectMemoryAllocator alloc, GlobalRenderer globalRenderer, UniformBuffer<CameraStateUniforms> cameraStateUniformsBuffer) {
            return new CPUCulledUniformRenderIndex<>(gl, bakeStorage, alloc);
        }
    }

    //this isn't even remotely as efficient as it could be, but i don't care
    protected final Map<TilePos, BakeStorage.Location[]> allLocations = DirectTilePosAccess.newPositionKeyedHashMap();
    protected final Map<TilePos, BakeStorage.Location[]>[] selectedLocations = uncheckedCast(PArrays.filledFrom(MAX_LODS, Map[]::new, DirectTilePosAccess::newPositionKeyedHashMap));

    /**
     * The buffer into which tile geometry is captured for shader pack compatibility, or {@code -1} if it hasn't been created yet.
     */
    protected int shaderPackCaptureBuffer = -1;
    /**
     * The capacity of {@link #shaderPackCaptureBuffer}, in vertices.
     */
    protected int shaderPackCaptureCapacity;
    /**
     * The vertex array object exposing {@link #shaderPackCaptureBuffer} in the vertex format expected by shader packs, or {@code -1} if it hasn't been
     * created yet.
     */
    protected int shaderPackCaptureVao = -1;
    /**
     * The maximum number of vertices which are captured in a single batch.
     * <p>
     * Capturing and drawing tiles costs a fixed amount of CPU time per batch (the transform feedback setup and the program switches), but the geometry of
     * every tile has to be captured before it can be drawn, which means that the capture buffer needs to be able to hold a whole batch. Batching a couple
     * hundred thousand vertices (24 MiB with the current vertex stride) is a good compromise between the two.
     */
    protected static final int SHADER_PACK_CAPTURE_BATCH_VERTICES = 256 << 10;

    /**
     * Scratch arrays holding the tile positions and draw commands which are drawn by {@link #drawShaderPackTiles}, in the order in which they're drawn.
     */
    protected TilePos[] shaderPackTilePositions = new TilePos[0];
    protected BakeStorage.Location[] shaderPackTileCommands = new BakeStorage.Location[0];

    public CPUCulledUniformRenderIndex(OpenGL gl, BakeStorage<VertexType> bakeStorage, DirectMemoryAllocator alloc) {
        super(gl, bakeStorage, alloc);
    }

    @Override
    public void close() {
        try {
            super.close();
        } finally {
            if (this.shaderPackCaptureVao >= 0) {
                this.gl.glDeleteVertexArray(this.shaderPackCaptureVao);
            }
            if (this.shaderPackCaptureBuffer >= 0) {
                this.gl.glDeleteBuffer(this.shaderPackCaptureBuffer);
            }
        }
    }

    @Override
    protected void recomputeTile(@NonNull TilePos pos) {
        BakeStorage.Location[] locations = this.bakeStorage.find(pos);
        if (locations == null || this.hiddenPositions.contains(pos)) {
            //the position doesn't have any render data data associated with it or is hidden, and therefore can be entirely omitted from the index
            this.allLocations.remove(pos);
        } else {
            this.allLocations.put(pos, locations);
        }
    }

    @Override
    public void select(IFrustum frustum, TerrainRenderingBlockedTracker blockedTracker) {
        //clear all the lists
        for (val level : this.selectedLocations) {
            level.clear();
        }

        //iterate over all the tile positions and draw commands
        this.allLocations.forEach((pos, commands) -> {
            int level = pos.level();
            if (!blockedTracker.isTileBlocked(pos)
                && frustum.intersectsBB(pos.minBlockX(), pos.minBlockY(), pos.minBlockZ(), pos.maxBlockX(), pos.maxBlockY(), pos.maxBlockZ())) {
                this.selectedLocations[level].put(pos, commands);
            }
        });
    }

    @Override
    public void preservedDrawState(StatePreserver.Builder builder) {
        super.preservedDrawState(builder.vao());
    }

    @Override
    public void draw(DrawArguments args, int level, int pass, DrawShaderProgram shader, ShaderProgram.UniformSetter uniformSetter) {
        val selectedLocations = this.selectedLocations[level];
        if (selectedLocations.isEmpty()) {
            return;
        }

        ShaderPackCompat.Renderer shaderPack = ShaderPackCompat.renderer();
        if (shaderPack != null && shaderPack.isActive()) {
            //a shader pack is active: draw the tiles using the pack's own terrain shader program, so that its custom g-buffer layout is filled
            //  in correctly and the pack's lighting/fog is applied to our geometry
            this.drawShaderPack(args, level, pass, selectedLocations, shaderPack);
            return;
        }

        int modeEnum = this.bakeStorage.drawMode.mode();
        val indexFormat = this.bakeStorage.indexFormat.type();
        int indexType = indexFormat.type();
        int indexSize = indexFormat.size();

        OpenGL gl = this.gl;
        boolean absoluteIndices = this.bakeStorage.absoluteIndices;

        gl.glBindVertexArray(this.vaos.get(level, pass).id());

        int uniformLocation = shader.uniformLocation(RenderConstants.TILE_POS_UNIFORM_NAME);

        selectedLocations.forEach((pos, commands) -> {
            val command = commands[pass];
            if (command != null) {
                uniformSetter.set4i(uniformLocation, pos.x(), pos.y(), pos.z(), pos.level());

                int count = command.count;
                long indices = command.firstIndex * (long) indexSize;

                if (absoluteIndices) {
                    //if the indices are absolute, we don't need to pass in the baseVertex parameter and so we can use a draw command
                    //  with absolutely no feature requirements
                    gl.glDrawElements(modeEnum, count, indexType, indices);
                } else {
                    gl.glDrawElementsBaseVertex(modeEnum, count, indexType, indices, command.baseVertex);
                }
            }
        });
    }

    /**
     * Draws the selected tiles using a shader pack's terrain shader program.
     * <p>
     * FP2's geometry is first captured using transform feedback into the vertex format which shader packs expect, then drawn like vanilla chunk geometry. This
     * makes the pack's programs see the far terrain exactly like they see vanilla chunks: with per-vertex normals, block texture coordinates, lightmap
     * coordinates and block ids, and with all positions relative to the camera.
     *
     * @param args              the draw arguments
     * @param level             the detail level
     * @param pass              the render pass
     * @param selectedLocations the selected tile positions and their draw commands
     * @param shaderPack        the shader pack renderer
     */
    protected void drawShaderPack(DrawArguments args, int level, int pass, Map<TilePos, BakeStorage.Location[]> selectedLocations, ShaderPackCompat.Renderer shaderPack) {
        shaderPack.beginPass(level, pass);
        try {
            this.drawShaderPackTiles(level, pass, selectedLocations, shaderPack, args.shaderPackXfbProgram);
        } finally {
            shaderPack.endPass(level, pass);
        }
    }

    @Override
    public void drawShaderPackWater(@NonNull CameraState cameraState, @NonNull ShaderPackCompat.Renderer shaderPack, @NonNull DrawShaderProgram xfbProgram) {
        RenderIndex.DrawableMask drawableMask = this.drawableMask();
        if (!drawableMask.shouldDrawAnythingForLayer(RenderConstants.LAYER_TRANSPARENT)) {
            return;
        }

        //draw from the lowest detail level to the highest, so that the highest-detail tiles (which are drawn last) end up on top. fp2's regular
        //  transparent pass usually enforces this using the stencil buffer, but that isn't available in the shader pack's g-buffers.
        for (int level = drawableMask.maxLevelToDraw() - 1; level >= drawableMask.minLevelToDraw(); level--) {
            if (!drawableMask.shouldDrawAnythingForLevelLayer(level, RenderConstants.LAYER_TRANSPARENT)) {
                continue;
            }

            Map<TilePos, BakeStorage.Location[]> selectedLocations = this.selectedLocations[level];
            if (selectedLocations.isEmpty()) {
                continue;
            }

            shaderPack.beginWaterPass(level);
            try {
                this.drawShaderPackTiles(level, RenderConstants.LAYER_TRANSPARENT, selectedLocations, shaderPack, xfbProgram);
            } finally {
                shaderPack.endWaterPass(level);
            }
        }
    }

    /**
     * Draws the given tile locations using the shader pack's currently active terrain or water shader program.
     *
     * @param level             the detail level
     * @param pass              the render pass
     * @param selectedLocations the selected tile positions and their draw commands
     * @param shaderPack        the shader pack renderer
     * @param xfbProgram        the transform feedback program used to convert FP2's geometry into a shader pack compatible vertex format
     */
    protected void drawShaderPackTiles(int level, int pass, @NonNull Map<TilePos, BakeStorage.Location[]> selectedLocations, @NonNull ShaderPackCompat.Renderer shaderPack, @NonNull DrawShaderProgram xfbProgram) {
        OpenGL gl = this.gl;

        int modeEnum = this.bakeStorage.drawMode.mode();
        val indexFormat = this.bakeStorage.indexFormat.type();
        int indexType = indexFormat.type();
        int indexSize = indexFormat.size();
        boolean absoluteIndices = this.bakeStorage.absoluteIndices;

        int captureVao = this.shaderPackCaptureVao(shaderPack);
        int tilePosUniform = xfbProgram.uniformLocation(RenderConstants.TILE_POS_UNIFORM_NAME);
        int mcEntityUniform = xfbProgram.uniformLocation(RenderConstants.MC_ENTITY_UNIFORM_NAME);

        //shader packs use the mc_Entity attribute to detect special blocks. fp2's vertex format only stores a per-tile texture index, which can't be mapped
        //  back to a block state on the GPU, so a constant value is used for all vertices of a pass instead. the translucent pass only ever contains water,
        //  which is identified by its block id (8/9, as used by Chocapic13) as well as by a metadata value of 1 (as used by SEUS).
        boolean water = pass == RenderConstants.LAYER_TRANSPARENT;
        float mcEntityX = water ? (float) shaderPack.waterBlockAliasId() : 0.0f;
        float mcEntityY = water ? 1.0f : 0.0f;

        //save the state which we're going to modify. the shader pack's program is bound by the caller, and needs to be restored after every capture.
        int packProgram = gl.glGetInteger(GL_CURRENT_PROGRAM);
        int oldVao = gl.glGetInteger(GL_VERTEX_ARRAY_BINDING);
        boolean oldRasterizerDiscard = gl.glIsEnabled(GL_RASTERIZER_DISCARD);

        //gather the tiles which actually have geometry in this pass
        if (this.shaderPackTilePositions.length < selectedLocations.size()) {
            int length = Math.max(selectedLocations.size(), this.shaderPackTilePositions.length << 1);
            this.shaderPackTilePositions = new TilePos[length];
            this.shaderPackTileCommands = new BakeStorage.Location[length];
        }
        TilePos[] tilePositions = this.shaderPackTilePositions;
        BakeStorage.Location[] tileCommands = this.shaderPackTileCommands;
        int tileCount = 0;
        for (val entry : selectedLocations.entrySet()) {
            val command = entry.getValue()[pass];
            if (command != null) {
                tilePositions[tileCount] = entry.getKey();
                tileCommands[tileCount] = command;
                tileCount++;
            }
        }
        if (tileCount == 0) {
            return;
        }

        try {
            if (packProgram == 0) { //no shader program is bound, so there is nothing which the captured geometry could be drawn with
                return;
            }

            int fp2Vao = this.vaos.get(level, pass).id();
            int tileIndex = 0;
            while (tileIndex < tileCount) {
                //gather as many tiles as fit into a single batch. all of the tiles in a batch are captured one after another (transform feedback appends
                //  the emitted vertices to the capture buffer in draw order), and then drawn using a single draw call.
                int batchEnd = tileIndex;
                long batchVertices = 0L;
                do {
                    batchVertices += tileCommands[batchEnd].count;
                    batchEnd++;
                } while (batchEnd < tileCount && batchVertices + tileCommands[batchEnd].count <= SHADER_PACK_CAPTURE_BATCH_VERTICES);

                this.ensureShaderPackCaptureCapacity((int) batchVertices);

                //captures are written to the buffer at binding point 0
                gl.glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, this.shaderPackCaptureBuffer);
                gl.glBindVertexArray(fp2Vao);

                val xfbUniformSetter = xfbProgram.bindUnsafe();
                xfbUniformSetter.set4f(mcEntityUniform, mcEntityX, mcEntityY, 0.0f, 1.0f);

                //rasterizer discard must only be enabled for the capture itself. the captured geometry is drawn immediately afterwards, and that draw call
                //  has to be rasterized just like any other. a single transform feedback pass covers the whole batch, which is significantly cheaper than
                //  starting and stopping transform feedback once per tile.
                gl.glEnable(GL_RASTERIZER_DISCARD);
                gl.glBeginTransformFeedback(GL_TRIANGLES);
                try {
                    for (int i = tileIndex; i < batchEnd; i++) {
                        val pos = tilePositions[i];
                        val command = tileCommands[i];

                        //capture the tile's geometry in the vertex format used by vanilla chunks
                        xfbUniformSetter.set4i(tilePosUniform, pos.x(), pos.y(), pos.z(), pos.level());

                        if (absoluteIndices) {
                            gl.glDrawElements(modeEnum, command.count, indexType, command.firstIndex * (long) indexSize);
                        } else {
                            gl.glDrawElementsBaseVertex(modeEnum, command.count, indexType, command.firstIndex * (long) indexSize, command.baseVertex);
                        }
                    }
                } finally {
                    gl.glEndTransformFeedback();
                    gl.glDisable(GL_RASTERIZER_DISCARD);
                }

                //draw the captured geometry using the shader pack's program. the captured vertices are already relative to the camera, so the model-view
                //  matrix must be left untouched (just like vanilla chunk rendering, which performs the camera-relative translation on the CPU).
                gl.glUseProgram(packProgram);
                gl.glBindVertexArray(captureVao);
                gl.glDrawArrays(GL_TRIANGLES, 0, (int) batchVertices);

                tileIndex = batchEnd;
            }
        } finally {
            gl.glUseProgram(packProgram);
            gl.glBindVertexArray(oldVao);
            gl.glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
            if (oldRasterizerDiscard) {
                gl.glEnable(GL_RASTERIZER_DISCARD);
            } else {
                gl.glDisable(GL_RASTERIZER_DISCARD);
            }
        }
    }

    /**
     * Ensures that {@link #shaderPackCaptureBuffer} is large enough to hold the given number of vertices.
     *
     * @param vertices the number of vertices which need to fit into the buffer
     */
    protected void ensureShaderPackCaptureCapacity(int vertices) {
        if (vertices <= this.shaderPackCaptureCapacity) {
            return;
        }

        int capacity = Math.max(vertices, Math.max(this.shaderPackCaptureCapacity << 1, 16384));

        OpenGL gl = this.gl;
        int oldBuffer = gl.glGetInteger(GL_ARRAY_BUFFER_BINDING);
        try {
            gl.glBindBuffer(GL_ARRAY_BUFFER, this.shaderPackCaptureBuffer);
            gl.glBufferData(GL_ARRAY_BUFFER, capacity * (long) RenderConstants.SHADER_PACK_XFB_STRIDE, 0L, GL_DYNAMIC_COPY);
        } finally {
            gl.glBindBuffer(GL_ARRAY_BUFFER, oldBuffer);
        }

        this.shaderPackCaptureCapacity = capacity;
    }

    /**
     * Gets (and lazily creates) the vertex array object which exposes {@link #shaderPackCaptureBuffer} in the vertex format expected by shader packs.
     *
     * @param shaderPack the shader pack renderer
     * @return the vertex array object's id
     */
    protected int shaderPackCaptureVao(@NonNull ShaderPackCompat.Renderer shaderPack) {
        if (this.shaderPackCaptureVao >= 0) {
            return this.shaderPackCaptureVao;
        }

        OpenGL gl = this.gl;
        if (this.shaderPackCaptureBuffer < 0) {
            this.shaderPackCaptureBuffer = gl.glGenBuffer();
        }

        int oldVao = gl.glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int oldBuffer = gl.glGetInteger(GL_ARRAY_BUFFER_BINDING);
        int vao = gl.glGenVertexArray();
        try {
            gl.glBindVertexArray(vao);
            gl.glBindBuffer(GL_ARRAY_BUFFER, this.shaderPackCaptureBuffer);

            //the attribute indices and component types are the ones which vanilla chunk rendering uses for its fixed-function vertex attributes, as the
            //  pack's programs read those
            this.shaderPackCaptureAttribute(0, 3, GL_FLOAT, false, RenderConstants.SHADER_PACK_XFB_POS_OFFSET); //gl_Vertex
            this.shaderPackCaptureAttribute(2, 3, GL_BYTE, true, RenderConstants.SHADER_PACK_XFB_NORMAL_OFFSET); //gl_Normal
            this.shaderPackCaptureAttribute(3, 4, GL_UNSIGNED_BYTE, true, RenderConstants.SHADER_PACK_XFB_COLOR_OFFSET); //gl_Color
            this.shaderPackCaptureAttribute(8, 2, GL_FLOAT, false, RenderConstants.SHADER_PACK_XFB_UV_OFFSET); //gl_MultiTexCoord0
            this.shaderPackCaptureAttribute(9, 2, GL_UNSIGNED_SHORT, false, RenderConstants.SHADER_PACK_XFB_LIGHT_OFFSET); //gl_MultiTexCoord1 (lightmap coordinates)

            int entityAttribute = shaderPack.entityAttribute();
            if (entityAttribute >= 0) {
                this.shaderPackCaptureAttribute(entityAttribute, 2, GL_SHORT, false, RenderConstants.SHADER_PACK_XFB_ENTITY_OFFSET); //mc_Entity
            }
        } catch (Throwable t) {
            gl.glDeleteVertexArray(vao);
            throw t;
        } finally {
            gl.glBindBuffer(GL_ARRAY_BUFFER, oldBuffer);
            gl.glBindVertexArray(oldVao);
        }

        return this.shaderPackCaptureVao = vao;
    }

    private void shaderPackCaptureAttribute(int index, int size, int type, boolean normalized, int offset) {
        this.gl.glEnableVertexAttribArray(index);
        this.gl.glVertexAttribPointer(index, size, type, normalized, RenderConstants.SHADER_PACK_XFB_STRIDE, offset);
    }

    @Override
    public PosTechnique posTechnique() {
        return PosTechnique.UNIFORM;
    }

    @Override
    public Stats stats() {
        return new Stats(
                Stream.of(this.selectedLocations).mapToInt(Map::size).sum(),
                this.allLocations.size(),
                this.hiddenPositions.size(),
                -1, //TODO: count the number of culled commands?
                -1,
                this.bakeStorage.absoluteIndices ? "CPU culled, glDrawElements" : "CPU culled, glDrawElementsBaseVertex");
    }
}

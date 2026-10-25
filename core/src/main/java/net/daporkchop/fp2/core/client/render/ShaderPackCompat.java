/*
 * Adapted from The MIT License (MIT)
 *
 * Copyright (c) 2020-2026 DaPorkchop_
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software
 * is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.daporkchop.fp2.core.client.render;

/**
 * Compatibility hook which allows far terrain to be drawn through a third-party shader pack's own terrain shader program.
 * <p>
 * Shader packs expect the world to be rendered into their own g-buffer layout, which is different for every pack and therefore cannot be emulated by
 * FP2's own shaders. Instead, FP2's geometry is first captured using transform feedback into the same vertex format which Minecraft uses for vanilla
 * chunks, and then drawn through the pack's unmodified terrain shader program - so that the pack's lighting, textures and atmospheric effects are applied
 * to the far terrain exactly like they are to vanilla chunks.
 *
 * @author DaPorkchop_
 */
public final class ShaderPackCompat {
    /**
     * A renderer which is able to draw FP2's geometry using a shader pack's terrain shader programs.
     *
     * @author DaPorkchop_
     */
    public interface Renderer {
        /**
         * @return whether a shader pack is currently active
         */
        boolean isActive();

        /**
         * Gets the index of the vertex attribute which the shader pack's programs read the block id and metadata from ({@code mc_Entity}).
         *
         * @return the attribute index, or {@code -1} if the pack's programs don't read such an attribute
         */
        default int entityAttribute() {
            return -1;
        }

        /**
         * Gets the block alias ID for water (block ID 9) to pass in mc_Entity.x.
         */
        default int waterBlockAliasId() {
            return 9;
        }

        /**
         * Checks whether the pack's water shader program is available.
         * <p>
         * If it isn't, the translucent parts of the far terrain are drawn using the pack's regular terrain program instead (as opaque geometry), as drawing
         * them during the pack's water rendering phase would be pointless without a water program.
         *
         * @return whether the pack's water shader program is available
         */
        default boolean hasWaterProgram() {
            return true;
        }

        /**
         * Checks whether the pack is currently rendering its shadow map.
         * <p>
         * Only a small part of the world around the player is covered by the pack's shadow map, so the far terrain is only rendered up to the finest detail
         * level while this returns {@code true}.
         *
         * @return whether the pack is currently rendering its shadow map
         */
        default boolean isShadowPass() {
            return false;
        }

        /**
         * Called before drawing the tiles of the given detail level and render pass.
         *
         * @param level the detail level
         * @param pass  the render pass
         */
        void beginPass(int level, int pass);

        /**
         * Called after all tiles of the given detail level and render pass have been drawn.
         *
         * @param level the detail level
         * @param pass  the render pass
         */
        void endPass(int level, int pass);

        /**
         * Called before drawing the translucent tiles of the given detail level during the shader pack's water rendering phase.
         * <p>
         * Unlike {@link #beginPass(int, int)}, this is called while the pack's water shader program is active, after the pack's deferred pass has run. The
         * far terrain's opaque parts have already been drawn (and are therefore included in the pack's depth buffers) by then.
         *
         * @param level the detail level
         */
        void beginWaterPass(int level);

        /**
         * Called after all translucent tiles of the given detail level have been drawn during the shader pack's water rendering phase.
         *
         * @param level the detail level
         */
        void endWaterPass(int level);
    }

    private static volatile Renderer renderer;

    /**
     * Registers the renderer to use for shader pack compatibility.
     *
     * @param renderer the renderer, or {@code null} to disable shader pack compatibility
     */
    public static void setRenderer(Renderer renderer) {
        ShaderPackCompat.renderer = renderer;
    }

    /**
     * @return the registered renderer, or {@code null} if shader pack compatibility isn't available
     */
    public static Renderer renderer() {
        return renderer;
    }

    /**
     * @return whether a shader pack is currently active and its terrain programs are usable
     */
    public static boolean isActive() {
        Renderer renderer = ShaderPackCompat.renderer;
        return renderer != null && renderer.isActive();
    }
}

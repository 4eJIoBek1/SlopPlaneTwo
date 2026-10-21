/*
 * Adapted from The MIT License (MIT)
 *
 * Copyright (c) 2020-$today.year DaPorkchop_
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

#pragma once

//
//
// INPUTS
//
//

//the vertex shader (vert/common.vert) emits the same outputs which the fragment shader path uses, so they can simply be re-used here. note that geometry
//  shader inputs are arrays, and that the flat qualifiers must match the vertex shader's outputs.
in vec3 vs_out_pos[3];
in vec2 vs_out_light[3];

flat in vec3 vs_out_color[3];
flat in vec3 vs_out_base_pos[3];
flat in uint vs_out_state[3];

/**
 * A single vertex as emitted by the vertex shader.
 */
struct VsOut {
    vec3 pos;
    vec2 light;

    vec3 color;
    vec3 base_pos;
    uint state;
};

VsOut gs_in[3];

/**
 * Copies the geometry shader's input vertices into the {@link #gs_in} array.
 * <p>
 * This must be called at the beginning of {@code main()}.
 */
void gs_fetchInputs() {
    for (int i = 0; i < 3; i++) {
        gs_in[i].pos = vs_out_pos[i];
        gs_in[i].light = vs_out_light[i];

        gs_in[i].color = vs_out_color[i];
        gs_in[i].base_pos = vs_out_base_pos[i];
        gs_in[i].state = vs_out_state[i];
    }
}

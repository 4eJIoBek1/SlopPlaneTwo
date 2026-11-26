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
 *
 */

#pragma once

#include <"fp2:shaders/common.glsl">

#include <"fp2:shaders/util/debug_color_mode.glsl"> // FP2_DEBUG_COLOR_MODE_*

//
//
// OUTPUTS
//
//

out vec3 vs_out_pos;
out vec2 vs_out_light;

flat out vec3 vs_out_color;
flat out vec3 vs_out_base_pos;
flat out uint vs_out_state;
flat out int vs_out_level;

//
//
// CONSTANTS
//
//

#if FP2_DEBUG_COLOR_MODE == FP2_DEBUG_COLOR_MODE_LEVEL || FP2_DEBUG_COLOR_MODE == FP2_DEBUG_COLOR_MODE_POSITION
const vec3[16] DEBUG_COLORS = vec3[](
vec3(0., 1., 0.), vec3(1., 1., 0.), vec3(1., 0., 0.), vec3(0., 0., 1.),
vec3(1., 0., 1.), vec3(0., 1., 1.), vec3(.5), vec3(1.),
vec3(.5, 1., 0.), vec3(0., 1., .5), vec3(1., .5, 0.), vec3(1., 0., .5),
vec3(0., .5, 1.), vec3(.5, 0., 1.), vec3(5., 1., .5), vec3(5., 1., .5)
);
#endif

//
//
// UTILITIES
//
//

vec3 computeVertexColor(vec3 va_color, ivec4 tile_position) {
#if FP2_DEBUG_COLOR_MODE == FP2_DEBUG_COLOR_MODE_DISABLED
    //debug colors aren't enabled, we can just use the standard color
    return va_color;
#elif FP2_DEBUG_COLOR_MODE == FP2_DEBUG_COLOR_MODE_LEVEL
    return DEBUG_COLORS[tile_position.w];
#elif FP2_DEBUG_COLOR_MODE == FP2_DEBUG_COLOR_MODE_POSITION
    ivec4 i = (tile_position & 1) << ivec4(3, 2, 1, 0);
    return DEBUG_COLORS[i.x | i.y | i.z | i.w];
#elif FP2_DEBUG_COLOR_MODE == FP2_DEBUG_COLOR_MODE_NORMAL
    //we don't care about this, it won't be used anyway
    return va_color;
#else
#   error "FP2_DEBUG_COLOR_MODE is set to an unsupported value!"
#endif
}

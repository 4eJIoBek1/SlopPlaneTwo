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

#include <"fp2:shaders/common.glsl"> // diffuseLight(), normalToFaceIndex(), texUvFactor()
#include <"fp2:shaders/util/texture_uvs_technique.glsl"> // TexQuadList, PackedBakedQuad, stateAndFaceIndexToTexQuadList(), quadIndexToQuad()
#include <"fp2:shaders/geom/common.geom"> // gs_in[]

//
//
// GEOMETRY SHADER-SPECIFIC CONFIGURATION
//
//

layout(triangles) in;
layout(triangle_strip, max_vertices = 3) out;

//
//
// OUTPUTS
//
//

//these are captured using transform feedback, and then drawn as if they were regular vanilla chunk vertices (in the format expected by shader packs).
//  all of them are vec4s so that the transform feedback buffer layout is unambiguous (16 bytes per variable, in declaration order).
out vec4 xfb_pos; //xyz = camera-relative position
out vec4 xfb_color; //rgba = vertex color (texture tint * face shading)
out vec4 xfb_uv; //xy = texture coordinates in the block texture atlas
out vec4 xfb_normal; //xyz = face normal
out vec4 xfb_light; //xy = lightmap coordinates (as 0-255 floats, like vanilla)
out vec4 xfb_entity; //x = block id, y = block metadata (as used by shader packs)

//
//
// UNIFORMS
//
//

//the value of the mc_Entity vertex attribute (x = block id, y = block metadata). fp2 only stores a per-tile texture index in its vertex format, which can't
//  be mapped back to a block state on the GPU, so the render pass provides a single value for all vertices instead.
uniform vec4 u_mcEntity;

//
//
// CONSTANTS
//
//

//the vertex at which fp2's baked quad attributes are valid (the same convention which is used by the fragment shader path via flat interpolation)
const int PROVOKING = 2;

//
//
// UTILITIES
//
//

vec3 normalVector() {
    //compute the face normal from the triangle's winding order. vanilla terrain triangles are counter-clockwise when viewed from the front, and fp2's
    //  own fragment shader path ends up with a normal vector pointing in the same direction (as it is computed from the screen-space winding order)
    return normalize(cross(gs_in[1].pos - gs_in[0].pos, gs_in[2].pos - gs_in[0].pos));
}

void main() {
    gs_fetchInputs();

    vec3 normal = normalVector();
    uint state = gs_in[PROVOKING].state;

    //get the texture data for the block face this triangle belongs to
    TexQuadList list = stateAndFaceIndexToTexQuadList(state, normalToFaceIndex(normal));
    PackedBakedQuad quad = quadIndexToQuad(list.first);
    vec4 quadCoords = quad.coords;

    //compute the texture coordinates at each vertex. the texture origin is located at the provoking vertex, exactly like in the fragment shader path,
    //  but since we can't repeat the texture per fragment here, the whole quad is mapped to a single texture repetition instead.
    vec2 factors[3];
    vec2 factorMin = vec2(1e30);
    for (int i = 0; i < 3; i++) {
        factors[i] = texUvFactor(normal, gs_in[i].pos, gs_in[PROVOKING].pos);
        factorMin = min(factorMin, factors[i]);
    }
    vec2 factorOffset = floor(factorMin);

    //fp2's vertex colors already contain ambient occlusion and the block's tint, we only need to apply the face shading and the quad's texture tint
    //  (the latter is baked into the vertex color by the fragment shader path as well)
    vec3 vertexColor = max(gs_in[PROVOKING].color, vec3(quad.tint)) * diffuseLight(normal);

    for (int i = 0; i < 3; i++) {
        xfb_pos = vec4(gs_in[i].pos, 1.0);
        xfb_color = vec4(vertexColor, 1.0);
        xfb_uv = vec4(mix(quadCoords.st, quadCoords.pq, clamp(factors[i] - factorOffset, 0.0, 1.0)), 0.0, 1.0);
        xfb_normal = vec4(normal, 0.0);
        xfb_light = vec4(gs_in[i].light * 255.0, 0.0, 1.0); //fp2 stores the lightmap coordinates normalized, but vanilla (and shader packs) use the 0-255 range
        xfb_entity = u_mcEntity;

        gl_Position = gl_in[i].gl_Position;
        EmitVertex();
    }
    EndPrimitive();
}

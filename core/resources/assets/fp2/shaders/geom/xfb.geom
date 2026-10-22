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
//  the variables are packed as tightly as possible while keeping the per-vertex layout well-defined: the total stride is 40 bytes, laid out as
//  follows (see the SHADER_PACK_XFB_*_OFFSET constants in RenderConstants):
//
//      @0  pos      vec4   (xyz = camera-relative position, w unused)
//      @16 color    uvec2  (x = rgba as four unsigned bytes, y = normal as three signed bytes)
//      @24 uv       vec2   (texture coordinates in the block texture atlas)
//      @32 misc     uvec2  (x = lightmap coordinates as two unsigned shorts, y = block id and metadata as four unsigned bytes)
//
//  the uvec2 variables rely on little-endian byte order, which is the only architecture fp2 runs on anyway.
out vec4 xfb_pos; //xyz = camera-relative position
out uvec2 xfb_colorNormal; //x = rgba = vertex color (texture tint * face shading), y = xyz = face normal
out vec2 xfb_uv; //xy = texture coordinates in the block texture atlas
out uvec2 xfb_misc; //x = xy = lightmap coordinates (as 0-255 integers, like vanilla), y = xyzw = block id and metadata (mc_Entity)

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

//packs a value in the range [0, 1] into an unsigned byte
uint packUnorm8(float value) {
    return uint(round(clamp(value, 0.0, 1.0) * 255.0));
}

//packs a value in the range [-1, 1] into a signed byte (the result is the byte's bit pattern, as an unsigned integer)
uint packSnorm8(float value) {
    return uint(int(round(clamp(value, -1.0, 1.0) * 127.0))) & 0xFFu;
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

    //pack the values which are constant for the whole triangle
    uint packedColor = packUnorm8(vertexColor.r) | (packUnorm8(vertexColor.g) << 8) | (packUnorm8(vertexColor.b) << 16) | (255u << 24);
    uint packedNormal = packSnorm8(normal.x) | (packSnorm8(normal.y) << 8) | (packSnorm8(normal.z) << 16);
    uint packedEntity = (uint(round(clamp(u_mcEntity.x, 0.0, 255.0))) & 0xFFu)
            | ((uint(round(clamp(u_mcEntity.y, 0.0, 255.0))) & 0xFFu) << 8)
            | ((uint(round(clamp(u_mcEntity.z, 0.0, 255.0))) & 0xFFu) << 16)
            | ((uint(round(clamp(u_mcEntity.w, 0.0, 255.0))) & 0xFFu) << 24);

    for (int i = 0; i < 3; i++) {
        //fp2 stores the lightmap coordinates normalized, but vanilla (and shader packs) use the 0-255 range
        uint packedLight = packUnorm8(gs_in[i].light.x) | (packUnorm8(gs_in[i].light.y) << 16);

        xfb_pos = vec4(gs_in[i].pos, 1.0);
        xfb_colorNormal = uvec2(packedColor, packedNormal);
        xfb_uv = mix(quadCoords.st, quadCoords.pq, clamp(factors[i] - factorOffset, 0.0, 1.0));
        xfb_misc = uvec2(packedLight, packedEntity);

        gl_Position = gl_in[i].gl_Position;
        EmitVertex();
    }
    EndPrimitive();
}

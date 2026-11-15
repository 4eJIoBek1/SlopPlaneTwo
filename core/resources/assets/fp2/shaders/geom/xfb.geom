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
//

layout(triangles) in;
layout(triangle_strip, max_vertices = 3) out;

//
//
// OUTPUTS
//
//
//

//these are captured using transform feedback, and then drawn as if they were regular vanilla chunk vertices (in the format expected by shader packs).
//  the variables are packed as tightly as possible while keeping the per-vertex layout well-defined: the total stride is 56 bytes, laid out as
//  follows (see the SHADER_PACK_XFB_*_OFFSET constants in RenderConstants):
//
//      @0  pos      vec4   (xyz = camera-relative position, w unused)
//      @16 color    uvec2  (x = rgba as four unsigned bytes, y = normal as three signed bytes)
//      @24 uv       vec2   (texture coordinates in the block texture atlas)
//      @32 misc     uvec2  (x = lightmap coordinates as two unsigned shorts, y = block id and metadata as four unsigned bytes)
//      @40 tangent  vec4   (xyz = tangent vector, w = handedness (+1.0))
//
//  the uvec2 variables rely on little-endian byte order, which is the only architecture fp2 runs on anyway.
out vec4 xfb_pos; //xyz = camera-relative position
out uvec2 xfb_colorNormal; //x = rgba = vertex color (texture tint), y = xyz = face normal
out vec2 xfb_uv; //xy = texture coordinates in the block texture atlas
out uvec2 xfb_misc; //x = xy = lightmap coordinates (as 0-255 integers, like vanilla), y = xyzw = block id and metadata (mc_Entity)
out vec4 xfb_tangent; //xyz = tangent vector, w = handedness (+1.0)

//
//
// UNIFORMS
//
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
//

//the vertex at which fp2's baked quad attributes are valid (the same convention which is used by the fragment shader path via flat interpolation)
const int PROVOKING = 2;

//
//
// UTILITIES
//
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

vec4 computeTangent(vec3 normal, vec2 uv0, vec2 uv1, vec2 uv2) {
    vec3 p1 = gs_in[1].pos - gs_in[0].pos;
    vec3 p2 = gs_in[2].pos - gs_in[0].pos;
    vec2 duv1 = uv1 - uv0;
    vec2 duv2 = uv2 - uv0;
    float det = duv1.x * duv2.y - duv2.x * duv1.y;
    vec3 tangent;
    vec3 bitangent;
    if (abs(det) > 1e-6) {
        tangent = (p1 * duv2.y - p2 * duv1.y) / det;
        bitangent = (p2 * duv1.x - p1 * duv2.x) / det;
    } else {
        vec3 n = abs(normal);
        if (n.x > n.y && n.x > n.z) {
            tangent = vec3(0.0, 0.0, normal.x > 0.0 ? -1.0 : 1.0);
        } else if (n.z > n.x && n.z > n.y) {
            tangent = vec3(normal.z > 0.0 ? 1.0 : -1.0, 0.0, 0.0);
        } else {
            tangent = vec3(1.0, 0.0, 0.0);
        }
        bitangent = cross(normal, tangent);
    }
    tangent = tangent - normal * dot(tangent, normal);
    float lenSq = dot(tangent, tangent);
    if (lenSq > 1e-6) {
        tangent *= inversesqrt(lenSq);
    } else {
        tangent = abs(normal.y) < 0.999 ? normalize(cross(normal, vec3(0.0, 1.0, 0.0))) : normalize(cross(normal, vec3(1.0, 0.0, 0.0)));
    }
    float handedness = dot(cross(tangent, normal), bitangent) < 0.0 ? -1.0 : 1.0;
    return vec4(tangent, handedness);
}

void main() {
    gs_fetchInputs();

    vec3 normal = normalVector();
    uint state = gs_in[PROVOKING].state;

    //get the texture data for the block face this triangle belongs to
    TexQuadList list = stateAndFaceIndexToTexQuadList(state, normalToFaceIndex(normal));
    PackedBakedQuad quad = quadIndexToQuad(list.first);

    vec4 quadCoords = quad.coords;

    //compute the texture coordinates at each vertex. the texture origin is located at the provoking vertex.
    //in a quad split into two triangles, both triangles share the provoking vertex (at index 2) and the opposite corner.
    //deriving the quad's UV bounding box from the provoking vertex and opposite corner relative to provoking
    //guarantees identical UV bounds and scale for both triangles, completely eliminating diagonal tears while
    //smoothly scaling across the entire LOD cell size.
    vec2 factors[3];
    for (int i = 0; i < 3; i++) {
        factors[i] = texUvFactor(normal, gs_in[i].pos, gs_in[PROVOKING].pos);
    }

    vec3 d0 = gs_in[0].pos - gs_in[PROVOKING].pos;
    vec3 d1 = gs_in[1].pos - gs_in[PROVOKING].pos;
    int opp = dot(d0, d0) > dot(d1, d1) ? 0 : 1;
    vec2 oppRelFactor = factors[opp] - factors[PROVOKING];
    vec2 minCorner = min(vec2(0.0), oppRelFactor);
    vec2 maxCorner = max(vec2(0.0), oppRelFactor);
    vec2 span = max(maxCorner - minCorner, vec2(1.0));

    // Inset sprite bounds by 1/32 of a sprite (~0.5 texel at 16x16) to prevent atlas bleeding at higher mip levels
    vec2 uvInset = (quadCoords.pq - quadCoords.st) * (1.0 / 32.0);
    vec2 safeMinUV = quadCoords.st + uvInset;
    vec2 safeMaxUV = quadCoords.pq - uvInset;

    //fp2's vertex colors already contain ambient occlusion and the block's tint, we only need to apply the quad's texture tint.
    //the per-face diffuse shading is baked into the vertex color as well, exactly like vanilla does it (see
    //BlockModelRenderer#renderQuadsSmooth, which multiplies the vertex color by LightUtil.diffuseLight(face)).
    //without this, the far terrain is significantly brighter than the vanilla chunks around it when a shader pack is
    //active (the pack computes its own lighting on top of the albedo), while fp2's own shader path (which does apply
    //the diffuse) looks correct.
    //note that this has nothing to do with shader packs' "general grass fix" heuristics (e.g. SEUS Renewed's
    //GENERAL_GRASS_FIX in gbuffers_terrain.vsh), which are triggered by non-axis-aligned normals, not by the
    //vertex color.
    vec3 vertexColor = max(gs_in[PROVOKING].color, vec3(quad.tint)) * diffuseLight(normal);

    //pack the values which are constant for the whole triangle
    uint packedColor = packUnorm8(vertexColor.r) | (packUnorm8(vertexColor.g) << 8) | (packUnorm8(vertexColor.b) << 16) | (255u << 24);
    uint packedNormal = packSnorm8(normal.x) | (packSnorm8(normal.y) << 8) | (packSnorm8(normal.z) << 16);
    uint packedEntity = (uint(int(round(u_mcEntity.x))) & 0xFFFFu)
            | ((uint(int(round(u_mcEntity.y))) & 0xFFFFu) << 16);
    vec4 tangentOut = computeTangent(normal, factors[0], factors[1], factors[2]);

    for (int i = 0; i < 3; i++) {
        //fp2 stores the lightmap coordinates normalized, but vanilla (and shader packs) use the 0-255 range.
        //vanilla actually emits 0..240; match it so packs' lmcoord math agrees with vanilla chunks
        uint packedLight = packUnorm8(gs_in[i].light.x * (240.0 / 255.0))
                         | (packUnorm8(gs_in[i].light.y * (240.0 / 255.0)) << 16);

        xfb_pos = vec4(gs_in[i].pos, 1.0);
        xfb_colorNormal = uvec2(packedColor, packedNormal);
        xfb_uv = mix(safeMinUV, safeMaxUV, clamp((factors[i] - factors[PROVOKING] - minCorner) / span, 0.0, 1.0));
        xfb_misc = uvec2(packedLight, packedEntity);
        xfb_tangent = tangentOut;

        gl_Position = gl_in[i].gl_Position;
        EmitVertex();
    }
    EndPrimitive();
}

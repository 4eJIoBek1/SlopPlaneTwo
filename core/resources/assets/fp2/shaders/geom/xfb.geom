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

//the value of the mc_Entity vertex attribute (x = block id, y = block metadata). fp2 only stores a per-tile texture index in its vertex format, which can't
//  be mapped back to a block state on the GPU, so the render pass provides a single value for all vertices instead.
uniform vec4 u_mcEntity;
//1.0 if vanilla per-face diffuse shading ("old lighting") is enabled for the active shader pack, 0.0 otherwise.
uniform float u_oldLighting;
//0 = emit base quad (list.first), non-zero = emit overlay quad (list.first + 1) for multi-layer vertical sides.
uniform int u_overlayPass;

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
    vec3 n = cross(gs_in[1].pos - gs_in[0].pos, gs_in[2].pos - gs_in[0].pos);
    float lenSq = dot(n, n);
    return lenSq > 1e-12 ? n * inversesqrt(lenSq) : vec3(0.0, 1.0, 0.0);
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
    uint faceIndex = normalToFaceIndex(normal);
    TexQuadList list = stateAndFaceIndexToTexQuadList(state, faceIndex);
    bool isSideMultiLayer = faceIndex != 1u && faceIndex != 4u && (list.last - list.first > 1u);
    if (u_overlayPass != 0) {
        if (!isSideMultiLayer || abs(normal.y) > 0.01) {
            //emit a degenerate zero-area triangle with a valid unit normal so vertex count stays identical to the base pass
            for (int i = 0; i < 3; i++) {
                xfb_pos = vec4(0.0);
                xfb_colorNormal = uvec2(0u, 127u << 8);
                xfb_uv = vec2(0.0);
                xfb_misc = uvec2(0u);
                xfb_tangent = vec4(1.0, 0.0, 0.0, 1.0);
                gl_Position = vec4(0.0);
                EmitVertex();
            }
            EndPrimitive();
            return;
        }
    } else if (isSideMultiLayer && abs(normal.y) > 0.01) {
        //multi-layer side faces (e.g. grass_side + grass_side_overlay) represent a 1-block vertical cross-section and look
        //  wrong on sloped rough/LOD terrain. keep them only for truly vertical exact faces; use top/bottom on slopes instead.
        faceIndex = normal.y > 0.35 ? 1u : 4u;
        list = stateAndFaceIndexToTexQuadList(state, faceIndex);
    }
    PackedBakedQuad quad = quadIndexToQuad(u_overlayPass != 0 ? list.first + 1u : list.first);

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
    //per-face diffuse shading is only baked when the active shader pack uses OptiFine's oldLighting (e.g. SEUS Renewed);
    //packs with oldLighting=false (e.g. Chocapic13, BSL, Sildurs) expect unshaded gl_Color and compute directional lighting themselves.
    float faceShade = mix(1.0, diffuseLight(normal), u_oldLighting);
    vec3 vertexColor = max(gs_in[PROVOKING].color, vec3(quad.tint)) * faceShade;

    //pack the values which are constant for the whole triangle
    uint packedColor = packUnorm8(vertexColor.r) | (packUnorm8(vertexColor.g) << 8) | (packUnorm8(vertexColor.b) << 16) | (255u << 24);
    uint packedNormal = packSnorm8(normal.x) | (packSnorm8(normal.y) << 8) | (packSnorm8(normal.z) << 16);
    uint packedEntity = (uint(int(round(u_mcEntity.x))) & 0xFFFFu)
            | ((uint(int(round(u_mcEntity.y))) & 0xFFFFu) << 16);
    vec4 tangentOut = computeTangent(normal, factors[0], factors[1], factors[2]);

    for (int i = 0; i < 3; i++) {
        //VoxelBaker packs (L << 4) | 8 as normalized unorm8 (8..248); subtract 8 to recover vanilla's exact 0..240 (L * 16)
        uint blockLight = uint(clamp(round(gs_in[i].light.x * 255.0 - 8.0), 0.0, 240.0));
        uint skyLight = uint(clamp(round(gs_in[i].light.y * 255.0 - 8.0), 0.0, 240.0));
        uint packedLight = blockLight | (skyLight << 16);

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

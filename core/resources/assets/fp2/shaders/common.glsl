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

//
//
// MACROS
//
//

// FP2 constants

//the following macros is defined from java code:
//#define T_SHIFT (uint)

#if !defined(T_SHIFT)
#error T_SHIFT must be set!
#endif

#define T_MASK ((1 << T_SHIFT) - 1)
#define T_VOXELS (1 << T_SHIFT)
#define T_VERTS (T_VOXELS + 1)

// FP2 detail level constants

//the following macro may be defined from java code:
//#define LEVEL_0 (bool)

#if !defined(LEVEL_0)
//default value is false
#define LEVEL_0 (0)
#endif

//
//
// UTILITIES
//
//

// lighting

float diffuseLight(vec3 normal) {
    //compute all component values in parallel (possibly more likely to be vectorized better)
    vec3 values = (normal * normal) * (normal * vec3(0., .25, 0.) + vec3(.6, .75, .8));

    //add them together and prevent them from getting too high
    return min(values.x + values.y + values.z, 1.);

    // equivalent code:
    //return min(normal.x * normal.x * .6 + normal.y * normal.y * ((3. + normal.y) / 4.) + normal.z * normal.z * .8, 1.);
}

// vector math

uint normalToFaceIndex(vec3 normal)  {
    vec3 n = abs(normal);

    //component-wise mask. one lane is set (the lane whose component has the greatest absolute value)
    ivec3 axisMask = -ivec3(greaterThan(n, max(n.yxx, n.zzy)));

    //components set to 3 if the corresponding component in the normal vector is negative, 0 otherwise
    ivec3 negativeOffset = ivec3(lessThanEqual(normal, vec3(0.))) * 3;

    //the base offset to apply per component
    const ivec3 base = ivec3(0, 1, 2);

    //contains the final output value for each component, or 0 if the component isn't the greatest
    ivec3 values = (base + negativeOffset) & axisMask;

    //the maximum component value will be the only one that wasn't masked to zero
    return uint(max(values.x, max(values.y, values.z)));

    // equivalent code:
    /*if (n.y > n.x && n.y > n.z)  {
        return normal.y < 0. ? 4 : 1;
    } else if (n.z > n.x && n.z > n.y) {
        return normal.z < 0. ? 5 : 2;
    } else {
        return normal.x < 0. ? 3 : 0;
    }*/
}

// texture coordinates

/**
 * Computes the texture coordinate factor for a position on the given face, relative to the given base position.
 * <p>
 * The returned value is in units of texture repetitions: it is the offset from the face's texture origin (which is located at the base position), measured
 * along the face's two texture axes. It is a linear function of the position, and can therefore be interpolated.
 *
 * @param normal  the face normal
 * @param pos     the position to compute the texture coordinate factor for
 * @param basePos the position of the face's texture origin (usually the provoking vertex' position)
 */
vec2 texUvFactor(vec3 normal, vec3 pos, vec3 basePos) {
    vec3 delta = pos - basePos;

    vec3 s = sign(normal);
    normal = -abs(normal);

    //does this have some special name? i have no idea, but i'm pretty proud of it
    vec2 uv_factor = vec2(0.);
    uv_factor += vec2(1. - delta.x, -s.y * delta.z) * normal.y;
    uv_factor += vec2(-s.z * delta.x, delta.y) * normal.z;
    uv_factor += vec2(s.x * delta.z, delta.y) * normal.x;

    return uv_factor;
}

#include <"fp2:shaders/common.glsl">
#include <"fp2:shaders/util/camera_state_uniforms.glsl">
#include <"fp2:shaders/util/vanilla_renderability.glsl">

in vec3 vs_out_pos;
flat in int vs_out_level;

void main() {
    if (vs_out_level > 0) {
        ivec3 fragChunkPos = (u_positionFloor + ivec3(floor(vs_out_pos + u_positionFrac))) >> T_SHIFT;
        if (isVanillaRenderableLevel0(fragChunkPos)) {
            discard;
        }
    }
}

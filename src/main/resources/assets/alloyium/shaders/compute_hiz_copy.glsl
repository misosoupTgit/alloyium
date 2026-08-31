#version 430
layout(local_size_x = 8, local_size_y = 8) in;

// Copy scene depth → Hi-Z mip 0, optionally max-downsampling by uScale.
layout(binding = 0) uniform sampler2D uDepth;
layout(r32f, binding = 1) writeonly uniform image2D uHiz0;

uniform ivec2 uSize;
uniform int uScale; // 1=full, 2=half, ...

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= uSize.x || p.y >= uSize.y) {
        return;
    }
    int scale = max(uScale, 1);
    ivec2 base = p * scale;
    float m = 0.0;
    for (int y = 0; y < scale; ++y) {
        for (int x = 0; x < scale; ++x) {
            m = max(m, texelFetch(uDepth, base + ivec2(x, y), 0).r);
        }
    }
    imageStore(uHiz0, p, vec4(m, 0.0, 0.0, 0.0));
}

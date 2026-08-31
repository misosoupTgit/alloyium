#version 430
layout(local_size_x = 8, local_size_y = 8) in;

// Hi-Z mip: max of 2×2 (ZO depth: 0=near, 1=far). Conservative occluder far bound.
layout(r32f, binding = 0) readonly uniform image2D uSrc;
layout(r32f, binding = 1) writeonly uniform image2D uDst;

uniform ivec2 uDstSize;
uniform ivec2 uSrcSize;

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= uDstSize.x || p.y >= uDstSize.y) {
        return;
    }
    ivec2 s = p * 2;
    float m = imageLoad(uSrc, clamp(s, ivec2(0), uSrcSize - 1)).r;
    m = max(m, imageLoad(uSrc, clamp(s + ivec2(1, 0), ivec2(0), uSrcSize - 1)).r);
    m = max(m, imageLoad(uSrc, clamp(s + ivec2(0, 1), ivec2(0), uSrcSize - 1)).r);
    m = max(m, imageLoad(uSrc, clamp(s + ivec2(1, 1), ivec2(0), uSrcSize - 1)).r);
    // Odd edges: include the leftover row/col
    if ((s.x + 2) < uSrcSize.x) {
        m = max(m, imageLoad(uSrc, clamp(s + ivec2(2, 0), ivec2(0), uSrcSize - 1)).r);
        m = max(m, imageLoad(uSrc, clamp(s + ivec2(2, 1), ivec2(0), uSrcSize - 1)).r);
    }
    if ((s.y + 2) < uSrcSize.y) {
        m = max(m, imageLoad(uSrc, clamp(s + ivec2(0, 2), ivec2(0), uSrcSize - 1)).r);
        m = max(m, imageLoad(uSrc, clamp(s + ivec2(1, 2), ivec2(0), uSrcSize - 1)).r);
    }
    imageStore(uDst, p, vec4(m, 0.0, 0.0, 0.0));
}

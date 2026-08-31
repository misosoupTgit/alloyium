#version 430
layout(local_size_x = 64) in;

// Compute B' — Indirect command generation ONLY. Never touch vertex attributes.
struct MeshletHeader {
    uint  vertexOffset;
    uint  vertexCount;
    uint  primitiveOffset;
    uint  primitiveCount;
    vec4  boundingSphere;
    vec4  normalCone;
};

struct DrawElementsIndirectCommand {
    uint count;
    uint instanceCount;
    uint firstIndex;
    int  baseVertex;
    uint baseInstance;
};

layout(std430, binding = 0) readonly buffer Meshlets {
    MeshletHeader meshlets[];
};

layout(std430, binding = 1) readonly buffer VisibleIds {
    uint visibleMeshletIds[];
};

layout(std430, binding = 2) readonly buffer Counters {
    uint visibleCount;
};

layout(std430, binding = 3) writeonly buffer OutCommands {
    DrawElementsIndirectCommand cmds[];
};

void main() {
    uint visIdx = gl_GlobalInvocationID.x;
    if (visIdx >= visibleCount) {
        // Absorb excess dispatches as no-op draws (DESIGN §4)
        cmds[visIdx].count         = 0u;
        cmds[visIdx].instanceCount = 0u;
        cmds[visIdx].firstIndex    = 0u;
        cmds[visIdx].baseVertex    = 0;
        cmds[visIdx].baseInstance  = 0u;
        return;
    }

    uint meshletId = visibleMeshletIds[visIdx];
    MeshletHeader m = meshlets[meshletId];

    // Offset transcription only — no vertex data
    cmds[visIdx].count         = m.primitiveCount * 3u;
    cmds[visIdx].instanceCount = 1u;
    cmds[visIdx].firstIndex    = m.primitiveOffset;
    cmds[visIdx].baseVertex    = int(m.vertexOffset);
    cmds[visIdx].baseInstance  = 0u;
}

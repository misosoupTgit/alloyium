#version 430
layout(local_size_x = 64) in;

// Compute A (R2) — Frustum + Normal Cone + shared Prefix Sum.
// Debug counters in binding=2 for CPU readback. Never touches vertex attributes.
struct MeshletHeader {
    uint  vertexOffset;
    uint  vertexCount;
    uint  primitiveOffset;
    uint  primitiveCount;
    vec4  boundingSphere;
    vec4  normalCone; // xyz = axis, w = cos(halfAngle); w > 1 disables cone
};

layout(std430, binding = 0) readonly buffer Meshlets {
    MeshletHeader meshlets[];
};

layout(std430, binding = 1) writeonly buffer VisibleIds {
    uint visibleMeshletIds[];
};

layout(std430, binding = 2) buffer Counters {
    uint visibleCount;
    uint frustumCulled;
    uint coneCulled;
    uint _pad;
};

layout(std430, binding = 3) writeonly buffer DispatchArgs {
    uint numGroupsX;
    uint numGroupsY;
    uint numGroupsZ;
};

uniform vec4 uFrustumPlanes[6];
uniform vec3 uCameraPos;
uniform uint uMeshletCount;
uniform uint uEnableFrustum; // 0/1
uniform uint uEnableCone;    // 0/1

shared uint localFlags[64];
shared uint groupBase;

bool frustumCull(vec4 sphere) {
    // Camera-relative centers: Embeddium VS uses (world - camera) before modelView.
    vec3 center = sphere.xyz - uCameraPos;
    for (int i = 0; i < 6; ++i) {
        if (dot(uFrustumPlanes[i].xyz, center) + uFrustumPlanes[i].w < -sphere.w) {
            return true;
        }
    }
    return false;
}

bool coneCull(vec4 cone, vec3 sphereCenter) {
    if (cone.w > 1.0) {
        return false;
    }
    vec3 viewDir = normalize(sphereCenter - uCameraPos);
    return dot(cone.xyz, viewDir) > cone.w;
}

void main() {
    uint tid = gl_LocalInvocationID.x;
    uint id = gl_GlobalInvocationID.x;

    bool visible = false;
    if (id < uMeshletCount) {
        MeshletHeader m = meshlets[id];
        bool culled = false;

        if (uEnableFrustum != 0u && frustumCull(m.boundingSphere)) {
            atomicAdd(frustumCulled, 1u);
            culled = true;
        } else if (uEnableCone != 0u && coneCull(m.normalCone, m.boundingSphere.xyz)) {
            atomicAdd(coneCulled, 1u);
            culled = true;
        }

        visible = !culled;
    }

    localFlags[tid] = visible ? 1u : 0u;
    barrier();

    for (uint offset = 1u; offset < 64u; offset <<= 1u) {
        uint val = (tid >= offset) ? localFlags[tid - offset] : 0u;
        barrier();
        localFlags[tid] += val;
        barrier();
    }

    if (tid == 63u) {
        groupBase = atomicAdd(visibleCount, localFlags[63u]);
    }
    barrier();

    if (visible) {
        uint exclusiveScan = localFlags[tid] - 1u;
        visibleMeshletIds[groupBase + exclusiveScan] = id;
    }

    if (tid == 0u) {
        uint groups = (max(visibleCount, 1u) + 63u) / 64u;
        numGroupsX = groups;
        numGroupsY = 1u;
        numGroupsZ = 1u;
    }
}

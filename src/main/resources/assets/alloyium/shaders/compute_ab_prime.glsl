#version 430
layout(local_size_x = 64) in;

// R5: Frustum + Cone + Hi-Z occlusion + prefix-sum → Indirect cmds (A+B').
// Never touches vertex attributes.
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

layout(std430, binding = 1) writeonly buffer VisibleIds {
    uint visibleMeshletIds[];
};

layout(std430, binding = 2) buffer Counters {
    uint visibleCount;
    uint frustumCulled;
    uint coneCulled;
    uint occlusionCulled;
};

layout(std430, binding = 3) writeonly buffer OutCommands {
    DrawElementsIndirectCommand cmds[];
};

layout(binding = 4) uniform sampler2D uHiZ;

uniform vec4 uFrustumPlanes[6];
uniform vec3 uCameraPos;
uniform uint uMeshletCount;
uniform uint uEnableFrustum;
uniform uint uEnableCone;
uniform uint uEnableHiz;
uniform float uHizBias;
uniform mat4 uViewProj;
uniform vec2 uViewport;
uniform int uHizMaxLevel;

shared uint localFlags[64];
shared uint groupBase;

bool frustumCull(vec4 sphere) {
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

// ZO depth: 0=near, 1=far. Hi-Z stores max depth. Culled if nearestZ > hiz.
bool hizCull(vec4 sphere) {
    if (uEnableHiz == 0u) {
        return false;
    }
    vec3 center = sphere.xyz - uCameraPos;
    float r = sphere.w;
    if (length(center) <= r + 2.0) {
        return false;
    }

    vec2 uvMin = vec2( 999.0);
    vec2 uvMax = vec2(-999.0);
    float zNear = 1.0;
    for (int i = 0; i < 8; ++i) {
        vec3 p = center + r * vec3(
            ((i & 1) != 0) ? 1.0 : -1.0,
            ((i & 2) != 0) ? 1.0 : -1.0,
            ((i & 4) != 0) ? 1.0 : -1.0);
        vec4 clip = uViewProj * vec4(p, 1.0);
        if (clip.w <= 1e-4) {
            return false;
        }
        vec3 ndc = clip.xyz / clip.w;
        if (ndc.z < 0.0 || ndc.z > 1.0) {
            return false;
        }
        vec2 uv = ndc.xy * 0.5 + 0.5;
        uvMin = min(uvMin, uv);
        uvMax = max(uvMax, uv);
        zNear = min(zNear, ndc.z);
    }

    if (uvMax.x < 0.0 || uvMax.y < 0.0 || uvMin.x > 1.0 || uvMin.y > 1.0) {
        return true; // fully off-screen (frustum should have caught)
    }
    uvMin = clamp(uvMin, vec2(0.0), vec2(1.0));
    uvMax = clamp(uvMax, vec2(0.0), vec2(1.0));

    float px = max((uvMax.x - uvMin.x) * uViewport.x, 1.0);
    float py = max((uvMax.y - uvMin.y) * uViewport.y, 1.0);
    float mip = clamp(ceil(log2(max(px, py) * 0.5)), 0.0, float(uHizMaxLevel));

    float hiz = textureLod(uHiZ, uvMin, mip).r;
    hiz = max(hiz, textureLod(uHiZ, vec2(uvMax.x, uvMin.y), mip).r);
    hiz = max(hiz, textureLod(uHiZ, vec2(uvMin.x, uvMax.y), mip).r);
    hiz = max(hiz, textureLod(uHiZ, uvMax, mip).r);

    return zNear > hiz + uHizBias;
}

void main() {
    uint tid = gl_LocalInvocationID.x;
    uint id = gl_GlobalInvocationID.x;

    bool visible = false;
    MeshletHeader m;
    if (id < uMeshletCount) {
        m = meshlets[id];
        bool culled = false;
        if (uEnableFrustum != 0u && frustumCull(m.boundingSphere)) {
            atomicAdd(frustumCulled, 1u);
            culled = true;
        } else if (uEnableCone != 0u && coneCull(m.normalCone, m.boundingSphere.xyz)) {
            atomicAdd(coneCulled, 1u);
            culled = true;
        } else if (hizCull(m.boundingSphere)) {
            atomicAdd(occlusionCulled, 1u);
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
        uint outIdx = groupBase + exclusiveScan;
        visibleMeshletIds[outIdx] = id;
        cmds[outIdx].count         = m.primitiveCount * 3u;
        cmds[outIdx].instanceCount = 1u;
        cmds[outIdx].firstIndex    = m.primitiveOffset;
        cmds[outIdx].baseVertex    = int(m.vertexOffset);
        cmds[outIdx].baseInstance  = 0u;
    }
}

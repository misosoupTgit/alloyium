<div align="center">

# Alloyium

<img src="docs/alloyium.png" alt="Alloyium" width="220" />

</div>

## Brief history

Alloyium started from a simple bet: most of what [Nvidium](https://github.com/MCRcortex/nvidium) does for GPU-driven terrain is **algorithm**, so the non–Mesh-Shader pieces should be reproducible on **OpenGL 4.3** (Compute + SSBO + Indirect Draw) for any vendor—not only NVIDIA.

That bet was only half right. Culling, compaction, and Indirect command generation transferred cleanly. Emulating Mesh Shader **vertex export** with Compute did not: without an on-chip Shader Export path, writing attributes to VRAM and reading them again in a later stage added cost Mesh Shaders never pay. Near the end of the first architecture, Alloyium could look correct and still run **slower than Vanilla + Embeddium**.

The rewrite keeps GPU work for **culling and Indirect commands only**, and always draws from Embeddium’s static VBO/IBO (`baseVertex` / `firstIndex`). That form is what we call **Compute B'**—the current Alloyium.

---

## Technical overview

| | |
|---|---|
| Target | Minecraft **1.20.1** / **Forge** |
| Required | [Embeddium](https://www.curseforge.com/minecraft/mc-mods/embeddium) |
| Optional | Oculus / Iris (limited when a shader pack is active) |
| License | **LGPL-3.0-or-later** |
| Artifact | `alloyium-{SemVer}-Forge+1.20.1.jar` |

```
Embeddium static VBO / IBO
        │  read-only
        ▼
Compute A  — frustum / cone / (optional) Hi-Z cull + compaction
        ▼
Compute B' — emit DrawElementsIndirect commands only
             (no vertex-attribute writes)
        ▼
glMultiDrawElementsIndirect
   → draw Embeddium buffers via baseVertex / firstIndex
```

Hard rules:

- Do not emit position / normal / UV (or similar) from Compute.
- Final draws always reference Embeddium’s static buffers.
- Goal is a realistic uplift over **Vanilla + Embeddium**, not parity with Nvidium’s Mesh Shader path.

---

## Results in practice

Numbers depend on hardware, settings, and whether you are standing still or moving.

- Standing still, region-level Indirect caching and similar pieces can push very high FPS in favorable setups.
- Moving play is the honest metric; chase idle peaks and you will ship “optimizations” that regress real sessions.
- With Oculus / Iris and a shader pack, Iris’s baseline often dominates; Alloyium’s gain is usually small.
- Design target band: about **1.5–3×** vs Vanilla + Embeddium. Beating Nvidium is explicitly out of scope.

The fix that mattered was not “better culling math”—it was **stopping the VRAM round-trip for vertex data**.

---

## From old Alloyium to new Alloyium

**Old Alloyium** chased a cross-vendor stand-in for Nvidium: full GPU-driven terrain, including a Compute stage that regenerated or rewrote terrain vertex payloads much like a Mesh Shader’s output stage (internally, **Compute B**). Frustum / cone culling and stream compaction worked; the failure mode was structural. Mesh Shaders can stream primitives on-chip to the rasterizer. Compute on OpenGL 4.3 must park results in SSBO memory. That Write→barrier→Read path—and the frontend pressure of huge Indirect lists—could erase every win from culling.

**New Alloyium** is the same project after accepting that limit. Compute no longer owns geometry; it only decides *what* to draw and builds Indirect commands (**Compute B'**). Embeddium still owns meshing and the static VBO/IBO; Alloyium only supplies offsets and MultiDraw. Algorithmically it still resembles Nvidium’s cull→indirect idea; hardware-wise it does not pretend to be Mesh Shaders. The result is a narrower, honest product: an Embeddium acceleration layer with a ceiling below Nvidium, but without the self-inflicted bandwidth trap of the first design.

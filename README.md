<div align="center">

# Alloyium

<img src="docs/alloyium.png" alt="Alloyium" width="220" />

</div>

## Brief history

Alloyium started from a simple bet: most of what [Nvidium](https://github.com/MCRcortex/nvidium) does for GPU-driven terrain is **algorithm**, so the non–Mesh-Shader pieces should be reproducible on **OpenGL 4.3** (Compute + SSBO + Indirect Draw) for any vendor—not only NVIDIA.

That bet was only half right. Culling, compaction, and Indirect command generation transferred cleanly. Emulating Mesh Shader **vertex export** with Compute did not: without an on-chip Shader Export path, writing attributes to VRAM and reading them again in a later stage added cost Mesh Shaders never pay. Near the end of the first architecture, Alloyium could look correct and still run **slower than Vanilla + Embeddium**.

The rewrite did **not** drop Nvidium’s mindset. It kept the few optimizations that actually carry the idea—GPU culling, compaction, Indirect MultiDraw—and cut everything that only existed to imitate Mesh Shader hardware. GPU work is **culling and Indirect commands only**; draws always use Embeddium’s static VBO/IBO (`baseVertex` / `firstIndex`). That form is **Compute B'**—current Alloyium.

---

## Technical overview

| | |
|---|---|
| Target | Minecraft **1.20.1** / **Forge** |
| Required | [Embeddium](https://www.curseforge.com/minecraft/mc-mods/embeddium) |
| Optional | [Oculus](https://www.curseforge.com/minecraft/mc-mods/oculus) (Iris) — see below |
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

## Oculus (Iris)

On this target (Forge 1.20.1), shader packs come through **[Oculus](https://www.curseforge.com/minecraft/mc-mods/oculus)**—the Forge port of Iris—not a separate Iris jar. Alloyium does **not** hard-disable when Oculus is installed. Solid terrain can still use Alloyium’s Indirect path with Oculus (Iris) program / vertex-format bridging; **shadows stay on Oculus (Iris)**.

That said, several features are **limited or inactive** for shader compatibility, and Oculus alone can tax FPS even with no pack loaded. Same advice as the in-game warning: if you do not need Oculus and are not planning to use shader packs, **removing Oculus is recommended** for better performance.

Escape hatch: `oculus.irisFallback` in `config/alloyium-client.toml` (while a pack is active, leave terrain to Embeddium / Oculus).

---

## Results in practice

Numbers depend on hardware, settings, and whether you are standing still or moving.

- Standing still, region-level Indirect caching and similar pieces can push very high FPS in favorable setups.
- Moving play is the honest metric; chase idle peaks and you will ship “optimizations” that regress real sessions.
- With Oculus (Iris) and a shader pack, Oculus’s baseline often dominates; Alloyium’s gain is usually small.
- Design target band: about **1.3–1.6×** vs Vanilla + Embeddium. Beating Nvidium is explicitly out of scope.

The fix that mattered was not “better culling math”—it was **stopping the VRAM round-trip for vertex data**.

---

## From old Alloyium to new Alloyium

**Old Alloyium** tried to stand in for Nvidium across vendors: GPU-driven terrain end-to-end, including a Compute stage that rewrote terrain vertex payloads like a Mesh Shader output stage (**Compute B**). The Nvidium-shaped pieces that *are* portable—frustum / cone culling, stream compaction, Indirect draw lists—already worked. The collapse was structural. Mesh Shaders can stream primitives on-chip to the rasterizer; Compute on OpenGL 4.3 must park results in SSBO memory. That Write→barrier→Read path (and the frontend load of oversized Indirect lists) could wipe out every win from culling.

**New Alloyium** is not a retreat from Nvidium’s spirit. It is the same lineage after keeping only that **elite core** of ideas and stripping the imitation that had become the bottleneck. Compute no longer owns geometry; it decides *what* to draw and emits Indirect commands (**Compute B'**). Embeddium still owns meshing and the static VBO/IBO; Alloyium only supplies offsets and MultiDraw. The goal remains GPU-driven terrain in Nvidium’s sense—cull hard, draw little, stay Indirect—without paying Mesh Shader taxes that OpenGL 4.3 cannot waive. Ceiling stays below true Mesh Shader paths; the self-inflicted bandwidth trap does not.

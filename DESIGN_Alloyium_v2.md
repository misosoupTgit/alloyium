# Alloyium 設計書 v2.0 — Compute B' アーキテクチャを正本とする再建設計

対象: Minecraft 1.20.1 / Forge / Embeddium 依存
本書の位置付け: `技術ポストモーテム`（実装9割地点での性能劣化と是正の記録）を踏まえ、**Compute B' を最終ベースアーキテクチャとして確定**した上での再建設計書。以後の実装・レビューはすべて本書を正本とする。

前提文書: `技術ポストモーテム：OpenGL 4.3 Compute Shader による Mesh Shader エミュレーションの限界`（以下「ポストモーテム」）

---

## 0. この設計書のスタンス

ポストモーテム §11.1 で確定した決定事項をそのまま設計の憲法として採用する。

> Compute Shader で頂点属性（position/normal/UV）を生成・書き出しする経路は、例外3条件（本書 §6）を満たさない限り実装しない。最終描画は常に Embeddium の静的 VBO/IBO を `baseVertex`/`firstIndex` で直接参照する。

**Compute B（頂点変換＋SSBO書き出し）への回帰は禁止**。これは実装上の選択肢ではなく、設計原則として固定する。以降、単に「Compute B」と書いた場合は誤りであり、正しくは常に「**Compute B'（Indirectコマンド生成専任）**」を指す。旧 Compute B の構成は本書には存在しない。

### 0.1 性能目標（現実的な目標値として確定）

| 指標 | 目標 |
|---|---|
| 最低ライン | Vanilla + Embeddium 比 1.5× |
| 努力目標 | Vanilla + Embeddium 比 2〜3× |
| 非目標 | Nvidium（Mesh Shader 経路）との同等性能 |

Mesh Shader のハードウェア結合部分（Shader Export、Occupancy 自動管理、Task→Mesh のパイプライン重ね合わせ）は原理的に再現不可能という前提（ポストモーテム §10）を設計の出発点として受け入れる。

---

## 1. 全体アーキテクチャ

```
[Embeddium]
   セクション地形メッシュを CPU 側で生成
   → 静的 VBO / IBO（フレームを跨いで再利用、更新時のみ差分アップロード）
        │
        ▼
[Offline / Upload 層]
   meshlet 化（頂点再構成なし。境界球・法線コーンのみ算出）
   → MeshletHeader 配列を SSBO へアップロード
        │
        ▼
[Compute A: カリング + Stream Compaction]
   1. View-Frustum Culling
   2. Normal Cone Culling
   3. Hi-Z Occlusion Culling（後期実装）
   4. Stream Compaction（shared Prefix Sum）
   → VisibleMeshletIds バッファ
   → DispatchIndirect 引数バッファ
        │
        ▼
[Compute B': Indirect コマンド生成専任]
   可視 meshlet ごとに DrawElementsIndirectCommand を1個生成
   firstIndex / baseVertex は Embeddium 静的 IBO/VBO のオフセットをそのまま転記
   頂点属性・インデックス実データのコピーは一切行わない
   → DrawElementsIndirectCommand[] バッファ
        │
        ▼
[glMultiDrawElementsIndirect]
   Embeddium の静的 VBO/IBO を baseVertex/firstIndex で直接参照
   通常の VS+FS（またはシェーダーパック互換 program）で描画
```

**設計上の要点**: パイプラインを流れるのはオフセット（参照）だけであり、頂点属性の実データは Compute ステージを一度も経由しない。これにより、Compute B（旧）で発生していた「VRAM Write → VRAM Read」の往復コスト、およびそれに付随する `glMemoryBarrier` の同期ストールが構造的に存在しない設計になっている。

---

## 2. モジュール設計

### 2.1 Embeddium 連携層

- 役割: 静的 VBO/IBO の所有権は Embeddium 側に置き続ける。Alloyium はこれを**読み取り専用参照**としてのみ扱う。
- Alloyium 側は VBO/IBO の内容そのものを一切コピー・変換しない。触れるのは「どこからどこまでを描画に使うか」というオフセット情報のみ。
- Embeddium の Greedy Meshing・隣接面カリング済みメッシュをそのまま活用する（ポストモーテム §6 の通り、地形メッシュは meshlet 単位でさらに削れる無駄が少ないため、細分化への過剰投資は避ける）。

### 2.2 Meshlet 化（境界情報のみ）

```c
struct MeshletHeader {
    uint  vertexOffset;      // Embeddium 静的 VBO 内オフセット
    uint  vertexCount;
    uint  primitiveOffset;   // Embeddium 静的 IBO 内オフセット
    uint  primitiveCount;
    vec4  boundingSphere;    // xyz = center, w = radius
    vec4  normalCone;        // xyz = axis, w = cos(halfAngle)
};
```

**重要**: meshlet 化は頂点データの再パッキングを一切伴わない。既存の Embeddium バッファ上の連続範囲を指すオフセットと、カリング判定用の境界情報（球・法線コーン）のみを算出し、別 SSBO に保持する。

粒度は初期実装では**セクション単位**とし、meshlet 単位への細分化は §5 の効果測定を経てから投資判断する（段階的アプローチ、ポストモーテム §6.3）。

### 2.3 Compute A（カリング + Stream Compaction）

- Frustum Culling（境界球 vs 6平面）
- Normal Cone Culling（裏面クラスタ棄却）
- Hi-Z Occlusion Culling（後期実装。ミップ生成コスト自体がボトルネック化しないよう、ディスパッチ設計を別途検証する）
- Stream Compaction は Atomic Counter 単純方式ではなく、**shared Prefix Sum（Hillis-Steele型）方式を正本とする**。グローバル Atomic へのコンテンションをワークグループ数分（64分の1）に削減できるため。

出力は `VisibleMeshletIds` バッファと、次段の `DispatchIndirect` 引数のみ。**このステージでも頂点属性には一切触れない。**

### 2.4 Compute B'（Indirect コマンド生成専任）— 本設計の核

```c
// OpenGL 4.3 コア仕様
typedef struct {
    uint  count;         // 描画インデックス数
    uint  instanceCount; // 通常 1
    uint  firstIndex;    // Embeddium 静的 IBO オフセット
    int   baseVertex;    // Embeddium 静的 VBO オフセット
    uint  baseInstance;
} DrawElementsIndirectCommand; // 20 bytes
```

**責務の境界（コードレベルの制約）**:

```glsl
// ❌ 禁止パターン（旧 Compute B。本設計では二度と採用しない）
layout(std430, binding = 3) buffer OutVertices {
    PackedVertex outVerts[];
};
void main() {
    outVerts[writeIndex] = TransformVertex(meshlet, tid); // VRAM Write → 後段VSが再読込
}

// ✅ 正本パターン（Compute B'）
layout(std430, binding = 3) buffer OutCommands {
    DrawElementsIndirectCommand cmds[];
};
void main() {
    uint visIdx = gl_GlobalInvocationID.x;
    if (visIdx >= visibleCount) return;

    uint meshletId = visibleMeshletIds[visIdx];
    MeshletHeader m = meshlets[meshletId];

    cmds[visIdx].count         = m.primitiveCount * 3u;
    cmds[visIdx].instanceCount = 1u;
    cmds[visIdx].firstIndex    = m.primitiveOffset;   // オフセットの転記のみ
    cmds[visIdx].baseVertex    = int(m.vertexOffset); // オフセットの転記のみ
    cmds[visIdx].baseInstance  = 0u;
    // 頂点属性そのものには一切触れない
}
```

1 meshlet あたりの書き込みは 20 バイト固定であり、頂点数に比例しない。これにより旧 Compute B 比で約2桁のデータ量削減（ポストモーテム §4.2 の試算では約130倍）が構造として保証される。

### 2.5 描画層

`glMultiDrawElementsIndirect` により、Embeddium の静的 VBO/IBO を `baseVertex`/`firstIndex` 経由で直接参照する。頂点シェーダの実行経路は Vanilla+Embeddium の通常描画と同一であり、Alloyium 固有の追加コストはここでは発生しない。

---

## 3. データフロー上の禁止事項（ガードレール）

以下はコードレビュー時の常設チェック項目とする（ポストモーテム §11.1-4 を踏襲）。

- [ ] Compute パス全体で、頂点属性（position/normal/UV）を書き出しているバッファが存在しないこと（§6 の例外に該当しない限り）
- [ ] Compute B' が読むのはヘッダ情報（オフセット・カウント）のみで、Embeddium 実データバッファへの書き込みアクセスを持たないこと
- [ ] 新規機能追加時、「これは Amplification 相当（カリング・LOD判定・コマンド生成）か、Mesh 相当（頂点変換・出力）か」を最初に分類すること。後者に分類される機能は §6 のゲートを通過しない限り実装しない

---

## 4. フロントエンド（Command Processor）負荷対策

`glMultiDrawElementsIndirect` は `drawcount` を CPU 側固定値として渡す必要があり、GPU 側で決定した実コマンド数を動的反映できない（OpenGL 4.3 コアの制約）。この対策として:

- 当面: 最大想定数のコマンドバッファを発行し、不要分は `count=0`（no-op）として吸収する
- 検討中（未確定・要精査）: `GL_ARB_indirect_parameters`（`glMultiDrawElementsIndirectCountARB`）の採用。GPU 側の実コマンド数をそのまま Command Processor に渡せる。NV専用ではなく主要ベンダー共通拡張のため、クロスベンダー方針との親和性は比較的高いが、最低要求 GLバージョンの引き上げを伴うため対応GPU範囲への影響を精査してから決定する

meshlet 単位への細分化はコマンド数を1〜2桁増やしうるため、Command Processor 負荷とのトレードオフとして扱い、セクション単位カリングでの到達値を計測した上で投資判断を行う。

---

## 5. 測定方法論（Before/After 判定基準）

| 指標 | 是正前（旧Compute B想定） | 合格ライン |
|---|---|---|
| Compute B' の SSBO Write バイト数 | 頂点数に比例（数十〜百MB/フレーム級） | コマンド数 × 20 byte 程度（数百KB/フレーム級） |
| フレーム GPU time | Vanilla+Embeddium を上回る | Vanilla+Embeddium を下回る |
| カリング後残存数 | — | 是正前後で不変（ロジック回帰なし） |

使用ツール: RenderDoc（パス別GPUタイムライン、SSBO内容検査）／ NVIDIA Nsight Graphics・AMD RGP（帯域・Occupancy解析）／ apitrace（回帰比較）。

**運用ルール**: 機能テスト（視覚的正当性・カリング残存数）と性能テスト（パス別GPU time）は独立した検証軸として、両方を毎回実施する。ポストモーテムの根本教訓が「機能テストだけでは性能劣化を検出できなかった」ことである以上、この分離を再発防止策の中心に据える。

---

## 6. 頂点属性を Compute で扱ってよい唯一の例外規定

将来的な遠景LOD（メッシュ簡略化）等で頂点属性をComputeで扱う必要が生じた場合、以下3条件を**すべて**満たすことをゲート条件とする。

1. VSでは実現不可能な計算であること（例: GPU側でのみ確定するプロシージャルな簡略化）
2. 出力データ量が入力よりも計測上明確に小さいこと
3. その削減量がVRAM Write+Readの往復コストを上回ることをRenderDoc/Nsightで実測していること

現行のMinecraftブロック地形（軽量VS・既に間引き済みのメッシュ）はこの3条件のいずれも満たさないため、当面すべての頂点属性処理はEmbeddiumの静的バッファ参照のみで完結させる。

---

## 7. 段階的ロードマップ

| フェーズ | 内容 |
|---|---|
| R1 | GL能力検知、Embeddium橋渡し、Frustum Culling + Indirect solid描画（Compute B'前提で再実装） |
| R2 | Stream Compaction（shared Prefix Sum方式）、Normal Cone Culling |
| R3 | Compute B' 実装・§5測定基準でのBefore/After検証。**この時点でVanilla+Embeddium比1.5×達成を最初のゲートとする** |
| R4 | `GL_ARB_indirect_parameters` 採用可否の精査・判断 |
| R5 | Hi-Z 2-pass Occlusion Culling（ミップ生成コストの検証込み） |
| R6 | meshlet単位への細分化要否の効果測定（セクション単位到達値を基準に投資判断） |
| R7 | Oculus（Iris）互換レイヤー — Iris の pass/program 契約に Indirect Draw を翻訳して差し込む方式 |

旧設計との違い: 旧M5相当で行っていた「頂点変換+SSBO書き出し」フェーズは本ロードマップから削除。Compute B' は最初から「コマンド生成専任」として実装する（段階的に格下げるのではなく、最初からこの形で作る）。

---

## 8. コミュニティ向け説明方針（据え置き）

- Compute Shaderによるエミュレーションでは、Mesh Shaderのハードウェア結合部分（Shader Export、Occupancy自動管理）は原理的に再現できない
- 現実的な目標値はVanilla+Embeddium比1.5〜3×であり、Nvidium（Mesh Shader経路）そのものを上回ることは目指さない
- この制約は実装上の未熟さではなく、OpenGL 4.3のAPI表面が持つ構造的な限界に起因する

---

## 9. 参考文献

前提ポストモーテムの §13 を参照（NVIDIA/AMD一次資料、zeux.io、Khronos仕様、Nvidium/Embeddiumリポジトリ等）。

---

## 総括

本設計書は、ポストモーテムが示した教訓——「アルゴリズムとして等価に見える処理でも、前提とするメモリ階層・実行モデルが異なれば素朴な移植は逆効果になりうる」——を設計原則として固定化したものである。Compute B' を最初から正本として採用することで、旧Compute Bへの回帰リスクを設計レベルで排除し、「動くこと」と「速いこと」を独立した検証軸として扱う体制を、実装開始時点から組み込む。

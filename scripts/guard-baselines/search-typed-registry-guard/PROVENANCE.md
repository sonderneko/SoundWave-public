# Guard baseline provenance — search-typed-registry-guard

This public repository's history starts at the single snapshot commit
`e0b2d0bf282113069df42b6c3a7ddedad6c6a356`, so the fixed historical baseline
objects the guard used to read via `git show <commit>:<path>` do not exist
here. Byte-identical copies of those baseline files are vendored in this
directory, laid out as `<source commit>/<original repo path>`. The guard
prefers the vendored copy and falls back to `git show` where the object
exists. The baseline contents were NOT updated to current content — they are
the exact historical bytes, so every comparison keeps its original strength.

Source: private repo `sonderneko/SoundWave`, extracted 2026-11-05 with
`git show <commit>:<path>`. Verify any file with `sha256sum`.

## fb660e769edfd0c892c48152b55c0d6f96a0b575 (r657 historical ApiClient)

- `app/src/main/java/xyz/cncnlp/soundwave/ApiClient.kt`
  sha256 `0e6bffc2ef0c13cbe54b40ad2a594624ac0dcfdbca8163bdebad2e9f71d73d6d`

## 5ad2d9cd34f9dfb5e2cd1c5f4c96dec014ece1f0 (protected baseline)

- `app/src/main/java/xyz/cncnlp/soundwave/ApiClient.kt`
  sha256 `48eb767a5e26bb34d27a84535971350fe7d33a84672913f77f7168c36b8875f9`
- `app/src/main/java/xyz/cncnlp/soundwave/PlaylistFeedRepository.kt`
  sha256 `3750188c61837538e3f83737adc15befced0d128c31fc2656a1dbcb74ad778dd`
- `app/src/main/java/xyz/cncnlp/soundwave/HomeFeedController.kt`
  sha256 `e9d3b54385aad52debfe099f0a56de73dbfd658bc829d08789d112477cd13cbf`
- `app/src/main/java/xyz/cncnlp/soundwave/source/PlatformSourceAdapter.kt`
  sha256 `661e76265d38eda21de6ac7ffc4443378d2fc2365d77837fe7f176cc8bde63fc`
- `docs/source-capability-matrix.md`
  sha256 `0d192407244fbdede86ac6dda896982d2fcf1adeb8b8755b425f20f99d5e9036`

## 139ee6628a1e3dc0cc9f885e0a1b9aca431d4247 (fixed .552 baseline)

- `app/src/main/java/xyz/cncnlp/soundwave/ApiClient.kt`
  sha256 `15167dea8fbf259d65325fb593152a0ff810b1941ae04ea2a3657e5cddc62e87`
- `app/src/main/java/xyz/cncnlp/soundwave/source/MusicSource.kt`
  sha256 `cab44c216bbfa96f8aaf4840eabe98a21a7bbcee35600ae449528462fa8b9405`
- `app/src/main/java/xyz/cncnlp/soundwave/PlaylistFeedRepository.kt`
  sha256 `4c6d234f4d77d59457ed2a8da9aa33fc574ee1bffca7414bab466eaee5656292`
- `app/src/main/java/xyz/cncnlp/soundwave/source/PlatformSourceAdapter.kt`
  sha256 `e3f5c7f9efbe0eda03425c8c49dff446b6b985d3e12b89dba59ce66c56cdb073`
- `docs/source-capability-matrix.md`
  sha256 `d3e0b6aa6e9910ae0a88c87b1d3114844362f14f61565dec071dff47e8972499`

## 0a276c8218e10271f4717f6062f0a38ab21577f4 (fixed .551 baseline)

- `docs/source-capability-matrix.md`
  sha256 `fda50d8b76635c520b8a456a3d47ec0fc19ea414f23816811d08241694e4988a`

## Re-pinned reads (content verified byte-identical, no vendoring needed)

- `matrix_baseline`: was `bea4a4d25fe8cb4a196460ee9e230924fde9ba19`; the
  matrix content at that commit is byte-for-byte identical to the snapshot
  tree's `docs/source-capability-matrix.md`, so the pin was advanced to the
  snapshot commit `e0b2d0bf282113069df42b6c3a7ddedad6c6a356` per the guard's
  guard-final pin convention. Future matrix edits must advance the pin the
  same way.
- `PlaylistDirectSearch.kt` legacy-parser read: was
  `f7d9c5215e4e2fafff0928d2004d05819d959a25`; content at that commit is
  byte-for-byte identical to the snapshot tree, re-pinned to
  `e0b2d0bf282113069df42b6c3a7ddedad6c6a356`.
- `baseline_551` was spelled as the short SHA `0a276c8`; it now uses the
  full SHA of the same commit (above) so the vendored lookup keys match.

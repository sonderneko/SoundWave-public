# Guard baseline provenance — home-official-recommendation-registry-guard

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

## 139ee6628a1e3dc0cc9f885e0a1b9aca431d4247 (fixed .552 baseline)

- `app/src/main/java/xyz/cncnlp/soundwave/source/MusicSource.kt`
  sha256 `cab44c216bbfa96f8aaf4840eabe98a21a7bbcee35600ae449528462fa8b9405`
- `app/src/main/java/xyz/cncnlp/soundwave/source/PlatformSourceAdapter.kt`
  sha256 `e3f5c7f9efbe0eda03425c8c49dff446b6b985d3e12b89dba59ce66c56cdb073`
- `app/src/main/java/xyz/cncnlp/soundwave/PlaylistFeedRepository.kt`
  sha256 `4c6d234f4d77d59457ed2a8da9aa33fc574ee1bffca7414bab466eaee5656292`

#!/usr/bin/env python3
"""Mutation checks for webdav-sync-guard.sh; all mutations stay in temp fixtures."""
from pathlib import Path
import shutil
import subprocess
import tempfile

REPO = Path(__file__).resolve().parent.parent
GUARD = Path(__file__).resolve().parent / "webdav-sync-guard.sh"
OVERLAY_REL = Path("app/src/main/java/xyz/cncnlp/soundwave/ui/settings/WebDavSyncOverlay.kt")
REQUIRED_FILES = (
    "app/src/main/java/xyz/cncnlp/soundwave/sync/WebDavSync.kt",
    "app/src/main/java/xyz/cncnlp/soundwave/ui/settings/WebDavLoginSheet.kt",
    "app/src/main/java/xyz/cncnlp/soundwave/ui/common/DesignSheet.kt",
    str(OVERLAY_REL),
    "app/src/main/java/xyz/cncnlp/soundwave/ExtraPages.kt",
    "app/src/main/java/xyz/cncnlp/soundwave/SettingsHub.kt",
    "app/src/main/java/xyz/cncnlp/soundwave/MainActivity.kt",
    "app/src/main/java/xyz/cncnlp/soundwave/CloudSyncController.kt",
    "app/src/main/java/xyz/cncnlp/soundwave/LocalStore.kt",
    "app/src/test/java/xyz/cncnlp/soundwave/WebDavSyncTest.kt",
    "app/src/test/java/xyz/cncnlp/soundwave/WebDavServerCompatTest.kt",
    "app/build.gradle",
    "VERSION",
)


def fixture_at(destination: Path) -> Path:
    root = destination / "fixture"
    (root / "scripts").mkdir(parents=True)
    shutil.copy2(GUARD, root / "scripts/webdav-sync-guard.sh")
    for relative in REQUIRED_FILES:
        source = REPO / relative
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    return root


def run_guard(root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["bash", str(root / "scripts/webdav-sync-guard.sh")],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )


def expect_pass(root: Path, label: str) -> None:
    result = run_guard(root)
    if result.returncode != 0:
        raise AssertionError(f"{label}: guard unexpectedly failed (exit {result.returncode})\n{result.stdout}")
    print(f"PASS: {label} (guard exit 0)")


def expect_fail(root: Path, label: str) -> None:
    result = run_guard(root)
    if result.returncode == 0:
        raise AssertionError(f"{label}: guard unexpectedly passed\n{result.stdout}")
    if "[webdav-sync-guard] FAIL: overlay runs network work off the UI thread" not in result.stdout:
        raise AssertionError(f"{label}: failed for an unrelated reason (exit {result.returncode})\n{result.stdout}")
    print(f"PASS: {label} (guard rejected fixture, exit {result.returncode})")


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="webdav-sync-guard-test-") as temporary:
        temp = Path(temporary)

        untouched = fixture_at(temp / "untouched")
        expect_pass(untouched, "untouched copied source passes")

        no_sync = fixture_at(temp / "constructor-only")
        no_sync_overlay = no_sync / OVERLAY_REL
        source = no_sync_overlay.read_text()
        original_call = ".sync(\n                            url,"
        if source.count(original_call) != 1:
            raise AssertionError("expected exactly one WebDAV sync call marker in copied overlay")
        mutated = source.replace(original_call, ".syncRemoved(\n                            url,", 1)
        if "WebDavSyncClient(localPlaybackHistory" not in mutated:
            raise AssertionError("constructor was not retained in constructor-only mutation")
        no_sync_overlay.write_text(mutated)
        expect_fail(no_sync, "constructor retained but .sync invocation replaced")

        commented_sync = fixture_at(temp / "commented-sync")
        commented_overlay = commented_sync / OVERLAY_REL
        source = commented_overlay.read_text()
        commented = source.replace(
            original_call,
            "/* .sync( is only a comment */ .syncRemoved(\n                            url,",
            1,
        )
        if commented == source or "WebDavSyncClient(localPlaybackHistory" not in commented:
            raise AssertionError("failed to construct commented-sync mutation")
        commented_overlay.write_text(commented)
        expect_fail(commented_sync, "commented-out .sync text does not satisfy the guard")

    print("Mutation checks: 3/3 passed")


if __name__ == "__main__":
    main()

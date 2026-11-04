#!/usr/bin/env python3
"""Mutation tests for webdav-auto-sync-wiring-guard.py; fixtures are isolated and offline."""
from __future__ import annotations

from pathlib import Path
import shutil
import subprocess
import tempfile

REPO = Path(__file__).resolve().parent.parent
GUARD = Path(__file__).resolve().parent / "webdav-auto-sync-wiring-guard.py"
BASE = Path("app/src/main/java/xyz/cncnlp/soundwave")
ACCOUNT = BASE / "AccountWebDavAutoSync.kt"
CORE = BASE / "sync/WebDavAutoSync.kt"
MAIN = BASE / "MainActivity.kt"
CLOUD = BASE / "CloudSyncController.kt"
CLIENT = BASE / "sync/WebDavSync.kt"
SOURCES = (ACCOUNT, CORE, MAIN, CLOUD, CLIENT)


def fixture_at(destination: Path) -> Path:
    root = destination / "fixture"
    (root / "scripts").mkdir(parents=True)
    shutil.copy2(GUARD, root / "scripts/webdav-auto-sync-wiring-guard.py")
    for relative in SOURCES:
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(REPO / relative, target)
    return root


def run_guard(root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["python3", str(root / "scripts/webdav-auto-sync-wiring-guard.py"), "--root", str(root)],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False,
    )


def expect_pass(root: Path, label: str) -> None:
    result = run_guard(root)
    if result.returncode:
        raise AssertionError(f"{label}: expected guard pass, got {result.returncode}\n{result.stdout}")
    print(f"PASS: {label}")


def mutate(root: Path, relative: Path, before: str, after: str, label: str, expected_check: str) -> None:
    path = root / relative
    source = path.read_text(encoding="utf-8")
    if source.count(before) != 1:
        raise AssertionError(f"{label}: expected one source marker, found {source.count(before)}: {before!r}")
    path.write_text(source.replace(before, after, 1), encoding="utf-8")
    result = run_guard(root)
    diagnostic = f"[webdav-auto-sync-wiring-guard] FAIL: {expected_check}"
    if result.returncode == 0 or diagnostic not in result.stdout:
        raise AssertionError(f"{label}: mutation did not fail the named check {expected_check!r}\n{result.stdout}")
    print(f"PASS: {label} (rejected by expected check)")


def main() -> None:
    cases = [
        (MAIN, "androidx.lifecycle.Lifecycle.Event.ON_RESUME -> webDavAutoSync.foreground()", "androidx.lifecycle.Lifecycle.Event.ON_RESUME -> Unit", "ON_RESUME foreground removed", "lifecycle effect attaches, registers its observer, dispatches events, and handles initial RESUMED"),
        (MAIN, "androidx.lifecycle.Lifecycle.Event.ON_RESUME -> webDavAutoSync.foreground()", "androidx.lifecycle.Lifecycle.Event.ON_RESUME -> webDavAutoSync.background()", "ON_RESUME rewired to background", "lifecycle effect attaches, registers its observer, dispatches events, and handles initial RESUMED"),
        (MAIN, "androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> webDavAutoSync.background()", "androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> webDavAutoSync.foreground()", "ON_PAUSE rewired to foreground", "lifecycle effect attaches, registers its observer, dispatches events, and handles initial RESUMED"),
        (MAIN, "        webDavAutoSync.attach()\n", "        Unit\n", "lifecycle attach omitted", "lifecycle effect attaches, registers its observer, dispatches events, and handles initial RESUMED"),
        (MAIN, "        lifecycle.addObserver(observer)\n", "        // lifecycle.addObserver(observer)\n", "deleted observer registration with comment-only replacement", "lifecycle effect attaches, registers its observer, dispatches events, and handles initial RESUMED"),
        (MAIN, "            webDavAutoSync.foreground()\n        }\n        onDispose", "            Unit\n        }\n        onDispose", "initial RESUMED foreground missing while ON_RESUME remains", "lifecycle effect attaches, registers its observer, dispatches events, and handles initial RESUMED"),
        (MAIN, "            lifecycle.removeObserver(observer)\n", "            Unit\n", "onDispose observer removal omitted", "lifecycle disposal removes its observer and detaches the bridge"),
        (MAIN, "            webDavAutoSync.detach()\n", "            Unit\n", "onDispose detach omitted", "lifecycle disposal removes its observer and detaches the bridge"),
        (MAIN, "        onDispose {\n            lifecycle.removeObserver(observer)\n            webDavAutoSync.detach()\n        }", "        onDispose { Unit }\n        lifecycle.removeObserver(observer)\n        webDavAutoSync.detach()", "cleanup moved outside onDispose while tokens remain in effect", "lifecycle disposal removes its observer and detaches the bridge"),
        (ACCOUNT, "credentialPrefs.unregisterOnSharedPreferenceChangeListener(credentialListener)", "Unit", "credential listener unregister omitted", "credential listener registration and unregistration use the same callback"),
        (ACCOUNT, "credentialPrefs.unregisterOnSharedPreferenceChangeListener(credentialListener)", "// credentialPrefs.unregisterOnSharedPreferenceChangeListener(credentialListener)", "comment-only unregister spoof", "credential listener registration and unregistration use the same callback"),
        (ACCOUNT, 'getSharedPreferences("soundwave_webdav", Context.MODE_PRIVATE)', 'getSharedPreferences("webdav_other", Context.MODE_PRIVATE)', "credential namespace wrong", "credential listener registration and unregistration use the same callback"),
        (ACCOUNT, "credentialFingerprint = latest\n                auto.refreshBinding()", "credentialFingerprint = latest\n                auto.identityChanged()", "credential change refresh made ineffective", "credential listener dispatches on main and refreshes only on fingerprint change"),
        (ACCOUNT, "if (latest != credentialFingerprint)", "if (latest == credentialFingerprint)", "credential fingerprint condition inverted", "credential listener dispatches on main and refreshes only on fingerprint change"),
        (ACCOUNT, "scope.launch(mainDispatcher) {\n            val latest = credentialPrefs.all.toMap()", "scope.launch {\n            val latest = credentialPrefs.all.toMap()", "credential refresh leaves main dispatcher", "credential listener dispatches on main and refreshes only on fingerprint change"),
        (ACCOUNT, "WebDavAutoSyncBinding.bind(capability.accountKey, saved.url, saved.username)", "WebDavAutoSyncBinding.bind(saved.username, saved.url, saved.username)", "binding account key substituted", "current binding requires ready credentials, live account capability, provenance, and exact identity fields"),
        (ACCOUNT, "WebDavAutoSyncBinding.bind(capability.accountKey, saved.url, saved.username)", "WebDavAutoSyncBinding.bind(capability.accountKey, saved.username, saved.username)", "binding endpoint substituted", "current binding requires ready credentials, live account capability, provenance, and exact identity fields"),
        (ACCOUNT, "WebDavAutoSyncBinding.bind(capability.accountKey, saved.url, saved.username)", "WebDavAutoSyncBinding.bind(capability.accountKey, saved.url, saved.url)", "binding username substituted", "current binding requires ready credentials, live account capability, provenance, and exact identity fields"),
        (ACCOUNT, "!capability.isCurrent()", "false", "live capability check removed", "current binding requires ready credentials, live account capability, provenance, and exact identity fields"),
        (ACCOUNT, "LegacyUnassignedSnapshot.canExplicitlyEnableAccountSync(context)", "true", "snapshot provenance gate removed", "current binding requires ready credentials, live account capability, provenance, and exact identity fields"),
        (ACCOUNT, "if (currentBinding()?.accountKey == owner) auto.onLocalChanged()", "if (true) auto.onLocalChanged()", "local dirty owner filter removed", "local-dirty events are owner-filtered on main and subscribed/unsubscribed"),
        (ACCOUNT, "unsubscribe?.invoke()", "Unit", "local-dirty unsubscribe removed", "local-dirty events are owner-filtered on main and subscribed/unsubscribed"),
        (ACCOUNT, "auto.revoke() }; Unit", "auto.disable() }; Unit", "identity callback no longer revokes", "identity callback revokes authorization and detach clears it while backgrounding work"),
        (ACCOUNT, "account.onWebDavIdentityInvalidated = {}", "account.onWebDavIdentityInvalidated = { auto.revoke() }", "identity callback not cleared at detach", "identity callback revokes authorization and detach clears it while backgrounding work"),
        (ACCOUNT, "fun background() { auto.onBackground() }", "fun background() { Unit }", "bridge background forwarding removed", "foreground/background bridge forwards to the core state machine"),
        (CORE, "        cancelAutomaticWork()\n    }\n\n    /** Manual sync takes priority", "        Unit\n    }\n\n    /** Manual sync takes priority", "core background cancellation removed", "core background path clears foreground state and cancels outstanding automatic work"),
        (CLOUD, "if (loggedInState.value != v) {\n                onWebDavIdentityInvalidated()\n                xyz.cncnlp.soundwave.sync.WebDavOperations.identityChange {", "if (loggedInState.value != v) {\n                xyz.cncnlp.soundwave.sync.WebDavOperations.identityChange {", "logged-in identity invalidation hook removed", "account identity setters invoke invalidation before identityChange transitions"),
        (MAIN, "    LaunchedEffect(user, loggedIn) { webDavAutoSync.identityChanged() }\n", "    LaunchedEffect(user, loggedIn) { Unit }\n", "identityChanged LaunchedEffect missing bridge notification", "MainActivity identity LaunchedEffect notifies the bridge for delegated state changes"),
        (ACCOUNT, "            val lease = WebDavOperations.capture()\n            WebDavOperations.transaction(lease) {", "            WebDavOperations.transaction(lease) {\n            val lease = WebDavOperations.capture()", "transaction gate acquired before lease capture", "shared account mutex is acquired before the WebDAV transaction gate"),
        (ACCOUNT, "                        lease.commit {", "                        lease.withoutCommit {", "local apply moved outside lease commit", "merged bundle is applied under the lease before the runner reports Success"),
        (ACCOUNT, "                            account.applyWebDavBundleLocally(result.bundle)\n                            if (avatar?.isCurrent?.invoke() == true) avatar.apply(result.avatarB64)\n                        }\n                        WebDavAutoSyncOutcome.Success", "                            account.applyWebDavBundleLocally(result.bundle)\n                            if (avatar?.isCurrent?.invoke() == true) avatar.apply(result.avatarB64)\n                            WebDavAutoSyncOutcome.Success\n                        }", "success reported before lease commit completes", "merged bundle is applied under the lease before the runner reports Success"),
        (ACCOUNT, "if (code == 409 || code == 412) runBlocking(mainDispatcher)", "if (code == 409 || code == 412 || code == 200) runBlocking(mainDispatcher)", "successful HTTP response acknowledged early", "only definite 409/412 responses acknowledge the pre-PUT marker"),
        (ACCOUNT, "    private val transport: WebDavTransport? = null,", "    private val transport: WebDavTransport? = null,\n    private val scheduler: Any = WorkManager,", "scheduler injection added", "successful HTTP response is not acknowledged early and there is no scheduler injection"),
    ]
    with tempfile.TemporaryDirectory(prefix="webdav-auto-sync-wiring-guard-") as temporary:
        temp = Path(temporary)
        baseline = fixture_at(temp / "baseline")
        expect_pass(baseline, "untouched copied source passes")
        for index, (relative, before, after, label, expected) in enumerate(cases, start=1):
            root = fixture_at(temp / f"mutation-{index:02d}")
            mutate(root, relative, before, after, label, expected)
    print(f"Mutation checks: {len(cases) + 1}/{len(cases) + 1} passed ({len(cases)} mutations + baseline)")


if __name__ == "__main__":
    main()

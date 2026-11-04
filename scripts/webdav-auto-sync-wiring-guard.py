#!/usr/bin/env python3
"""Structural source guard for AccountWebDavAutoSync lifecycle/credential wiring.

This is intentionally not a runtime or AndroidKeyStore integration test. It checks
that production source keeps the lifecycle, identity, credential, and transaction
boundaries connected. Kotlin comments are stripped before every structural check.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys

BASE = Path("app/src/main/java/xyz/cncnlp/soundwave")


def without_kotlin_comments(source: str) -> str:
    """Erase line and nested block comments, preserving strings and line layout."""
    out: list[str] = []
    i = 0
    while i < len(source):
        if source.startswith("//", i):
            end = source.find("\n", i)
            if end < 0:
                out.append(" " * (len(source) - i))
                break
            out.append(" " * (end - i))
            i = end
        elif source.startswith("/*", i):
            start = i
            depth = 1
            i += 2
            while i < len(source) and depth:
                if source.startswith("/*", i):
                    depth += 1
                    i += 2
                elif source.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
            comment = source[start:i]
            out.append("".join("\n" if c == "\n" else " " for c in comment))
        elif source.startswith('"""', i):
            end = source.find('"""', i + 3)
            end = len(source) if end < 0 else end + 3
            out.append(source[i:end])
            i = end
        elif source[i] in "\"'":
            quote = source[i]
            start = i
            i += 1
            while i < len(source):
                if source[i] == "\\":
                    i += 2
                elif source[i] == quote:
                    i += 1
                    break
                else:
                    i += 1
            out.append(source[start:i])
        else:
            out.append(source[i])
            i += 1
    return "".join(out)


def matching_end(source: str, start: int, opening: str = "{", closing: str = "}") -> int | None:
    depth = 0
    i = start
    while i < len(source):
        if source.startswith('"""', i):
            end = source.find('"""', i + 3)
            i = len(source) if end < 0 else end + 3
            continue
        if source[i] in "\"'":
            quote = source[i]
            i += 1
            while i < len(source):
                if source[i] == "\\":
                    i += 2
                elif source[i] == quote:
                    i += 1
                    break
                else:
                    i += 1
            continue
        if source[i] == opening:
            depth += 1
        elif source[i] == closing:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return None


def scoped_block(source: str, anchor: str) -> str:
    """Return the actual braced block following a unique code anchor."""
    pos = source.find(anchor)
    if pos < 0:
        return ""
    embedded_open = anchor.rfind("{")
    start = pos + embedded_open if embedded_open >= 0 else source.find("{", pos + len(anchor))
    if start < 0:
        return ""
    end = matching_end(source, start)
    return source[start:end + 1] if end is not None else ""


def function_body(source: str, name: str) -> str:
    """Extract one named Kotlin function body, not a whole-file token soup."""
    match = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", source)
    if not match:
        return ""
    paren = source.find("(", match.start())
    end_paren = matching_end(source, paren, "(", ")")
    if end_paren is None:
        return ""
    start = source.find("{", end_paren + 1)
    if start < 0:
        return ""
    end = matching_end(source, start)
    return source[start:end + 1] if end is not None else ""


def between(source: str, start_anchor: str, end_anchor: str) -> str:
    start = source.find(start_anchor)
    if start < 0:
        return ""
    end = source.find(end_anchor, start + len(start_anchor))
    return source[start:end] if end >= 0 else ""


def load(root: Path, relative: str) -> str:
    path = root / relative
    if not path.is_file():
        raise FileNotFoundError(f"missing required source: {path}")
    return without_kotlin_comments(path.read_text(encoding="utf-8"))


def run(root: Path) -> int:
    account_path = str(BASE / "AccountWebDavAutoSync.kt")
    core_path = str(BASE / "sync/WebDavAutoSync.kt")
    main_path = str(BASE / "MainActivity.kt")
    cloud_path = str(BASE / "CloudSyncController.kt")
    client_path = str(BASE / "sync/WebDavSync.kt")
    account = load(root, account_path)
    core = load(root, core_path)
    main = load(root, main_path)
    cloud = load(root, cloud_path)
    client = load(root, client_path)

    effect = scoped_block(main, "DisposableEffect(webDavAutoSync, lifecycleOwner)")
    observer = scoped_block(effect, "val observer = androidx.lifecycle.LifecycleEventObserver {")
    initial_resumed = scoped_block(effect, "if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {")
    disposal = scoped_block(effect, "onDispose {")
    identity_effect = scoped_block(main, "LaunchedEffect(user, loggedIn) {")
    listener = scoped_block(account, "private val credentialListener = SharedPreferences.OnSharedPreferenceChangeListener")
    attach = function_body(account, "attach")
    detach = function_body(account, "detach")
    binding = between(account, "private fun currentBinding()", "private suspend fun runSync")
    # runSync uses an expression body (`= account.withWebDavAccountLock { ... }`),
    # so keep its declaration through the next class-level function boundary.
    runner = between(account, "private suspend fun runSync", "\n}")
    foreground = function_body(account, "foreground")
    background = function_body(account, "background")
    core_background = function_body(core, "onBackground")
    cancel = function_body(core, "cancelAutomaticWork")
    login_setter = between(cloud, "var loggedIn:", "val userState")
    user_setter = between(cloud, "var user:", "val passState")
    invalidate_pending = function_body(cloud, "invalidatePendingAccountAuth")
    block_session = function_body(cloud, "blockCurrentAccountSession")
    identity_transition = between(cloud, "suspend fun <T> withAccountIdentityTransition", "private fun loadStoredPass")
    capability = function_body(cloud, "prepareWebDavAccountDataSync")
    apply = function_body(cloud, "applyWebDavBundleLocally")
    settings_route = function_body(main, "AppRootSettingsRoute")
    success_path = between(runner, "when (val result =", "is WebDavSyncResult.Failure")
    commit_block = scoped_block(success_path, "lease.commit {")
    response_callback = scoped_block(runner, "onAccountPutResponse = { code ->")
    execute = scoped_block(client, "private fun execute(request: WebDavHttpRequest)")

    checks: list[tuple[str, bool]] = [
        ("bridge is constructed with the remembered account controller", bool(re.search(
            r"remember\(account\)\s*\{\s*AccountWebDavAutoSync\(context,\s*scope,\s*account\)\s*\}", main))),
        ("lifecycle effect attaches, registers its observer, dispatches events, and handles initial RESUMED", bool(effect)
         and "webDavAutoSync.attach()" in effect
         and "lifecycle.addObserver(observer)" in effect
         and bool(observer)
         and "Lifecycle.Event.ON_RESUME -> webDavAutoSync.foreground()" in observer
         and "Lifecycle.Event.ON_PAUSE -> webDavAutoSync.background()" in observer
         and bool(initial_resumed)
         and "webDavAutoSync.foreground()" in initial_resumed),
        ("lifecycle disposal removes its observer and detaches the bridge", bool(effect)
         and bool(disposal)
         and "lifecycle.removeObserver(observer)" in disposal
         and "webDavAutoSync.detach()" in disposal),
        ("credential listener dispatches on main and refreshes only on fingerprint change", bool(listener)
         and "scope.launch(mainDispatcher)" in listener
         and "credentialPrefs.all.toMap()" in listener
         and "latest != credentialFingerprint" in listener
         and "credentialFingerprint = latest" in listener
         and "auto.refreshBinding()" in listener),
        ("credential listener registration and unregistration use the same callback", bool(attach) and bool(detach)
         and 'context.applicationContext.getSharedPreferences("soundwave_webdav", Context.MODE_PRIVATE)' in account
         and "credentialPrefs.registerOnSharedPreferenceChangeListener(credentialListener)" in attach
         and "credentialPrefs.unregisterOnSharedPreferenceChangeListener(credentialListener)" in detach),
        ("current binding requires ready credentials, live account capability, provenance, and exact identity fields", bool(binding)
         and "WebDavSavedCredentialState.Ready" in account[account.find("private fun credentials():"):account.find("private fun currentBinding()")]
         and "account.prepareWebDavAccountDataSync()" in binding
         and "credentials()" in binding
         and "capability.isCurrent()" in binding
         and "canExplicitlyEnableAccountSync(context)" in binding
         and "WebDavAutoSyncBinding.bind(capability.accountKey, saved.url, saved.username)" in binding),
        ("local-dirty events are owner-filtered on main and subscribed/unsubscribed", bool(attach) and bool(detach)
         and "AccountOwnedDataChanges.subscribe" in attach
         and "scope.launch(mainDispatcher)" in attach
         and "currentBinding()?.accountKey == owner" in attach
         and "auto.onLocalChanged()" in attach
         and "unsubscribe?.invoke()" in detach
         and "unsubscribe = null" in detach),
        ("identity callback revokes authorization and detach clears it while backgrounding work", bool(attach) and bool(detach)
         and "account.onWebDavIdentityInvalidated = { scope.launch(mainDispatcher) { auto.revoke() }; Unit }" in attach
         and "account.onWebDavIdentityInvalidated = {}" in detach
         and "auto.onBackground()" in detach),
        ("foreground/background bridge forwards to the core state machine", bool(foreground) and "auto.onForeground()" in foreground
         and bool(background) and "auto.onBackground()" in background),
        ("core background path clears foreground state and cancels outstanding automatic work", bool(core_background)
         and "foreground = false" in core_background
         and "cancelAutomaticWork()" in core_background
         and "pendingDirty = false" in core_background
         and bool(cancel) and "generation += 1L" in cancel and "autoJob?.cancel()" in cancel),
        ("account identity setters invoke invalidation before identityChange transitions", bool(login_setter) and bool(user_setter)
         and "onWebDavIdentityInvalidated()" in login_setter and "onWebDavIdentityInvalidated()" in user_setter
         and "WebDavOperations.identityChange" in login_setter and "WebDavOperations.identityChange" in user_setter
         and login_setter.index("onWebDavIdentityInvalidated()") < login_setter.index("WebDavOperations.identityChange")
         and user_setter.index("onWebDavIdentityInvalidated()") < user_setter.index("WebDavOperations.identityChange")),
        ("account invalidation, blocked-session and identity-transition callbacks revoke in actual paths", bool(block_session)
         and "onWebDavIdentityInvalidated()" in block_session
         and bool(invalidate_pending) and "onWebDavIdentityInvalidated()" in invalidate_pending
         and bool(identity_transition) and "onWebDavIdentityInvalidated()" in identity_transition
         and "WebDavOperations.beginIdentityTransition()" in identity_transition),
        ("MainActivity identity LaunchedEffect notifies the bridge for delegated state changes", bool(identity_effect)
         and "webDavAutoSync.identityChanged()" in identity_effect),
        ("shared account mutex is acquired before the WebDAV transaction gate", bool(runner)
         and runner.index("account.withWebDavAccountLock") < runner.index("WebDavOperations.capture()")
         and runner.index("WebDavOperations.capture()") < runner.index("WebDavOperations.transaction(lease)")),
        ("merged bundle is applied under the lease before the runner reports Success", bool(success_path)
         and "lease.commit {" in success_path
         and "account.applyWebDavBundleLocally(result.bundle)" in success_path
         and "WebDavAutoSyncOutcome.Success" in success_path
         and bool(commit_block) and "WebDavAutoSyncOutcome.Success" not in commit_block
         and success_path.index("lease.commit {") < success_path.index("account.applyWebDavBundleLocally(result.bundle)")
         and success_path.index("account.applyWebDavBundleLocally(result.bundle)") < success_path.index("WebDavAutoSyncOutcome.Success")),
        ("only definite 409/412 responses acknowledge the pre-PUT marker", bool(response_callback)
         and re.search(r"if\s*\(code\s*==\s*409\s*\|\|\s*code\s*==\s*412\)\s*runBlocking\(mainDispatcher\)", response_callback) is not None
         and "auto.remoteWriteAcknowledged()" in response_callback
         and bool(execute) and "onAccountPutResponse?.invoke(it.code)" in execute),
        ("successful HTTP response is not acknowledged early and there is no scheduler injection", bool(response_callback)
         and response_callback.count("auto.remoteWriteAcknowledged()") == 1
         and "code == 200" not in response_callback and "code == 201" not in response_callback and "code == 204" not in response_callback
         and not re.search(r"\b(?:WorkManager|OneTimeWorkRequest|PeriodicWorkRequest|AlarmManager|Scheduler)\b", account + core + main)),
    ]
    failures = 0
    for label, ok in checks:
        print(f"[webdav-auto-sync-wiring-guard] {'PASS' if ok else 'FAIL'}: {label}")
        failures += not ok
    print(f"[webdav-auto-sync-wiring-guard] CHECKS: {len(checks)} total, {len(checks) - failures} passed, {failures} failed")
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent,
                        help="repository or copied fixture root (default: repository root)")
    args = parser.parse_args()
    try:
        return run(args.root.resolve())
    except (FileNotFoundError, OSError) as error:
        print(f"[webdav-auto-sync-wiring-guard] ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

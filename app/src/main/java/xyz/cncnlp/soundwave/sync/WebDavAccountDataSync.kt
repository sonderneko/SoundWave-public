package xyz.cncnlp.soundwave.sync

/**
 * Runtime-only capability. The profile name is never serialized in plaintext into a
 * WebDAV payload: it travels only AES-GCM-encrypted under a key derived from the
 * WebDAV password (see WebDavProfileNameCrypto), and the owner identity in file
 * names / the owner field remains the swown2_ hash (AccountDataIdentity).
 */
data class WebDavAccountDataSync(
    val accountUser: String,
    val accountKey: String,
    val isCurrent: () -> Boolean
)

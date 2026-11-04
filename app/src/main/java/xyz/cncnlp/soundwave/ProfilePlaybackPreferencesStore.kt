package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences

/**
 * Playback/download preferences owned by one explicitly addressed local profile.
 *
 * This store only persists values; it does not activate a profile or alter the legacy, no-ID
 * preference path. It is intentionally not wired into MainActivity, PlayerService, or workers.
 * The values will take effect only after future profile-aware playback, download, and probe
 * call sites are connected. Until then, runtime paths continue to use their existing global
 * preferences (including DownloadHelper's global download settings and ScriptProbeWorker's
 * global `play_br` read).
 *
 * Only value-based preferences belong here. System volume, audio routing, `allow_mix`, the SAF
 * tree URI/grant, output directories, and installed audio files remain device/global resources.
 * The equalizer preset is stored as an integer preference only; this API does not apply it to the
 * singleton PlayerService. Filename templates are fixed literal choices and are never evaluated.
 */
internal data class ProfilePlaybackPreferences(
    val playQuality: String = "320k",
    val downloadQuality: String = "320k",
    val autoPlayOnStart: Boolean = false,
    val prefetchNext: Boolean = true,
    val prefetchIntervalSec: Int = 6,
    val prefetchCount: Int = 2,
    val playSpeed: Float = 1f,
    val equalizerPreset: Int = 0,
    val downloadMultiThread: Boolean = true,
    val saveExtLyric: Boolean = true,
    val saveExtCover: Boolean = true,
    val downloadFilenameTemplate: String = "title_singer",
)

/** Explicit profile-only persistence for playback and download preferences; there is no global/no-ID API. */
internal object ProfilePlaybackPreferencesStore {
    private const val KEY_PLAY_QUALITY = "play_br"
    private const val KEY_DOWNLOAD_QUALITY = "down_br"
    private const val KEY_AUTO_PLAY_ON_START = "auto_play_on_start"
    private const val KEY_PREFETCH_NEXT = "prefetch_next"
    private const val KEY_PREFETCH_INTERVAL_SEC = "prefetch_interval_sec"
    private const val KEY_PREFETCH_COUNT = "prefetch_count"
    private const val KEY_PLAY_SPEED = "play_speed"
    private const val KEY_EQUALIZER_PRESET = "eq_preset"
    private const val KEY_DOWNLOAD_MULTI_THREAD = "download_mt"
    private const val KEY_SAVE_EXT_LYRIC = "save_ext_lyric"
    private const val KEY_SAVE_EXT_COVER = "save_ext_cover"
    private const val KEY_DOWNLOAD_FILENAME_TEMPLATE = "download_name_tpl"

    private const val DEFAULT_QUALITY = "320k"
    private const val DEFAULT_FILENAME_TEMPLATE = "title_singer"
    private val supportedQualities = USER_AUDIO_QUALITY_OPTIONS.map { it.first }.toSet()
    private val supportedFilenameTemplates = setOf("title_singer", "title_only", "album_title")

    /** Resolve the same isolated profile file on every call; never fall back to legacy preferences. */
    private fun profilePreferences(context: Context, profileId: ProfileId): SharedPreferences =
        context.applicationContext.getSharedPreferences(profileId.preferencesName, Context.MODE_PRIVATE)

    fun load(context: Context, profileId: ProfileId): ProfilePlaybackPreferences {
        val preferences = profilePreferences(context, profileId)
        return ProfilePlaybackPreferences(
            playQuality = normalizeQuality(preferences.getString(KEY_PLAY_QUALITY, DEFAULT_QUALITY)),
            downloadQuality = normalizeQuality(readString(preferences, KEY_DOWNLOAD_QUALITY, DEFAULT_QUALITY)),
            autoPlayOnStart = preferences.getBoolean(KEY_AUTO_PLAY_ON_START, false),
            prefetchNext = preferences.getBoolean(KEY_PREFETCH_NEXT, true),
            prefetchIntervalSec = preferences.getInt(KEY_PREFETCH_INTERVAL_SEC, 6).coerceIn(1, 30),
            prefetchCount = preferences.getInt(KEY_PREFETCH_COUNT, 2).coerceIn(1, 5),
            playSpeed = normalizePlaySpeed(preferences.getFloat(KEY_PLAY_SPEED, 1f)),
            equalizerPreset = preferences.getInt(KEY_EQUALIZER_PRESET, 0).coerceIn(0, 5),
            downloadMultiThread = readBoolean(preferences, KEY_DOWNLOAD_MULTI_THREAD, true),
            saveExtLyric = readBoolean(preferences, KEY_SAVE_EXT_LYRIC, true),
            saveExtCover = readBoolean(preferences, KEY_SAVE_EXT_COVER, true),
            downloadFilenameTemplate = normalizeFilenameTemplate(
                readString(preferences, KEY_DOWNLOAD_FILENAME_TEMPLATE, DEFAULT_FILENAME_TEMPLATE),
            ),
        )
    }

    /** Write only this store's profile-owned preference keys; unrelated profile data is untouched. */
    fun save(context: Context, profileId: ProfileId, settings: ProfilePlaybackPreferences) {
        profilePreferences(context, profileId).edit()
            .putString(KEY_PLAY_QUALITY, normalizeQuality(settings.playQuality))
            .putString(KEY_DOWNLOAD_QUALITY, normalizeQuality(settings.downloadQuality))
            .putBoolean(KEY_AUTO_PLAY_ON_START, settings.autoPlayOnStart)
            .putBoolean(KEY_PREFETCH_NEXT, settings.prefetchNext)
            .putInt(KEY_PREFETCH_INTERVAL_SEC, settings.prefetchIntervalSec.coerceIn(1, 30))
            .putInt(KEY_PREFETCH_COUNT, settings.prefetchCount.coerceIn(1, 5))
            .putFloat(KEY_PLAY_SPEED, normalizePlaySpeed(settings.playSpeed))
            .putInt(KEY_EQUALIZER_PRESET, settings.equalizerPreset.coerceIn(0, 5))
            .putBoolean(KEY_DOWNLOAD_MULTI_THREAD, settings.downloadMultiThread)
            .putBoolean(KEY_SAVE_EXT_LYRIC, settings.saveExtLyric)
            .putBoolean(KEY_SAVE_EXT_COVER, settings.saveExtCover)
            .putString(KEY_DOWNLOAD_FILENAME_TEMPLATE, normalizeFilenameTemplate(settings.downloadFilenameTemplate))
            .apply()
    }

    private fun readString(preferences: SharedPreferences, key: String, default: String): String =
        runCatching { preferences.getString(key, default) }.getOrNull() ?: default

    private fun readBoolean(preferences: SharedPreferences, key: String, default: Boolean): Boolean =
        runCatching { preferences.getBoolean(key, default) }.getOrDefault(default)

    private fun normalizeQuality(value: String?): String =
        normalizeUserSelectableQuality(value, DEFAULT_QUALITY).takeIf { it in supportedQualities } ?: DEFAULT_QUALITY

    private fun normalizeFilenameTemplate(value: String?): String =
        value?.takeIf { it in supportedFilenameTemplates } ?: DEFAULT_FILENAME_TEMPLATE

    private fun normalizePlaySpeed(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0.5f, 2f) else 1f
}

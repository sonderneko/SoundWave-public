package xyz.cncnlp.soundwave

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlaybackStoreProfileTest {
    private val context = IsolatedPreferencesContext(RuntimeEnvironment.getApplication())
    private val profileA = ProfileId.of("playback-snapshot-profile-a")
    private val profileB = ProfileId.of("playback-snapshot-profile-b")

    @Test
    fun unactivatedProfilesRejectSnapshotWritesWithoutOpeningPreferences() {
        val legacyOwnedSong = song("legacy-owned")
        val profileOwnedSong = song("profile-owned")

        // Snapshot defaults to the current legacy owner, which is not authority for a profile.
        PlaybackStore.save(
            context,
            profileA,
            PlaybackSnapshot(song = legacyOwnedSong, queue = listOf(legacyOwnedSong))
        )
        // The profile-addressed overload's default generation is not an activated runtime lease.
        PlaybackStore.save(
            context,
            profileB,
            song = profileOwnedSong,
            queue = listOf(profileOwnedSong),
            index = 0,
            positionMs = 678L,
            playing = false
        )

        assertTrue("unactivated profile writes must fail closed before opening preferences", context.requestedPreferenceNames.isEmpty())
        assertFalse("profile APIs must not access legacy playback storage", LEGACY_PREFERENCE in context.requestedPreferenceNames)
    }

    @Test
    fun emptyProfileReadsAsNullAndNullSnapshotsOrSongsDoNotWrite() {
        val emptyProfile = ProfileId.of("playback-snapshot-empty")

        assertNull(PlaybackStore.load(context, emptyProfile))
        assertEquals(setOf(preferenceName(emptyProfile)), context.requestedPreferenceNames.toSet())

        context.requestedPreferenceNames.clear()
        PlaybackStore.save(context, emptyProfile, PlaybackSnapshot(song = null))
        PlaybackStore.save(
            context,
            emptyProfile,
            song = null,
            queue = listOf(song("unused")),
            index = 0,
            positionMs = 500L,
            playing = true
        )
        PlaybackStore.save(
            context,
            emptyProfile,
            song = song("unused"),
            queue = emptyList(),
            index = 0,
            positionMs = 500L,
            playing = true
        )

        assertTrue("empty/null saves must not even open a preference namespace", context.requestedPreferenceNames.isEmpty())
    }

    @Test
    fun existingDefaultApisKeepLegacyPreferenceFileKeysAndExplicitResumeBehavior() {
        val legacySong = song("legacy")

        PlaybackStore.save(
            context,
            song = legacySong,
            queue = listOf(legacySong),
            index = 0,
            positionMs = 4_321L,
            playing = true
        )

        assertState(
            songId = "legacy",
            queueIds = listOf("legacy"),
            index = 0,
            positionMs = 4_321L,
            wasPlaying = true,
            state = PlaybackStore.load(context)
        )
        val legacyJson = JSONObject(prefs(LEGACY_PREFERENCE).getString("state", null)!!)
        assertEquals(
            setOf("ownerVersion", "owner", "ownerGeneration", "song", "queue", "index", "positionMs", "playing", "ts", "resolvedAt"),
            legacyJson.keys().asSequence().toSet()
        )
        assertEquals(1, legacyJson.getInt("ownerVersion"))
        assertEquals(xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.LEGACY_OWNER, legacyJson.getString("owner"))
        assertTrue(legacyJson.getLong("ownerGeneration") >= 0L)
        assertEquals(setOf("state"), prefs(LEGACY_PREFERENCE).all.keys)

        PlaybackStore.setExplicitResume(context, true)
        assertTrue(PlaybackStore.consumeExplicitResume(context))
        assertFalse(PlaybackStore.consumeExplicitResume(context))
        assertEquals(setOf("state"), prefs(LEGACY_PREFERENCE).all.keys)
        assertEquals(setOf(LEGACY_PREFERENCE, "soundwave_userdata"), context.requestedPreferenceNames.toSet())
    }

    @Test
    fun unsafeProfileIdsAreRejectedBeforeAnyPreferenceNamespaceIsAccessed() {
        listOf("", "../escape", "profile/name", "profile.name", "含中文", "a".repeat(65)).forEach { rawId ->
            assertThrows("unsafe profile ID: $rawId", IllegalArgumentException::class.java) {
                ProfileId.of(rawId)
            }
        }

        assertTrue(context.requestedPreferenceNames.isEmpty())
    }

    private fun assertState(
        songId: String,
        queueIds: List<String>,
        index: Int,
        positionMs: Long,
        wasPlaying: Boolean,
        state: PlaybackStore.State?
    ) {
        assertTrue("expected a saved playback snapshot", state != null)
        val actual = requireNotNull(state)
        assertEquals(songId, actual.song.id)
        assertEquals(queueIds, actual.queue.map { it.id })
        assertEquals(index, actual.index)
        assertEquals(positionMs, actual.positionMs)
        assertEquals(wasPlaying, actual.wasPlaying)
    }

    private fun song(id: String) = Song(id = id, name = id, singer = "Artist", source = "wy")

    private fun preferenceName(profileId: ProfileId) = "$PROFILE_PREFERENCE_PREFIX${profileId.value}"

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** All app SharedPreferences calls are remapped to a unique Robolectric-only namespace. */
    private class IsolatedPreferencesContext(base: Context) : ContextWrapper(base) {
        private val namespace = "playback-store-profile-test-${System.nanoTime()}"
        val requestedPreferenceNames = mutableListOf<String>()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences(name.also { requestedPreferenceNames += it }.let { "$namespace-$it" }, mode)
    }

    private companion object {
        const val LEGACY_PREFERENCE = "soundwave_playback_v1"
        const val PROFILE_PREFERENCE_PREFIX = "soundwave_playback_profile_"
    }
}

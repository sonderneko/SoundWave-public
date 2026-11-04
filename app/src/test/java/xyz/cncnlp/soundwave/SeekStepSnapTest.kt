package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.cncnlp.soundwave.ui.player.seekStepIndex
import xyz.cncnlp.soundwave.ui.player.seekStepSnapRatio

/** 件 2（用户 2026-11-04）：播放进度拖动 5 秒一档吸附——纯函数口径。 */
class SeekStepSnapTest {
    @Test fun snapsToNearestFiveSecondStep() {
        // 200 秒的歌：0.31 → 62 秒 → 就近 60 秒 = 0.30；0.34 → 68 秒 → 70 秒 = 0.35。
        assertEquals(0.30f, seekStepSnapRatio(0.31f, 200f), 0.0001f)
        assertEquals(0.35f, seekStepSnapRatio(0.34f, 200f), 0.0001f)
        // 已在档上不动。
        assertEquals(0.25f, seekStepSnapRatio(0.25f, 200f), 0.0001f)
        // 两端夹紧。
        assertEquals(0f, seekStepSnapRatio(0f, 200f), 0f)
        assertEquals(1f, seekStepSnapRatio(1f, 200f), 0f)
        assertEquals(1f, seekStepSnapRatio(0.999f, 200f), 0.0001f)
    }

    @Test fun noSnapWhenDurationUnknownOrTooShort() {
        assertEquals(0.37f, seekStepSnapRatio(0.37f, 0f), 0f)
        assertEquals(0.37f, seekStepSnapRatio(0.37f, 5f), 0f)
        assertEquals(0.37f, seekStepSnapRatio(0.37f, -10f), 0f)
        // 越界输入先夹紧（不吸附口径下仍返回合法分数）。
        assertEquals(1f, seekStepSnapRatio(1.4f, 0f), 0f)
        assertEquals(0f, seekStepSnapRatio(-0.2f, 0f), 0f)
    }

    @Test fun stepIndexTracksCrossings() {
        assertEquals(12, seekStepIndex(0.30f, 200f))
        assertEquals(12, seekStepIndex(0.31f, 200f))
        assertEquals(14, seekStepIndex(0.34f, 200f))
        // 不吸附口径恒 0（宿主据此不发跨档触感）。
        assertEquals(0, seekStepIndex(0.9f, 0f))
        assertEquals(0, seekStepIndex(0.9f, 5f))
    }
}

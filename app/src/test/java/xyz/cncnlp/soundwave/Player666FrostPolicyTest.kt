package xyz.cncnlp.soundwave
import org.junit.Test
import org.junit.Assert.*
import xyz.cncnlp.soundwave.ui.common.pageFrostAllowed
class Player666FrostPolicyTest {
 @Test fun onlyExplicitWhiteThemeEnablesPageGlass(){assertTrue(pageFrostAllowed("light"));assertFalse(pageFrostAllowed("night"));assertFalse(pageFrostAllowed("dark"))}
}

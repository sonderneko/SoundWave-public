package xyz.cncnlp.soundwave
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
class ActivityDeliveryTest {
 private fun link(q:String)=Intent("view",Uri.parse("soundwave://search?q=$q"))
 private fun next()=runBlocking {withTimeoutOrNull(100){SchemeActionHolder.actions.first()}}
 @Test fun pendingLatestBeforeCollectorSurvivesRecreateOnce() {
  val a=MainActivity();a.intent=link("cold-pending");a.onCreate(null)
  a.onNewIntent(link("latest-pending"))
  val state=Bundle();a.onSaveInstanceState(state)
  val recreated=MainActivity();recreated.intent=a.intent;recreated.onCreate(state)
  assertEquals(SchemeActionHolder.Action.Search("latest-pending"),next())
  assertNull(next())
 }
 @Test fun consumedColdIntentDoesNotReplayAfterRecreate() {
  val a=MainActivity();a.intent=link("A");a.onCreate(null)
  assertEquals(SchemeActionHolder.Action.Search("A"),next())
  val state=Bundle();a.onSaveInstanceState(state)
  val recreated=MainActivity();recreated.intent=a.intent;recreated.onCreate(state)
  assertNull("old launch must not replay",next())
 }
 @Test fun hotIntentUpdatesActivityWithoutReplayingOnRecreate() {
  val a=MainActivity();a.intent=link("cold");a.onCreate(null);next()
  val hot=link("hot");a.onNewIntent(hot)
  assertSame(hot,a.intent)
  assertEquals(SchemeActionHolder.Action.Search("hot"),next())
  val state=Bundle();a.onSaveInstanceState(state)
  val recreated=MainActivity();recreated.intent=a.intent;recreated.onCreate(state)
  assertNull(next())
  recreated.onNewIntent(link("hot")) // Identical content is a legitimate NEW delivery.
  assertEquals(SchemeActionHolder.Action.Search("hot"),next())
 }
}

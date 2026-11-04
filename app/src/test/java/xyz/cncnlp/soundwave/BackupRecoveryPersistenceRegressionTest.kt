package xyz.cncnlp.soundwave
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
@RunWith(RobolectricTestRunner::class)
class BackupRecoveryPersistenceRegressionTest {
 @Test fun lateFailureMustPreserveReplayableInputAndBeforeImage() {
  val ctx: Context = RuntimeEnvironment.getApplication()
  File(ctx.filesDir, "backup_restore_recovery").deleteRecursively()
  ctx.getSharedPreferences("backup_restore_recovery_v1",0).edit().clear().commit()
  val broken = object : ContextWrapper(ctx) {
   override fun getSharedPreferences(name:String, mode:Int): SharedPreferences {
    val p=super.getSharedPreferences(name,mode)
    return if(name!="soundwave_scripts") p else object: SharedPreferences by p {
     override fun edit(): SharedPreferences.Editor = throw IllegalStateException("fixture scripts failure")
    }
   }
  }
  val input=JSONObject("""{"schema":"soundwave-backup","version":1,"user":{},"scripts":{"script_meta":"[]"}}""")
  assertTrue(runCatching { BackupStore.restore(broken,input) }.isFailure)
  assertTrue("late failure must retain original and projected input on disk",File(ctx.filesDir,"backup_restore_recovery").listFiles().orEmpty().any { it.isFile && it.length()>0 })
 }
}

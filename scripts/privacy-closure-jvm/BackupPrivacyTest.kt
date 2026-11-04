package xyz.cncnlp.soundwave
import android.content.Context
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
class BackupPrivacyTest {
 @Test fun genericBackupCannotExportUnknownOrScriptSecrets() {
  val c=Context();val p=c.getSharedPreferences("soundwave",0)
  p.edit().putString("theme_mode","dark").putString("future_credential","SECRET").putString("server_url","https://private").putBoolean("crash_auto_upload",true).apply()
  c.getSharedPreferences("soundwave_scripts",0).edit().putString("script_code_a","token='SECRET'").putString("script_meta","[{\"id\":\"a\",\"enabled\":true,\"description\":\"SECRET\"}]").apply()
  val backup=BackupStore.export(c)
  assertEquals("dark",backup.getJSONObject("settings").getString("theme_mode"))
  assertFalse(backup.toString().contains("SECRET"))
  assertFalse(backup.getJSONObject("settings").has("crash_auto_upload"))
 }
 @Test fun safePreferencesRestoreWithoutTouchingLocalConsentOrScriptCode() {
  val c=Context();val p=c.getSharedPreferences("soundwave",0)
  p.edit().putBoolean("crash_auto_upload",false).putString("user","local-user").apply()
  val scripts=c.getSharedPreferences("soundwave_scripts",0)
  scripts.edit().putString("script_code_a","LOCAL-SECRET").putString("script_meta","[{\"id\":\"a\",\"name\":\"Keep name\",\"enabled\":true},{\"id\":\"b\",\"enabled\":true}]").apply()
  val root=JSONObject().put("schema","soundwave-backup").put("version",1).put("user",JSONObject())
   .put("settings",JSONObject().put("theme_mode","dark").put("play_speed",1.5).put("crash_auto_upload",true).put("user","foreign").put("unknown","bad"))
   .put("scripts",JSONObject().put("script_code_a","FOREIGN-SECRET").put("script_meta","[{\"id\":\"a\",\"enabled\":false},{\"id\":\"missing\",\"enabled\":true}]"))
  BackupStore.restore(c,root)
  assertEquals("dark",p.getString("theme_mode",null));assertEquals(1.5f,p.all["play_speed"])
  assertEquals(false,p.getBoolean("crash_auto_upload",true));assertEquals("local-user",p.getString("user",null));assertFalse(p.all.containsKey("unknown"))
  assertEquals("LOCAL-SECRET",scripts.getString("script_code_a",null))
  val meta=org.json.JSONArray(scripts.getString("script_meta",null))
  assertEquals(2,meta.length());assertEquals("Keep name",meta.getJSONObject(0).getString("name"));assertFalse(meta.getJSONObject(0).getBoolean("enabled"))
 }
 @Test fun invalidAllowedPreferenceFailsBeforeAnyRestoreMutation() {
  val c=Context();LocalStore.saves=0
  val root=JSONObject().put("schema","soundwave-backup").put("version",1).put("user",JSONObject())
   .put("settings",JSONObject().put("theme_mode","dark").put("play_speed","fast"))
  try {BackupStore.restore(c,root);fail("invalid type accepted")}catch(_:IllegalArgumentException){}
  assertEquals(0,LocalStore.saves)
  assertFalse(c.getSharedPreferences("soundwave",0).all.containsKey("theme_mode"))
 }
}

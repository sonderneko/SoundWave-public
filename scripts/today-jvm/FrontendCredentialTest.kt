package xyz.cncnlp.soundwave
import org.junit.Assert.*
import org.junit.Test
class FrontendCredentialTest {
 @Test fun coldStartRejectsOtherOwnerAndUnboundLegacyCipher() {
  for (owner in listOf("Alice", "")) {
   val p=FakePrefs(mutableMapOf("user" to "Bob","pass" to "v2:alice","pass_owner" to owner))
   invalidateMismatchedFrontendCredential(p)
   assertNull(p.values["pass"]);assertNull(p.values["pass_owner"])
  }
  val p=FakePrefs(mutableMapOf("user" to "Bob","pass" to "v2:b","pass_owner" to "Bob"))
  invalidateMismatchedFrontendCredential(p);assertEquals("v2:b",p.values["pass"])
 }

 @Test fun failedEncryptionClearsPreviousOwnerCipherAtomically() {
  val p=FakePrefs(mutableMapOf("user" to "Alice","pass" to "v2:alice","pass_owner" to "Alice"))
  assertFalse(persistFrontendAccountCredentials(p,"Bob",null,true))
  assertEquals("Bob",p.values["user"]);assertNull(p.values["pass"]);assertNull(p.values["pass_owner"])
  assertEquals(1,p.commits)
 }
 @Test fun newOwnerSuccessIsBoundAndDiskFailureIsNotSaved() {
  val p=FakePrefs();assertTrue(persistFrontendAccountCredentials(p,"Bob","v2:bob",true))
  assertEquals("Bob",p.values["user"]);assertEquals("Bob",p.values["pass_owner"]);assertEquals("v2:bob",p.values["pass"])
  assertEquals(1,p.commits);p.ok=false;assertFalse(persistFrontendAccountCredentials(p,"Carol","v2:c",true))
 }
 @Test fun blockedRenameCannotRetainCipherOrOwner() {
  val p=FakePrefs(mutableMapOf("user" to "Alice","pass" to "v2:a","pass_owner" to "Alice"))
  assertFalse(persistFrontendAccountCredentials(p,"Bob","v2:b",false))
  assertNull(p.values["pass"]);assertNull(p.values["pass_owner"]);assertEquals(false,p.values["logged_in"])
 }
}

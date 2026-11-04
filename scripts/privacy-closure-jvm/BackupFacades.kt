package xyz.cncnlp.soundwave
import android.content.Context
import org.json.JSONObject
class Song(val id:String="") {fun toJson()=JSONObject().put("id",id);companion object{fun fromJson(o:JSONObject)=Song(o.optString("id"))}}
data class DownloadItem(val song:Song,val path:String="",val size:Long=0,val time:Long=0,val status:String="",val progress:Float=0f,val error:String="",val quality:String="",val platform:String="",val scriptId:String="",val resolveFailure:String="",val taskId:String="")
object LocalStore {
 var saves=0
 class UserBundle(val favs:List<Song> = emptyList(),val playlists:List<Playlist> = emptyList(),val avatarB64:String="") {
 fun toCloudJson()=JSONObject()
 companion object{fun fromCloud(o:JSONObject)=UserBundle()}
 }
 class Playlist(val id:String)
 fun load(c:Context)=UserBundle()
 fun loadHistory(c:Context)=emptyList<Song>()
 fun loadDownloads(c:Context)=emptyList<DownloadItem>()
 fun saveForGenericBackupRestore(c:Context,b:UserBundle){saves++}
 fun replaceHistory(c:Context,s:List<Song>,limit:Int){}
 fun clearDownloads(c:Context){}
 fun addDownload(c:Context,d:DownloadItem){}
}
object ListenStats {fun localDailyListenStats(c:Context)=emptyMap<String,Any>();fun restoreLocalDailyListenStats(c:Context,s:String)=true}
object ListenStatsDailyStore{fun localProjection(s:String,t:Long){}}
object PlaybackHistoryPolicy{fun retainRecent(s:List<Song>,limit:Int)=s}
object AvatarStore{fun saveFromB64(c:Context,u:String,b:String){}}

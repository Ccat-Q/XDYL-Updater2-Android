package com.ccatq.xdylupdater2.downloads

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class DownloadState(val label: String) { queued("排队中"), downloading("下载中"), verifying("校验中"), completed("已完成"), failed("失败") }
@Entity(tableName = "downloads")
data class DownloadRecord(
    @PrimaryKey val id: String,
    val remoteURL: String,
    val filename: String,
    val expectedSHA256: String?,
    val created: Long,
    val systemId: Long? = null,
    val state: String = DownloadState.queued.name,
    val downloaded: Long = 0,
    val total: Long = -1,
    val localPath: String? = null,
    val message: String? = null,
    val cellular: Boolean = true
)
@Dao interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY created DESC") fun observe(): Flow<List<DownloadRecord>>
    @Query("SELECT * FROM downloads ORDER BY created DESC") suspend fun all(): List<DownloadRecord>
    @Query("SELECT * FROM downloads WHERE id = :id") suspend fun find(id: String): DownloadRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: DownloadRecord)
    @Query("DELETE FROM downloads WHERE id = :id") suspend fun delete(id: String)
}
@Database(entities = [DownloadRecord::class], version = 1, exportSchema = true)
abstract class DownloadDatabase : RoomDatabase() { abstract fun downloads(): DownloadDao }

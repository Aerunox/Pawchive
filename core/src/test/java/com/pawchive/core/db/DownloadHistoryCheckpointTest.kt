package com.pawchive.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pawchive.core.model.DownloadRecord
import com.pawchive.core.model.DownloadStatus
import com.pawchive.core.model.DownloadType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 下载断点续传检查点测试（FEAT-RESUME-RESTART）。
 *
 * 覆盖核心场景：
 * - 新记录的 tempOffset / tempTotal 缺省 0（无断点语义）
 * - updateStatusAndCheckpoint 持久化字节位置与总长（PAUSED 落库语义）
 * - RUNNING 周期检查点覆盖写入（进度推进语义）
 * - 检查点不影响 filePath / fileSize 等其它字段
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadHistoryCheckpointTest {

    private lateinit var db: PawchiveDatabase
    private lateinit var dao: DownloadHistoryDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PawchiveDatabase::class.java
        ).build()
        dao = db.downloadHistoryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun record(id: String = "rec-1") = DownloadRecord(
        id = id,
        url = "https://file.pawchive.pw/data/ab/cd/abcd.zip",
        fileName = "abcd.zip",
        mimeType = "application/zip",
        type = DownloadType.ATTACHMENT,
        dedupKey = "dedup-$id",
        status = DownloadStatus.PENDING,
        createdAt = 1000L
    )

    @Test
    fun `new record defaults to zero checkpoint`() = runBlocking {
        dao.upsert(record())
        val loaded = dao.getById("rec-1")!!
        assertEquals(0L, loaded.tempOffset)
        assertEquals(0L, loaded.tempTotal)
    }

    @Test
    fun `updateStatusAndCheckpoint persists offset and total`() = runBlocking {
        dao.upsert(record())

        dao.updateStatusAndCheckpoint("rec-1", DownloadStatus.PAUSED, 42, 1234L, 5678L)

        val loaded = dao.getById("rec-1")!!
        assertEquals(DownloadStatus.PAUSED, loaded.status)
        assertEquals(42, loaded.progress)
        assertEquals(1234L, loaded.tempOffset)
        assertEquals(5678L, loaded.tempTotal)
    }

    @Test
    fun `running checkpoint overwrites previous values`() = runBlocking {
        dao.upsert(record())
        dao.updateStatusAndCheckpoint("rec-1", DownloadStatus.RUNNING, 10, 500L, 5678L)

        dao.updateStatusAndCheckpoint("rec-1", DownloadStatus.RUNNING, 20, 1200L, 5678L)

        val loaded = dao.getById("rec-1")!!
        assertEquals(1200L, loaded.tempOffset)
        assertEquals(20, loaded.progress)
    }

    @Test
    fun `checkpoint update keeps other columns untouched`() = runBlocking {
        dao.upsert(record())
        dao.updateStatus(
            "rec-1", DownloadStatus.COMPLETED, 100,
            filePath = "content://media/1", fileSize = 5678L,
            errorMessage = null, completedAt = 2000L
        )

        dao.updateStatusAndCheckpoint("rec-1", DownloadStatus.COMPLETED, 100, 0L, 0L)

        val loaded = dao.getById("rec-1")!!
        assertEquals("content://media/1", loaded.filePath)
        assertEquals(5678L, loaded.fileSize)
        assertEquals(2000L, loaded.completedAt)
    }
}

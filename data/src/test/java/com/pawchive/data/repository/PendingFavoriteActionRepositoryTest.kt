package com.pawchive.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.pawchive.core.db.PawchiveDatabase
import com.pawchive.core.model.PendingFavoriteActionKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 离线收藏动作队列仓库测试（FEAT-OFFLINE-FAV-QUEUE）。
 *
 * 覆盖核心场景：
 * - 帖子 / 创作者动作的幂等键构造（objectKey 格式）
 * - 同一对象重复入队 → 最新意图覆盖（add 后 remove 只保留 remove）
 * - 入队后调度唯一补写任务（WorkManager unique work）
 * - 补写成功移除后计数归零
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingFavoriteActionRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: PawchiveDatabase
    private lateinit var repository: PendingFavoriteActionRepository

    @Before
    fun setup() {
        // enqueue() 内部会调度 WorkManager 任务，测试环境需初始化
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, PawchiveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PendingFavoriteActionRepository(context, db.pendingFavoriteActionDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `enqueue post action builds post objectKey`() = runBlocking {
        repository.enqueue(PendingFavoriteActionKind.ADD_POST, "fanbox", "alice", "1")

        val all = repository.getAll()
        assertEquals(1, all.size)
        assertEquals("post|fanbox|alice|1", all[0].objectKey)
        assertEquals(PendingFavoriteActionKind.ADD_POST.name, all[0].kind)
        assertEquals("1", all[0].postId)
    }

    @Test
    fun `enqueue creator action builds creator objectKey without postId`() = runBlocking {
        repository.enqueue(PendingFavoriteActionKind.ADD_CREATOR, "patreon", "bob", null)

        val all = repository.getAll()
        assertEquals(1, all.size)
        assertEquals("creator|patreon|bob", all[0].objectKey)
        assertEquals("bob", all[0].creatorId)
        assertNull(all[0].postId)
    }

    @Test
    fun `re-enqueue same object keeps latest intent`() = runBlocking {
        repository.enqueue(PendingFavoriteActionKind.ADD_POST, "fanbox", "alice", "1")
        repository.enqueue(PendingFavoriteActionKind.REMOVE_POST, "fanbox", "alice", "1")

        val all = repository.getAll()
        assertEquals(1, all.size)
        assertEquals(PendingFavoriteActionKind.REMOVE_POST.name, all[0].kind)
    }

    @Test
    fun `enqueue schedules unique sync work`() = runBlocking {
        repository.enqueue(PendingFavoriteActionKind.ADD_POST, "fanbox", "alice", "1")

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(PendingFavoriteActionRepository.WORK_NAME).get()
        assertEquals(1, infos.size)
    }

    @Test
    fun `remove empties queue and count`() = runBlocking {
        repository.enqueue(PendingFavoriteActionKind.ADD_POST, "fanbox", "alice", "1")
        assertEquals(1, repository.observeCount().first())

        repository.remove("post|fanbox|alice|1")

        assertEquals(0, repository.getAll().size)
        assertEquals(0, repository.observeCount().first())
    }
}

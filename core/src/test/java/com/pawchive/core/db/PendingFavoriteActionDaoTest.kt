package com.pawchive.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pawchive.core.model.PendingFavoriteActionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 离线收藏动作队列 DAO 测试（FEAT-OFFLINE-FAV-QUEUE）。
 *
 * 覆盖核心场景：
 * - upsert 后 getAll 按 createdAt 升序返回
 * - 同 objectKey（幂等键）upsert 覆盖，实现"最新意图优先"
 * - observeCount 反映队列条数
 * - delete / clearAll 移除
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingFavoriteActionDaoTest {

    private lateinit var db: PawchiveDatabase
    private lateinit var dao: PendingFavoriteActionDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PawchiveDatabase::class.java
        ).build()
        dao = db.pendingFavoriteActionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun action(
        objectKey: String,
        kind: String = "ADD_POST",
        createdAt: Long = 1L
    ) = PendingFavoriteActionEntity(
        objectKey = objectKey,
        kind = kind,
        service = "fanbox",
        creatorId = "alice",
        postId = if (objectKey.startsWith("post|")) "1" else null,
        createdAt = createdAt
    )

    @Test
    fun `getAll returns actions ordered by createdAt`() = runBlocking {
        dao.upsert(action("post|fanbox|alice|2", createdAt = 2L))
        dao.upsert(action("post|fanbox|alice|1", createdAt = 1L))
        dao.upsert(action("creator|fanbox|alice", createdAt = 3L))

        val all = dao.getAll()
        assertEquals(listOf(1L, 2L, 3L), all.map { it.createdAt })
    }

    @Test
    fun `upsert with same objectKey overwrites`() = runBlocking {
        dao.upsert(action("post|fanbox|alice|1", kind = "ADD_POST"))
        dao.upsert(action("post|fanbox|alice|1", kind = "REMOVE_POST"))

        val all = dao.getAll()
        assertEquals(1, all.size)
        assertEquals("REMOVE_POST", all[0].kind)
    }

    @Test
    fun `observeCount reflects queue size`() = runBlocking {
        assertEquals(0, dao.observeCount().first())
        dao.upsert(action("post|fanbox|alice|1"))
        dao.upsert(action("creator|fanbox|alice"))
        assertEquals(2, dao.observeCount().first())
    }

    @Test
    fun `delete removes single objectKey`() = runBlocking {
        dao.upsert(action("post|fanbox|alice|1"))
        dao.upsert(action("creator|fanbox|alice"))

        dao.delete("post|fanbox|alice|1")

        assertEquals(listOf("creator|fanbox|alice"), dao.getAll().map { it.objectKey })
    }

    @Test
    fun `clearAll empties queue`() = runBlocking {
        dao.upsert(action("post|fanbox|alice|1"))
        dao.clearAll()
        assertEquals(0, dao.getAll().size)
    }
}

package com.pawchive.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 文件哈希反查结果映射测试（FEAT-HASH-SEARCH）。
 *
 * 覆盖场景：
 * - 站点帖子：file / attachments / published 透传，substring → content
 * - Discord 帖子：server → user、channel → userName、service 固定 discord
 * - 空结果：posts / discordPosts 为 null 或空列表时返回空列表
 * - 合并顺序：站点帖子在前，Discord 帖子在后
 */
class FileSearchExtensionsTest {

    private fun sitePost(id: String = "1", user: String = "alice", service: String = "fanbox") =
        FileSearchPost(
            fileId = 100L + id.toLong(),
            id = id,
            user = user,
            service = service,
            title = "title $id",
            substring = "excerpt $id",
            published = "2026-01-0$id",
            file = PostFile(name = "img$id.png", path = "/ab/cd/abcdef$id.png"),
            attachments = listOf(Attachment(name = "att$id.zip", path = "/ab/cd/att$id.zip"))
        )

    private fun discordPost(id: String = "d1") = FileSearchDiscordPost(
        fileId = 900L,
        id = id,
        server = "9527",
        channel = "general",
        substring = "discord excerpt",
        published = "2026-02-01"
    )

    @Test
    fun `site posts map through with file and attachments`() {
        val result = FileSearchResult(
            id = 1L, hash = "ab", mime = "image/png", ext = ".png", size = 123L,
            posts = listOf(sitePost("1"), sitePost("2", user = "bob", service = "patreon")),
            discordPosts = null
        )
        val posts = result.toPosts()

        assertEquals(2, posts.size)
        val first = posts[0]
        assertEquals("1", first.id)
        assertEquals("alice", first.user)
        assertEquals("fanbox", first.service)
        assertEquals("title 1", first.title)
        assertEquals("excerpt 1", first.content)
        assertEquals("2026-01-01", first.published)
        assertEquals("/ab/cd/abcdef1.png", first.file?.path)
        assertEquals("att1.zip", first.attachments?.first()?.name)
        assertNull(first.userName)
    }

    @Test
    fun `discord posts map to plain-text posts with discord service`() {
        val result = FileSearchResult(
            id = 1L, hash = "ab", mime = "image/png", ext = ".png", size = 123L,
            posts = null,
            discordPosts = listOf(discordPost())
        )
        val posts = result.toPosts()

        assertEquals(1, posts.size)
        val d = posts[0]
        assertEquals("d1", d.id)
        assertEquals("9527", d.user)
        assertEquals("general", d.userName)
        assertEquals("discord", d.service)
        assertEquals("discord excerpt", d.title)
        assertNull(d.file)
        assertNull(d.attachments)
    }

    @Test
    fun `site posts come first then discord posts`() {
        val result = FileSearchResult(
            id = 1L, hash = "ab", mime = "image/png", ext = ".png", size = 123L,
            posts = listOf(sitePost("1")),
            discordPosts = listOf(discordPost())
        )
        val posts = result.toPosts()

        assertEquals(2, posts.size)
        assertEquals("1", posts[0].id)
        assertEquals("d1", posts[1].id)
    }

    @Test
    fun `null lists produce empty result`() {
        val result = FileSearchResult(
            id = 1L, hash = "ab", mime = "image/png", ext = ".png", size = 123L,
            posts = null, discordPosts = null
        )
        assertEquals(0, result.toPosts().size)
    }
}

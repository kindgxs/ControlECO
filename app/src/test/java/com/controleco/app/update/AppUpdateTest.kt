package com.controleco.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动更新的纯函数部分：release JSON 解析（真实 API 返回结构）与版本比较。
 * 网络部分（直连/镜像切换）不做单测，靠真机日志核对。
 */
class AppUpdateTest {

    private val releaseJson = """
        [
          {
            "url": "https://api.github.com/repos/ArrogHie/ControlECO/releases/2",
            "tag_name": "v0.4.6-beta",
            "name": "v0.4.6-beta",
            "body": "### 修复\n\n- 通知金额误取营销数字\r\n- 商户显示",
            "draft": false,
            "prerelease": true,
            "assets": [
              {
                "name": "ControlECO-0.4.6-beta.apk",
                "browser_download_url": "https://github.com/ArrogHie/ControlECO/releases/download/v0.4.6-beta/ControlECO-0.4.6-beta.apk"
              },
              {
                "name": "sources.zip",
                "browser_download_url": "https://github.com/ArrogHie/ControlECO/releases/download/v0.4.6-beta/sources.zip"
              }
            ]
          },
          {
            "url": "https://api.github.com/repos/ArrogHie/ControlECO/releases/1",
            "tag_name": "v0.4.5-beta",
            "name": "v0.4.5-beta",
            "body": "旧版本",
            "draft": false,
            "prerelease": true,
            "assets": [
              {
                "name": "ControlECO-0.4.5-beta.apk",
                "browser_download_url": "https://github.com/ArrogHie/ControlECO/releases/download/v0.4.5-beta/ControlECO-0.4.5-beta.apk"
              }
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun `parse release json extracts tag apk url and notes`() {
        val r = AppUpdate.parseReleaseJson(releaseJson)!!
        assertEquals("v0.4.6-beta", r.tagName)
        assertEquals(
            "https://github.com/ArrogHie/ControlECO/releases/download/v0.4.6-beta/ControlECO-0.4.6-beta.apk",
            r.apkUrl,
        )
        // 镜像地址 = 前缀拼接，zip 资产不参与（取首个 .apk）
        assertEquals(
            "https://gh-proxy.com/https://github.com/ArrogHie/ControlECO/releases/download/v0.4.6-beta/ControlECO-0.4.6-beta.apk",
            r.apkUrlMirror,
        )
        assertTrue(r.notes.contains("通知金额误取营销数字"))
        assertTrue(r.notes.contains("\n"))
    }

    @Test
    fun `parse release json without apk asset keeps empty urls`() {
        val json = """[{"tag_name": "v0.5.0", "body": "仅源码"}]"""
        val r = AppUpdate.parseReleaseJson(json)!!
        assertEquals("v0.5.0", r.tagName)
        assertEquals("", r.apkUrl)
        assertEquals("", r.apkUrlMirror)
    }

    @Test
    fun `apk url scoped to newest release when older ones have assets`() {
        // 列表接口新→旧：最新 release 没挂 APK 时不能错配到旧版本的资产
        val json = """
            [
              {"tag_name": "v0.5.0", "assets": []},
              {"tag_name": "v0.4.6-beta", "assets": [
                {"browser_download_url": "https://github.com/ArrogHie/ControlECO/releases/download/v0.4.6-beta/x.apk"}
              ]}
            ]
        """.trimIndent()
        val r = AppUpdate.parseReleaseJson(json)!!
        assertEquals("v0.5.0", r.tagName)
        assertEquals("", r.apkUrl)
    }

    @Test
    fun `parse release json without tag returns null`() {
        assertNull(AppUpdate.parseReleaseJson("""{"message": "Not Found"}"""))
    }

    // ---------- 版本比较 ----------

    @Test
    fun `newer patch version detected`() {
        assertTrue(AppUpdate.isNewerVersion("v0.4.6-beta", "0.4.5-beta"))
        assertFalse(AppUpdate.isNewerVersion("v0.4.5-beta", "0.4.5-beta"))
        assertFalse(AppUpdate.isNewerVersion("v0.4.4-beta", "0.4.5-beta"))
    }

    @Test
    fun `minor and major bump detected`() {
        assertTrue(AppUpdate.isNewerVersion("0.5.0-beta", "0.4.9-beta"))
        assertTrue(AppUpdate.isNewerVersion("1.0.0", "0.9.9-beta"))
    }

    @Test
    fun `stable beats prerelease on same numbers`() {
        // 去掉 -beta 尾巴的收尾发布也算新版本
        assertTrue(AppUpdate.isNewerVersion("0.4.6", "0.4.6-beta"))
        assertFalse(AppUpdate.isNewerVersion("0.4.6-beta", "0.4.6"))
    }

    @Test
    fun `unparsable tag is never newer`() {
        assertFalse(AppUpdate.isNewerVersion("not-a-version", "0.4.5-beta"))
        assertFalse(AppUpdate.isNewerVersion("", "0.4.5-beta"))
    }

    @Test
    fun `integer amount differences`() {
        assertTrue(AppUpdate.isNewerVersion("v1", "0.4.5-beta"))
        assertTrue(AppUpdate.isNewerVersion("0.10.0", "0.9.9"))
    }

    // ---------- 本版本分支仓库：最新提交解析与比对 ----------

    private val commitJson = """
        {
          "sha": "8f3c1d2a9b7e6f5d4c3b2a190817263544556677",
          "node_id": "C_kwDOAbc",
          "commit": {
            "author": { "name": "kindgxs", "email": "dev@example.com", "date": "2026-10-25T10:00:00Z" },
            "message": "feat: 新增开关",
            "tree": { "sha": "1111111111111111111111111111111111111111" }
          },
          "parents": [ { "sha": "2222222222222222222222222222222222222222" } ]
        }
    """.trimIndent()

    @Test
    fun `parse commit json takes head sha`() {
        assertEquals("8f3c1d2a9b7e6f5d4c3b2a190817263544556677", AppUpdate.parseCommitSha(commitJson))
        assertNull(AppUpdate.parseCommitSha("""{"message":"Not Found"}"""))
    }

    @Test
    fun `commit compare uses short sha prefix`() {
        assertTrue(AppUpdate.sameCommit("8f3c1d2a9b7e6f5d", "8f3c1d2"))
        assertTrue(AppUpdate.sameCommit("8f3c1d2", "8f3c1d2a9b7e6f5d"))
        assertFalse(AppUpdate.sameCommit("8f3c1d2", "9a8b7c6"))
        assertFalse(AppUpdate.sameCommit("", "8f3c1d2"))
    }
}

package com.controleco.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新检查的纯函数部分：release JSON 解析（真实 API 返回结构）与版本比较。
 * 应用只提示新版本并跳转原仓库 Release 页面，网络部分不做单测，靠真机日志核对。
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
              }
            ]
          },
          {
            "url": "https://api.github.com/repos/ArrogHie/ControlECO/releases/1",
            "tag_name": "v0.4.5-beta",
            "name": "v0.4.5-beta",
            "body": "旧版本",
            "draft": false,
            "prerelease": true
          }
        ]
    """.trimIndent()

    @Test
    fun `parse release json extracts tag notes and release page`() {
        val r = AppUpdate.parseReleaseJson(releaseJson)!!
        assertEquals("v0.4.6-beta", r.tagName)
        // 跳转地址指向原仓库该版本的 Release 页面
        assertEquals(
            "https://github.com/ArrogHie/ControlECO/releases/tag/v0.4.6-beta",
            r.releaseUrl,
        )
        assertTrue(r.notes.contains("通知金额误取营销数字"))
        assertTrue(r.notes.contains("\n"))
    }

    @Test
    fun `only newest release is used`() {
        // 列表接口新→旧：说明只取第一个 release，不串到旧版本
        val r = AppUpdate.parseReleaseJson(releaseJson)!!
        assertFalse(r.notes.contains("旧版本"))
        assertFalse(r.releaseUrl.contains("v0.4.5-beta"))
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
}

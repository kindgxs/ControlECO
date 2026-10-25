package com.controleco.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.controleco.app.BuildConfig
import com.controleco.app.util.AppLog
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 更新检查（只提示，不下载安装）：仅在设置页手动检查 GitHub Release。
 * 发现新版本只弹窗提示，按钮跳转原仓库（ArrogHie/ControlECO）的 Release 页面，
 * 由用户在浏览器里自行下载安装。本应用不再启动自动检查，也不再应用内下载/安装 APK。
 *
 * 网络是本应用唯一的联网用途：只访问 GitHub 的 releases 接口，不上传任何数据。
 */
object AppUpdate {

    private const val REPO = "ArrogHie/ControlECO"

    /**
     * 用 releases 列表接口而不是 releases/latest：latest 不返回预发布（prerelease），
     * 历史上 beta 曾以 prerelease 发布导致它 404；列表接口（未认证）对正式版与预发布都返回
     * 全部已发布 release（新→旧），草稿未认证不可见、不会误取。
     */
    private const val API_URL = "https://api.github.com/repos/$REPO/releases?per_page=5"
    /** 镜像：前缀拼接代理（仅用于 API 查询） */
    private const val MIRROR_PREFIX = "https://gh-proxy.com/"

    /** 直连超时给短一些，失败尽快切镜像；镜像同值 */
    private const val TIMEOUT_MS = 6_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _updateAvailable = MutableStateFlow<ReleaseInfo?>(null)

    /** 非空 = 检测到新版本（MainActivity 收集后弹提示框，处理完置回 null） */
    val updateAvailable: StateFlow<ReleaseInfo?> = _updateAvailable

    data class ReleaseInfo(
        /** tag 名（如 v0.4.6-beta），直接用于展示 */
        val tagName: String,
        /** 原仓库该版本的 Release 页面地址 */
        val releaseUrl: String,
        /** release 说明（原文截断展示） */
        val notes: String,
    )

    /** 对话框处理完（前往 Release / 知道了）后清掉，避免旋转屏幕重弹 */
    fun consumeUpdate() {
        _updateAvailable.value = null
    }

    /**
     * 手动检查（设置页「检查更新」）：查一次最新 Release。
     * 发现新版本 → 进 [updateAvailable] 弹窗（onResult 收到 null，页面不用再提示）；
     * 其余结果经 onResult 在主线程回调（无更新/失败，供页面 Toast 提示）。
     */
    fun checkNow(context: Context, onResult: (String?) -> Unit) {
        scope.launch {
            val release = runCatching { fetchLatestRelease() }
                .onFailure { AppLog.w("Update", "manual check failed", it) }
                .getOrNull()
            val message = when {
                release == null -> "检查失败：直连与镜像都未成功，请稍后再试"
                !isNewerVersion(release.tagName, BuildConfig.VERSION_NAME) ->
                    "已是最新版本（${BuildConfig.VERSION_NAME}）"
                else -> null
            }
            if (message == null && release != null) {
                AppLog.i("Update", "manual check: new version ${release.tagName}")
                _updateAvailable.value = release
            }
            mainHandler.post { onResult(message) }
        }
    }

    /** 打开原仓库 Release 页面；失败时返回提示文案（正常返回 null） */
    fun openReleasePage(context: Context, release: ReleaseInfo): String? = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        AppLog.i("Update", "open release page: ${release.releaseUrl}")
        null
    }.getOrElse {
        AppLog.w("Update", "open release page failed", it)
        "没有可用的浏览器，Release 地址：${release.releaseUrl}"
    }

    /** 直连 → 失败（除"仓库还没有 release"外）→ 镜像 */
    internal fun fetchLatestRelease(): ReleaseInfo? {
        val direct = fetch(API_URL)
        if (direct?.first == 404) return null // 仓库还没有任何 release，镜像也必然 404
        val json = direct?.takeIf { it.first in 200..299 }?.second
            ?: fetch(MIRROR_PREFIX + API_URL)?.takeIf { it.first in 200..299 }?.second
        if (json == null) {
            AppLog.i("Update", "fetch failed: direct=${direct?.first ?: "error"}")
            return null
        }
        return parseReleaseJson(json)
    }

    /** 返回 (HTTP 状态码, 响应体)；网络异常返回 null */
    private fun fetch(url: String): Pair<Int, String>? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ControlECO-App")
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        code to body
    } catch (e: Exception) {
        AppLog.w("Update", "fetch error url=$url: ${e.message}")
        null
    }

    /**
     * 从 releases 列表的 JSON 里取最新 release 的 tag 与说明，并拼出原仓库 Release 页面地址。
     * 用正则而非 org.json：字段位置固定、结构简单，且纯函数可直接 JVM 单测。
     * 列表是新→旧排序：只取**第一个** release 对象（截到第二个 tag_name 为止）。
     */
    internal fun parseReleaseJson(json: String): ReleaseInfo? {
        val tagMatch = Regex("""\"tag_name\"\s*:\s*\"([^\"]+)\"""").find(json) ?: return null
        val nextTag = Regex("""\"tag_name\"""").find(json, tagMatch.range.last + 1)
        val scope = if (nextTag != null) json.substring(0, nextTag.range.first) else json
        val rawBody = Regex("""\"body\"\s*:\s*\"((?:\\.|[^\"\\])*)\"""").find(scope)?.groupValues?.get(1)
        val tag = tagMatch.groupValues[1]
        return ReleaseInfo(
            tagName = tag,
            releaseUrl = "https://github.com/$REPO/releases/tag/$tag",
            notes = rawBody?.let { unescapeJson(it) }?.take(300).orEmpty(),
        )
    }

    /** JSON 字符串转义还原（\n \r \" \\ \/ 与 \uXXXX），够展示 release 说明即可 */
    private fun unescapeJson(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '\\' || i + 1 >= text.length) {
                sb.append(c); i++; continue
            }
            val next = text[i + 1]
            when (next) {
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\n'); i += 2 } // \r\n 展示用统一成 \n
                't' -> { sb.append('\t'); i += 2 }
                'u' -> {
                    val hex = if (i + 5 < text.length) text.substring(i + 2, i + 6) else null
                    val code = hex?.toIntOrNull(16)
                    if (code != null) { sb.append(code.toChar()); i += 6 } else { sb.append(next); i += 2 }
                }
                else -> { sb.append(next); i += 2 } // \" \\ \/ 等
            }
        }
        return sb.toString()
    }

    /**
     * 语义化版本比较（0.4.6-beta vs 0.4.5-beta）：
     * 数字段逐位比；相等时稳定版 > 预发布（0.4.5 比 0.4.5-beta 新，用于去 beta 尾巴的收尾发布）。
     * 解析不了的一律视为"不比当前新"（宁可不提醒）。
     */
    internal fun isNewerVersion(latest: String, current: String): Boolean {
        val l = parseVersion(latest) ?: return false
        val c = parseVersion(current) ?: return false
        for (i in 0 until maxOf(l.nums.size, c.nums.size)) {
            val lv = l.nums.getOrElse(i) { 0 }
            val cv = c.nums.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return c.pre != null && l.pre == null
    }

    private data class Version(val nums: List<Int>, val pre: String?)

    /** "v0.4.6-beta" → ([0,4,6], "beta")；数字段缺失按 0 补 */
    private fun parseVersion(text: String): Version? {
        val t = text.trim().removePrefix("v").removePrefix("V")
        if (t.isEmpty()) return null
        val base = t.substringBefore('-')
        val pre = t.substringAfter('-', "").ifEmpty { null }
        val nums = base.split('.').map { seg ->
            seg.filter { it.isDigit() }.ifEmpty { "0" }.toInt()
        }
        if (nums.none { it > 0 } && nums.all { it == 0 }) return null // 全 0/非数字，不是版本号
        return Version(nums, pre)
    }
}

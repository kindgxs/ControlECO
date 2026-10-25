package com.controleco.app.update

import android.os.Handler
import android.os.Looper
import com.controleco.app.BuildConfig
import com.controleco.app.util.AppLog
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 更新检查：**只提示是否有更新，不下载、不安装**（自动更新已关闭，启动不再检查）。
 *
 * 一次检查同时看两个来源：
 * 1. 上游开源仓库：以 releases 列表的最新 tag 与本包版本名比较；
 * 2. 本版本分支仓库：取分支最新提交的 sha，与本包构建时内置的 [BuildConfig.GIT_COMMIT] 比较。
 *
 * 网络仍是本应用唯一的联网用途：只访问 GitHub 的 releases / commits 接口，不上传任何数据。
 */
object AppUpdate {

    /** 上游开源仓库：按 release 版本号判断 */
    private const val UPSTREAM_REPO = "ArrogHie/ControlECO"
    /** 本版本分支仓库：按分支最新提交判断（当前安装包的分支来源） */
    private const val FORK_REPO = "kindgxs/ControlECO"
    private const val FORK_BRANCH = "New"

    /**
     * 用 releases 列表接口而不是 releases/latest：latest 不返回预发布（prerelease），
     * 历史上 beta 曾以 prerelease 发布导致它 404；列表接口（未认证）对正式版与预发布都返回
     * 全部已发布 release（新→旧），草稿未认证不可见、不会误取。
     */
    private const val API_UPSTREAM = "https://api.github.com/repos/$UPSTREAM_REPO/releases?per_page=5"
    /** 分支最新提交：返回的 JSON 里首个 "sha" 即该分支当前 HEAD */
    private const val API_FORK_HEAD = "https://api.github.com/repos/$FORK_REPO/commits/$FORK_BRANCH"
    /** 镜像：前缀拼接代理（API 查询支持） */
    private const val MIRROR_PREFIX = "https://gh-proxy.com/"

    /** 直连超时给短一些，失败尽快切镜像；镜像同值 */
    private const val TIMEOUT_MS = 6_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    data class ReleaseInfo(
        /** tag 名（如 v0.4.6-beta） */
        val tagName: String,
        /** GitHub 直连 APK 下载地址（更新改为仅提示后不再下载，保留解析结果供单测/排查） */
        val apkUrl: String,
        /** 镜像 APK 下载地址（同上，仅保留解析） */
        val apkUrlMirror: String,
        /** release 说明（原文截断展示） */
        val notes: String,
    )

    /**
     * 手动检查（设置页 / 关于页「检查更新」）：同时查上游仓库与本版本分支，
     * 合成一条「是否有更新」的文案，在主线程回调（不下载、不安装）。
     */
    fun checkNow(onResult: (String) -> Unit) {
        scope.launch {
            val message = runCatching { checkBothRepos() }
                .onFailure { AppLog.w("Update", "manual check failed", it) }
                .getOrElse { "检查失败：网络异常，请稍后再试" }
            AppLog.i("Update", "manual check: ${message.replace('\n', ' ')}")
            mainHandler.post { onResult(message) }
        }
    }

    /** 两个仓库各一行：有更新 / 无更新 / 检查失败 */
    private fun checkBothRepos(): String {
        val upstream = runCatching { fetchLatestRelease() }.getOrNull()
        val upstreamText = when {
            upstream == null -> "上游仓库：检查失败"
            isNewerVersion(upstream.tagName, BuildConfig.VERSION_NAME) ->
                "上游仓库：有更新（${upstream.tagName}）"
            else -> "上游仓库：无更新"
        }

        val headSha = runCatching { fetchForkHead() }.getOrNull()
        val localSha = BuildConfig.GIT_COMMIT
        val forkText = when {
            headSha == null -> "分支仓库：检查失败"
            localSha.isBlank() -> "分支仓库：最新 ${headSha.take(7)}"
            sameCommit(headSha, localSha) -> "分支仓库：无更新"
            else -> "分支仓库：有更新（${headSha.take(7)}）"
        }
        return "$upstreamText\n$forkText"
    }

    /**
     * 提交比对：两边可能都是完整 40 位 sha，也可能一侧是短哈希（7 位），
     * 取较短长度的前 7 位以内做不区分大小写的前缀比较；任一为空视为不同。
     */
    internal fun sameCommit(remoteSha: String, localSha: String): Boolean {
        if (remoteSha.isBlank() || localSha.isBlank()) return false
        val len = minOf(remoteSha.length, localSha.length, 7)
        return remoteSha.take(len).equals(localSha.take(len), ignoreCase = true)
    }

    /** 分支最新提交 sha：直连 → 失败改走镜像；都失败返回 null */
    internal fun fetchForkHead(): String? {
        val direct = fetch(API_FORK_HEAD)
        val json = direct?.takeIf { it.first in 200..299 }?.second
            ?: fetch(MIRROR_PREFIX + API_FORK_HEAD)?.takeIf { it.first in 200..299 }?.second
        if (json == null) {
            AppLog.i("Update", "fork head fetch failed: direct=${direct?.first ?: "error"}")
            return null
        }
        return parseCommitSha(json)
    }

    /**
     * 从 commits 接口的 JSON 里取提交 sha。用正则而非 org.json：只看首个 "sha"
     * （顶层提交自身），纯函数可直接 JVM 单测。
     */
    internal fun parseCommitSha(json: String): String? =
        Regex("""\"sha\"\s*:\s*\"([0-9a-fA-F]{7,40})\"""").find(json)?.groupValues?.get(1)

    /** 直连 → 失败（除"仓库还没有 release"外）→ 镜像 */
    internal fun fetchLatestRelease(): ReleaseInfo? {
        val direct = fetch(API_UPSTREAM)
        if (direct?.first == 404) return null // 仓库还没有任何 release，镜像也必然 404
        val json = direct?.takeIf { it.first in 200..299 }?.second
            ?: fetch(MIRROR_PREFIX + API_UPSTREAM)?.takeIf { it.first in 200..299 }?.second
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
     * 从 releases 列表的 JSON 里取最新 release 的 tag、首个 .apk 资产与说明。
     * 用正则而非 org.json：字段位置固定、结构简单，且纯函数可直接 JVM 单测。
     * 列表是新→旧排序：tag/资产/说明只取**第一个** release 对象（截到第二个 tag_name 为止），
     * 防止最新 release 没挂 APK 时错配到旧版本的资产。
     */
    internal fun parseReleaseJson(json: String): ReleaseInfo? {
        val tagMatch = Regex("""\"tag_name\"\s*:\s*\"([^\"]+)\"""").find(json) ?: return null
        val nextTag = Regex("""\"tag_name\"""").find(json, tagMatch.range.last + 1)
        val scope = if (nextTag != null) json.substring(0, nextTag.range.first) else json
        val apkUrl = Regex("""\"browser_download_url\"\s*:\s*\"(https:[^\"]+\.apk)\"""").find(scope)?.groupValues?.get(1)
        val rawBody = Regex("""\"body\"\s*:\s*\"((?:\\.|[^\"\\])*)\"""").find(scope)?.groupValues?.get(1)
        return ReleaseInfo(
            tagName = tagMatch.groupValues[1],
            apkUrl = apkUrl.orEmpty(),
            apkUrlMirror = apkUrl?.let { MIRROR_PREFIX + it }.orEmpty(),
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

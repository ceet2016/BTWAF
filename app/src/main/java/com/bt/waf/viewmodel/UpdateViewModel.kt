package com.bt.waf.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bt.waf.data.update.AppUpdater
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * 在线更新的 ViewModel。
 *
 * 状态机（界面据此渲染，不要自己推断当前状态）：
 *
 * ```
 *  Idle ──check()──► Checking ──┬─► UpToDate        已是最新
 *                               ├─► Available       有新版本（尚未下载任何字节）
 *                               └─► Failed          网络 / 解析失败
 *  Available ──download()──► Downloading(进度) ──┬─► Ready（校验通过）
 *                             └─► Failed
 *  Ready ──install()──► 系统安装器被拉起
 * ```
 *
 * 关键点：**查版本与下载是两个独立动作**。查版本只拉几百字节的
 * version.json，下载要用户点「立即更新」才发生。这样流量消耗与实际
 * 更新次数成正比，而不是与打开 App 次数成正比。
 */
class UpdateViewModel : ViewModel() {

    var state by mutableStateOf<State>(State.Idle)
        private set

    private var job: Job? = null

    /** 由页面在 Composition 里注入，供拉起系统安装器使用。 */
    var appContext: Context? = null

    sealed interface State {
        data object Idle : State
        data object Checking : State

        data class UpToDate(val current: String) : State

        /** 有新版本。构造时不含任何本地文件，纯元信息。 */
        data class Available(
            val versionName: String,
            val versionCode: Int,
            /** 原始字节数，供进度条算百分比。0 表示远端未给出大小。 */
            val sizeBytes: Long,
            val sizeText: String,
            val publishedAt: String,
            val apkUrl: String,
            /** 远端提供的 SHA256，用于下载后校验。空串表示远端未提供。 */
            val sha256: String,
        ) : State

        data class Downloading(val read: Long, val total: Long) : State

        /** 已下载并通过 SHA256 校验，可交给系统安装。 */
        data class Ready(val path: String) : State

        data class Failed(val reason: String) : State
    }

    fun check() {
        if (state is State.Checking) return
        state = State.Checking
        job = viewModelScope.launch {
            when (val r = AppUpdater.check()) {
                is AppUpdater.CheckResult.UpToDate ->
                    state = State.UpToDate(r.current)

                is AppUpdater.CheckResult.Available -> {
                    val i = r.info
                    state = State.Available(
                        versionName = i.versionName,
                        versionCode = i.versionCode,
                        sizeBytes = i.size,
                        sizeText = humanSize(i.size),
                        publishedAt = friendlyTime(i.publishedAt),
                        apkUrl = r.apkUrl,
                        sha256 = i.sha256,
                    )
                }

                // CheckResult.Checking 是给「调用方自己正在查」的枚举值，
                // 这里的 coroutine 已经await 完了，正常不会命中。
                is AppUpdater.CheckResult.Checking -> state = State.Checking

                is AppUpdater.CheckResult.Failed ->
                    state = State.Failed(r.reason)
            }
        }
    }

    /** 用户确认后才调用：这一步才开始产生流量。 */
    fun download(onReady: (File) -> Unit) {
        val cur = state as? State.Available ?: return
        job = viewModelScope.launch {
            state = State.Downloading(0L, cur.sizeBytes)

            when (val r = AppUpdater.download(cur.apkUrl) { read, total ->
                // 下载过程中持续重组来推进进度条。
                if (state is State.Downloading) state = State.Downloading(read, total)
            }) {
                is AppUpdater.DownloadResult.Failed ->
                    state = State.Failed(r.reason)

                is AppUpdater.DownloadResult.Success -> {
                    val ctx = appContext
                    if (ctx == null) {
                        r.file.delete()
                        state = State.Failed("缺少上下文，无法准备安装")
                        return@launch
                    }

                    // ⚠️ 远端给了校验和就必须验证。
                    // 不一致时**立即删掉文件**：留着的话下次续传会在这份
                    // 坏文件上继续追加，越续越坏且永远校验不过。
                    if (cur.sha256.isNotBlank()) {
                        val actual = AppUpdater.sha256(r.file)
                        if (actual == null || !actual.equals(cur.sha256, ignoreCase = true)) {
                            r.file.delete()
                            state = State.Failed("安装包校验未通过，请重试")
                            return@launch
                        }
                    }

                    val staged = AppUpdater.stageForInstall(ctx, r.file)
                    if (staged == null) {
                        r.file.delete()
                        state = State.Failed("无法准备安装包")
                        return@launch
                    }
                    r.file.delete()   // .part 用完即弃，留着只占空间
                    state = State.Ready(staged.absolutePath)
                    onReady(staged)
                }
            }
        }
    }

    /** 拉起系统安装器。 */
    fun install() {
        val p = (state as? State.Ready)?.path ?: return
        val ctx = appContext ?: return
        if (!AppUpdater.install(ctx, File(p))) {
            state = State.Failed("未找到可用的系统安装器")
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        state = State.Idle
    }

    private fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> "未知大小"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    private fun friendlyTime(iso: String): String {
        val ts = runCatching {
            // 形如 2026-10-05T12:00:44Z
            java.time.LocalDateTime.parse(iso.removeSuffix("Z"))
                .atZone(java.time.ZoneOffset.UTC)
                .toInstant().toEpochMilli()
        }.getOrNull() ?: return ""
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 3600_000L -> "刚刚发布"
            diff < 86400_000L -> "${diff / 3600_000L} 小时前"
            diff < 30L * 86400_000L -> "${diff / 86400_000L} 天前"
            else -> iso.substringBefore('T')
        }
    }
}
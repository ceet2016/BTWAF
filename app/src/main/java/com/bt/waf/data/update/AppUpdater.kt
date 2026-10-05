package com.bt.waf.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import com.bt.waf.BuildConfig
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 下载进度回调：(已读字节, 总字节)。总字节未知时为 -1。
 *
 * 提到文件顶层而不是放在 object 内部：Kotlin **不支持局部/嵌套 typealias**，
 * 放在 object 里会直接编译报错「Nested and local type aliases are not supported」。
 */
typealias DownloadProgress = (read: Long, total: Long) -> Unit

/**
 * 在线更新。
 *
 * ## 为什么查版本要用「commit 附件」而不是 API
 *
 * CNB 的 `api.cnb.cool` 所有接口（`/-/git/tags`、`/-/releases`、
 * `/-/commit-assets`）**匿名一律401**，token 不能硬编码进 APK。
 * 实测唯一匿名可用的路径是 web 端：
 *
 * ```
 * https://cnb.cool/<slug>/-/commit-assets/download/<ref>/<file>
 * ```
 *
 * 它接受**分支名**作 ref，302 跳到带签名的 asset.cnb.cool。
 * 仓库 slug 与 ref 写死在这里 —— App 本来就是单仓库分发的客户端，
 * 不需要做成可配置的，多一个输入框就多一个填错的可能。
 *
 * ## 为什么必须校验内容而不是只看 HTTP 状态码
 *
 *踩过的坑：CNB 的 `/-/raw/` 路径在文件不存在时返回 **HTTP 200 + 一张
 * HTML 错误页**。只判 `response.isSuccessful` 会把错误页当成版本文件，
 * 于是解析出 versionCode = 0，比本地还"旧"，用户永远看不到更新提示。
 * 所以下面用 `isJsonObject()` 做第二道校验。
 */
object AppUpdater {

    private const val SLUG = "unicom/BTWAF"
    private const val BRANCH = "main"
    private const val WEB = "https://cnb.cool"
    private const val REPO_URL = "$WEB/$SLUG"

    /** 版本文件与 APK 的公开下载地址。 */
    private fun assetUrl(file: String) =
        "$REPO_URL/-/commit-assets/download/$BRANCH/$file"

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)   // 必须：附件URL 会 302 到 asset.cnb.cool
            .build()
    }

    // ─────────────────────────────── 数据模型

    /**
     * 远端版本信息。
     *
     * 字段全部给默认值：远端 JSON 由CI 生成，字段缺失时不该抛异常崩掉
     * 整个更新流程，而是退化成"无更新可用"，让 App 照常使用。
     */
    data class RemoteVersion(
        val versionCode: Int,
        val versionName: String,
        val apkName: String,
        val size: Long,
        val sha256: String,
        val publishedAt: String,
    ) {
        /** 校验和必须是 64 位十六进制，否则下载完无法验证完整性。 */
        val shaValid: Boolean get() = sha256.length == 64 && sha256.all { it in "0123456789abcdefABCDEF" }
    }

    /** 检查结果。 */
    sealed interface CheckResult {
        data object Checking : CheckResult

        /** 已是最新。 */
        data class UpToDate(val current: String) : CheckResult

        /** 有新版本。**此时还没下载任何东西** —— 这是省流量的关键。 */
        data class Available(val info: RemoteVersion, val apkUrl: String) : CheckResult

        data class Failed(val reason: String) : CheckResult
    }

    /**
     * 当前安装版本（取自包信息）。
     *
     * 读包信息而不是直接用 `BuildConfig.VERSION_CODE` 是有意的：
     * 两者理论上同源，但系统以安装包里的 manifest 为准。
     * 万一出现「CI 传了个参数改版本号」之类的分叉，读包信息才不会
     * 跟系统判定打架 —— 系统认哪个，我们就说哪个。
     */
    val currentVersionCode: Int
        get() {
            val ctx = appContext ?: return BuildConfig.VERSION_CODE
            return try {
                val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode.toInt() else pkg.versionCode
            } catch (_: Throwable) {
                BuildConfig.VERSION_CODE
            }
        }

    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    @Volatile
    private var appContext: Context? = null

    fun attach(ctx: Context) {
        appContext = ctx.applicationContext
    }

    // ─────────────────────────────── 查版本（不下载 APK）

    /**
     * 只拉version.json（几百字节），**不碰 APK**。
     *
     * 用户要求「不要全部下载下来更新，不然浪费流量」—— 所以顺序必须是：
     * 先比对 versionCode，确认确实有新版本才提示，APK 下载由用户点击触发。
     * 自动更新 App 的流量消耗因此与实际更新频率成正比，而不是与启动次数成正比。
     */
    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        // 进入 IO 线程后先检查一次协程是否已被取消。
        // 查版本要发一次网络请求，页面关掉后如果还等着响应就白等。
        //ensureActive() 是 kotlinx.coroutines 的顶层扩展，import 后可直接调。
        currentCoroutineContext().ensureActive()
        try {
            val req = Request.Builder().url(assetUrl("version.json")).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext CheckResult.Failed("HTTP ${resp.code}")
                }
                val text = resp.body?.string().orEmpty()

                // ⚠️ 第二道校验：不能只看状态码。
                // CNB 对不存在的资源会返回 200 + HTML 错误页，
                // 直接解析会把错误页当成版本文件（versionCode=0），
                // 表现为「永远提示已是最新」，而且毫无异常日志。
                if (!text.trimStart().startsWith("{")) {
                    return@withContext CheckResult.Failed(
                        "远端未提供版本信息（可能是仓库还没跑过构建）"
                    )
                }

                val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                    ?: return@withContext CheckResult.Failed("版本文件格式异常")

                val info = RemoteVersion(
                    versionCode = obj.get("versionCode")?.asInt ?: 0,
                    versionName = obj.get("versionName")?.asString.orEmpty().ifBlank { "未知" },
                    apkName = obj.get("apkName")?.asString.orEmpty().ifBlank { "btwaf-latest.apk" },
                    size = obj.get("size")?.asLong ?: 0L,
                    sha256 = obj.get("sha256")?.asString.orEmpty().trim().lowercase(),
                    publishedAt = obj.get("publishedAt")?.asString.orEmpty(),
                )
                if (info.versionCode <= 0) {
                    return@withContext CheckResult.Failed("版本号无效")
                }

                val mine = currentVersionCode
                if (info.versionCode > mine) {
                    CheckResult.Available(info, assetUrl(info.apkName))
                } else {
                    CheckResult.UpToDate("$currentVersionName ($mine)")
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            CheckResult.Failed(e.message ?: "网络异常")
        }
    }

    // ─────────────────────────────── 下载 APK

    /** 下载结果。 */
    sealed interface DownloadResult {
        data class Success(val file: File) : DownloadResult
        data class Failed(val reason: String) : DownloadResult
    }

    /**
     * 下载 APK 到临时文件。
     *
     * ## 为什么支持断点续传
     *
     * APK 有十几 MB，手机网络下中途断链很常见。没有续传的话每次失败都要
     * 从零开始重来，用户为了更新一个包要重复消耗几倍流量。
     * 用 `Range: bytes=<已下载>-` 续传，服务器返回 206 就接着写，
     * 返回 200 说明服务器不支持续传，那就**从头覆盖**（不能追加，
     * 否则会拼出一个损坏的包）。
     *
     * ## 为什么先下载再校验，而不是边下边校验
     *
     * SHA256 必须对完整文件计算。中途校验只能发现"当前这段不对"，
     * 无法确认整体正确，而下载损坏的 APK 会导致安装失败或系统静默拒绝。
     */
    suspend fun download(
        url: String,
        onProgress: DownloadProgress = { _, _ -> },
    ): DownloadResult = withContext(Dispatchers.IO) {
        val part = File(appContext?.cacheDir ?: return@withContext DownloadResult.Failed("无缓存目录"), "btwaf-update.part")
        val expectTotal = part.length()
        try {
            val builder = Request.Builder().url(url)
            if (expectTotal > 0) builder.header("Range", "bytes=$expectTotal-")

            http.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext DownloadResult.Failed("HTTP ${resp.code}")
                }

                // 206 = 服务器接受续传；200 = 不支持，必须从头写。
                val appending = resp.code == 206 && expectTotal > 0
                val total = resp.body?.contentLength()?.let { if (it > 0) it + expectTotal else -1L } ?: -1L

                if (!appending) part.delete()

                val body = resp.body ?: return@withContext DownloadResult.Failed("响应为空")
                RandomAccessFile(part, "rw").use { raf ->
                    if (appending) raf.seek(expectTotal) else raf.setLength(0)
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read = raf.length()
                        var lastNotify = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            raf.write(buf, 0, n)
                            read += n
                            // 每 256KB 报一次：太频繁会拖慢 IO，
                            // 太稀疏进度条会一跳一跳。
                            if (read - lastNotify >= 256 * 1024) {
                                lastNotify = read
                                onProgress(read, total)
                            }
                        }
                    }
                }
                onProgress(part.length(), total)
            }
            DownloadResult.Success(part)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DownloadResult.Failed(e.message ?: "下载失败")
        }
    }

    /** 计算文件 SHA256，返回小写十六进制。 */
    fun sha256(file: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * 触发安装。
     *
     * 走 `ACTION_VIEW` + FileProvider，**必须**用 FileProvider 而不能
     * 直接给 `file://` URI：Android 7.0起系统禁止 App 跨进程传 file://，
     * 会直接抛 FileUriExposedException（targetSdk 34 下必然触发）。
     */
    fun install(context: Context, apk: File): Boolean = runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrElse { false }

    /** APK 落到 cacheDir 后，先复制到 files 目录 —— FileProvider 授权的是 files 下的路径。 */
    fun stageForInstall(context: Context, downloaded: File): File? = runCatching {
        val target = File(context.filesDir, "btwaf-update.apk")
        downloaded.copyTo(target, overwrite = true)
        target
    }.getOrNull()

    /** 清掉半截的临时下载文件。 */
    fun cleanup() {
        runCatching {
            appContext?.cacheDir?.listFiles { _, name -> name == "btwaf-update.part" }
                ?.forEach { it.delete() }
        }
    }
}
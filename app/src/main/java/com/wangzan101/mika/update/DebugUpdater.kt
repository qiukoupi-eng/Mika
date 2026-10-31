package com.wangzan101.mika.update

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.wangzan101.mika.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Debug 版在线更新:读固定地址的 latest.json,有新 versionCode 就下载、校验 sha256、拉起安装。 */
object DebugUpdater {
    const val MANIFEST_URL = "https://pub-9c89d8f043fd47e3a06bad1851e9b228.r2.dev/latest.json"
    private const val PREFS = "debug_updater"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    val enabled: Boolean get() = BuildConfig.DEBUG

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }
    private val main = Handler(Looper.getMainLooper())

    data class Info(val versionCode: Int, val versionName: String, val apkUrl: String, val sha256: String, val notes: String)

    /** App 启动时调用:一天最多查一次,没有新版不打扰。 */
    fun checkOnStart(activity: Activity) {
        if (!enabled) return
        val p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("last", 0) < DAY_MS) return
        p.edit().putLong("last", now).apply()
        check(activity, manual = false)
    }

    fun check(activity: Activity, manual: Boolean) {
        if (!enabled) return
        if (manual) toast(activity, "正在检查更新…")
        thread(name = "mika-update-check") {
            val info = runCatching { fetch() }.getOrElse {
                if (manual) toast(activity, "检查更新失败:${it.message}"); return@thread
            }
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (info.versionCode <= BuildConfig.VERSION_CODE) {
                    if (manual) toast(activity, "已是最新版本 ${BuildConfig.VERSION_NAME}")
                    return@post
                }
                AlertDialog.Builder(activity)
                    .setTitle("发现新版本 ${info.versionName}")
                    .setMessage("当前 ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) → ${info.versionName}(${info.versionCode})\n\n${info.notes}")
                    .setPositiveButton("更新") { _, _ -> download(activity, info) }
                    .setNegativeButton("稍后", null)
                    .show()
            }
        }
    }

    private fun fetch(): Info {
        val req = Request.Builder().url("$MANIFEST_URL?t=${System.currentTimeMillis()}").header("Cache-Control", "no-cache").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val j = JSONObject(r.body!!.string())
            return Info(j.getInt("versionCode"), j.getString("versionName"), j.getString("apkUrl"),
                j.getString("sha256").lowercase(), j.optString("notes"))
        }
    }

    @Suppress("DEPRECATION")
    private fun download(activity: Activity, info: Info) {
        val dlg = ProgressDialog(activity).apply {
            setTitle("下载 ${info.versionName}"); setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100; setCancelable(false); show()
        }
        thread(name = "mika-update-download") {
            val dir = File(activity.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val out = File(dir, "mika-${info.versionCode}.apk")
            val result = runCatching {
                http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val body = r.body!!
                    val total = body.contentLength()
                    val md = MessageDigest.getInstance("SHA-256")
                    var done = 0L; var last = -1
                    body.byteStream().use { input -> out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            o.write(buf, 0, n); md.update(buf, 0, n); done += n
                            if (total > 0) { val pct = (done * 100 / total).toInt(); if (pct != last) { last = pct; main.post { dlg.progress = pct } } }
                        }
                    } }
                    val sha = md.digest().joinToString("") { "%02x".format(it) }
                    if (sha != info.sha256) { out.delete(); error("sha256 校验失败") }
                }
            }
            main.post {
                runCatching { dlg.dismiss() }
                result.onSuccess { install(activity, out) }.onFailure { toast(activity, "下载失败:${it.message}") }
            }
        }
    }

    private fun install(activity: Activity, apk: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            toast(activity, "请允许 Mika Debug「安装未知应用」,然后再点一次检查更新")
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun toast(ctx: Context, msg: String) = main.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
}

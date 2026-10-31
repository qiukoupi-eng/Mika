package com.wangzan101.mika.about

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 一个发布的版本:版本号(不带 v)、标题、更新说明(Markdown)、发布日期、发布页、APK 直链。 */
data class ReleaseInfo(
    val version: String,
    val name: String,
    /** 更新说明。 */
    val notes: String,
    /** 发布日期 yyyy-MM-dd;来源没写时为空。 */
    val date: String?,
    val pageUrl: String,
    val apkUrl: String?,
    /** APK 附件的字节数;接口没给时为空。 */
    val apkSize: Long? = null,
    /** APK 附件的摘要,形如 `sha256:<hex>`;较早的附件没有,这时只靠包名、版本号和签名把关。 */
    val apkDigest: String? = null,
) {
    /** 「从 GitHub 下载」打开的地址:有 APK 直链就下 APK,没有就打开发布页。 */
    val downloadUrl: String get() = apkUrl ?: pageUrl
}

/**
 * 读 GitHub 发布页接口(releases/latest)的版本信息:tag_name、name、body、published_at、html_url、
 * assets 里第一个 .apk 的 browser_download_url。
 */
object Releases {
    private val json = Json { ignoreUnknownKeys = true }

    fun fromGitHub(text: String): ReleaseInfo {
        val o = json.parseToJsonElement(text).jsonObject
        val tag = o.str("tag_name") ?: error("no tag_name")
        val body = o.str("body").orEmpty()
        val apk = o["assets"]?.let { a ->
            runCatching { a.jsonArray }.getOrNull()?.map { it.jsonObject }
                ?.firstOrNull { it.str("name")?.endsWith(".apk", ignoreCase = true) == true }
        }
        val version = AppVersion.normalize(tag)
        return ReleaseInfo(
            version = version,
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: version,
            notes = body.trim(),
            date = o.str("published_at")?.take(10)?.takeIf { it.length == 10 },
            pageUrl = o.str("html_url") ?: "${MikaLinks.RELEASES}/tag/$tag",
            apkUrl = apk?.str("browser_download_url"),
            apkSize = apk?.strOrNumber("size")?.toLongOrNull(),
            apkDigest = apk?.str("digest"),
        )
    }

    /** 存盘用:记下最近一次查到的版本,下次打开 App 时红点还在。 */
    fun toJson(r: ReleaseInfo): JsonObject = buildJsonObject {
        put("version", r.version)
        put("name", r.name)
        put("notes", r.notes)
        r.date?.let { put("date", it) }
        put("pageUrl", r.pageUrl)
        r.apkUrl?.let { put("apkUrl", it) }
        r.apkSize?.let { put("apkSize", it) }
        r.apkDigest?.let { put("apkDigest", it) }
    }

    fun fromJson(o: JsonObject): ReleaseInfo? = runCatching {
        ReleaseInfo(
            version = o.str("version")!!, name = o.str("name") ?: o.str("version")!!, notes = o.str("notes").orEmpty(),
            date = o.str("date"), pageUrl = o.str("pageUrl") ?: MikaLinks.RELEASES, apkUrl = o.str("apkUrl"),
            apkSize = o.strOrNumber("apkSize")?.toLongOrNull(), apkDigest = o.str("apkDigest"),
        )
    }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.strOrNumber(key: String): String? = (this[key] as? JsonPrimitive)?.content
}

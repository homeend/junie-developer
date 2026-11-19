package dev.juniedeveloper.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Operating system and CPU as they appear in Junie release asset names. */
data class HostPlatform(val os: String, val arch: String) {
    val isWindows: Boolean get() = os == "windows"

    companion object {
        fun of(osName: String, osArch: String): HostPlatform {
            val os = osName.lowercase().let {
                when {
                    it.startsWith("windows") -> "windows"
                    it.startsWith("mac") || it.contains("darwin") -> "macos"
                    else -> "linux"
                }
            }
            val arch = when (osArch.lowercase()) {
                "aarch64", "arm64" -> "aarch64"
                else -> "amd64"
            }
            return HostPlatform(os, arch)
        }

        fun current(): HostPlatform = of(System.getProperty("os.name"), System.getProperty("os.arch"))
    }
}

data class ReleaseAsset(val name: String, val url: String, val size: Long)

data class JunieRelease(
    val tag: String,
    val title: String,
    val publishedAt: String,
    val assets: List<ReleaseAsset>,
) {
    /** "nightly", "eap", ... taken from the asset names; null when the release has no zips. */
    val channel: String? get() = assets.firstNotNullOfOrNull { ASSET_NAME.matchEntire(it.name)?.groupValues?.get(1) }

    /** The zip built for [platform], or null when this release has none. */
    fun assetFor(platform: HostPlatform): ReleaseAsset? = assets.firstOrNull {
        val m = ASSET_NAME.matchEntire(it.name) ?: return@firstOrNull false
        m.groupValues[3] == platform.os && m.groupValues[4] == platform.arch
    }

    fun matches(query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || tag.contains(q, ignoreCase = true) || title.contains(q, ignoreCase = true)
    }

    companion object {
        // junie-nightly-4175.1-windows-amd64.zip, junie-eap-4171.3-linux-aarch64.zip
        private val ASSET_NAME = Regex("""junie-([a-z][a-z-]*?)-([0-9][0-9.]*)-(windows|linux|macos)-(amd64|aarch64)\.zip""")

        /** Parses the body of `GET /repos/{owner}/{repo}/releases`. */
        fun parseList(json: String): List<JunieRelease> =
            JsonParser.parseString(json).asJsonArray.mapNotNull { parse(it) }

        /** Parses the body of `GET /repos/{owner}/{repo}/releases/tags/{tag}`. */
        fun parseOne(json: String): JunieRelease? = parse(JsonParser.parseString(json))

        private fun parse(element: JsonElement): JunieRelease? {
            val o = element as? JsonObject ?: return null
            val tag = o.string("tag_name") ?: return null
            val assets = o.getAsJsonArray("assets")?.mapNotNull { a ->
                val ao = a as? JsonObject ?: return@mapNotNull null
                ReleaseAsset(
                    name = ao.string("name") ?: return@mapNotNull null,
                    url = ao.string("browser_download_url") ?: return@mapNotNull null,
                    size = ao.get("size")?.takeUnless { it.isJsonNull }?.asLong ?: 0L,
                )
            }.orEmpty()
            return JunieRelease(tag, o.string("name") ?: tag, o.string("published_at").orEmpty(), assets)
        }

        private fun JsonObject.string(key: String): String? =
            get(key)?.takeUnless { it.isJsonNull }?.asString
    }
}

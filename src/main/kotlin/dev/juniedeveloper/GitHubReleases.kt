package dev.juniedeveloper

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.HttpRequests
import dev.juniedeveloper.core.JunieRelease
import java.net.HttpURLConnection
import java.nio.file.Path

/** Talks to the public GitHub releases API of the Junie repository. */
object GitHubReleases {
    const val REPOSITORY = "JetBrains/junie"
    const val PAGE_SIZE = 100
    private const val API = "https://api.github.com/repos/$REPOSITORY/releases"

    private val tokenAttributes = CredentialAttributes(generateServiceName("JunieDeveloper", "github-token"))

    /** Optional: without a token GitHub allows 60 API requests an hour per address. */
    var token: String?
        get() = PasswordSafe.instance.getPassword(tokenAttributes)?.takeIf { it.isNotBlank() }
        set(value) = PasswordSafe.instance.setPassword(tokenAttributes, value?.trim()?.takeIf { it.isNotEmpty() })

    /** One page of releases, newest first. [page] starts at 1. */
    fun page(page: Int): List<JunieRelease> =
        JunieRelease.parseList(api("$API?per_page=$PAGE_SIZE&page=$page").readString())

    /** The release with exactly this tag, or null when there is none. */
    fun byTag(tag: String): JunieRelease? = try {
        JunieRelease.parseOne(api("$API/tags/${java.net.URLEncoder.encode(tag, Charsets.UTF_8)}").readString())
    } catch (e: HttpRequests.HttpStatusException) {
        if (e.statusCode == HttpURLConnection.HTTP_NOT_FOUND) null else throw e
    }

    fun download(url: String, target: Path, indicator: ProgressIndicator) {
        HttpRequests.request(url).productNameAsUserAgent().saveToFile(target, indicator)
    }

    private fun api(url: String) = HttpRequests.request(url)
        .productNameAsUserAgent()
        .accept("application/vnd.github+json")
        .tuner { connection ->
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        }
}

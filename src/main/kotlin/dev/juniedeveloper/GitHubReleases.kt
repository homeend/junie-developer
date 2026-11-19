package dev.juniedeveloper

import com.google.gson.JsonParser
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.HttpRequests
import com.intellij.openapi.progress.ProcessCanceledException
import dev.juniedeveloper.core.GhCli
import dev.juniedeveloper.core.JunieRelease
import dev.juniedeveloper.core.ReleaseAsset
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.file.Path

/** Talks to the public GitHub releases API of the Junie repository. */
object GitHubReleases {
    const val REPOSITORY = "JetBrains/junie"
    const val PAGE_SIZE = 100
    private const val API = "https://api.github.com/repos/$REPOSITORY/releases"
    private val LOG = logger<GitHubReleases>()
    private val gh = GhCli()

    private val tokenAttributes = CredentialAttributes(generateServiceName("JunieDeveloper", "github-token"))

    /** The token entered in the tool window, kept in the IDE password safe. */
    var token: String?
        get() = PasswordSafe.instance.getPassword(tokenAttributes)?.takeIf { it.isNotBlank() }
        set(value) = PasswordSafe.instance.setPassword(tokenAttributes, value?.trim()?.takeIf { it.isNotEmpty() })

    /** Where the token in use comes from, for messages; null when requests are anonymous. */
    @Volatile var tokenSource: String? = null
        private set

    @Volatile private var ghToken: String? = null
    @Volatile private var ghTokenAsked = false

    /**
     * The token to send, in order: the one entered here, `GH_TOKEN` / `GITHUB_TOKEN`,
     * then the login of the GitHub CLI (`gh auth token`) when `gh` is installed.
     * Anonymous access works too, but GitHub limits it to 60 requests an hour per
     * network address, which a shared office or VPN address exhausts quickly.
     */
    private fun effectiveToken(): String? {
        token?.let { tokenSource = "the token entered in JunieDeveloper"; return it }
        for (name in listOf("GH_TOKEN", "GITHUB_TOKEN")) {
            System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { tokenSource = "\$$name"; return it }
        }
        if (!ghTokenAsked) {
            ghToken = if (gh.isInstalled) gh.authToken() else null
            ghTokenAsked = true
        }
        tokenSource = ghToken?.let { "the GitHub CLI login" }
        return ghToken
    }

    /** One page of releases, newest first. [page] starts at 1. */
    fun page(page: Int): List<JunieRelease> =
        JunieRelease.parseList(apiWithFallback("?per_page=$PAGE_SIZE&page=$page")!!)

    /** The release with exactly this tag, or null when there is none. */
    fun byTag(tag: String): JunieRelease? =
        apiWithFallback("/tags/${java.net.URLEncoder.encode(tag, Charsets.UTF_8)}", notFoundIsNull = true)
            ?.let { JunieRelease.parseOne(it) }

    /**
     * Downloads [asset] of the release [tag]. When the direct download fails, the
     * GitHub CLI fetches it instead (no progress percentage on that route).
     */
    fun download(tag: String, asset: ReleaseAsset, target: Path, indicator: ProgressIndicator) {
        try {
            HttpRequests.request(asset.url).productNameAsUserAgent().saveToFile(target, indicator)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: IOException) {
            if (!gh.isInstalled) throw e
            LOG.info("Direct download failed (${e.message}), using the GitHub CLI")
            indicator.text = "Downloading ${asset.name} with the GitHub CLI"
            indicator.isIndeterminate = true
            try {
                gh.downloadAsset(REPOSITORY, tag, asset.name, target) { indicator.isCanceled }
            } catch (viaGh: IOException) {
                indicator.checkCanceled()
                throw IOException("${e.message} The GitHub CLI could not download it either: ${viaGh.message}", e)
            } finally {
                indicator.isIndeterminate = false
            }
        }
    }

    /**
     * Asks GitHub directly and, when that is refused or fails, asks through the
     * GitHub CLI, which uses its own login and connection settings.
     */
    private fun apiWithFallback(pathAndQuery: String, notFoundIsNull: Boolean = false): String? {
        try {
            return api(API + pathAndQuery, notFoundIsNull)
        } catch (e: IOException) {
            if (!gh.isInstalled) throw e
            LOG.info("Direct GitHub request failed (${e.message}), using the GitHub CLI")
            try {
                return gh.api("repos/$REPOSITORY/releases$pathAndQuery")
            } catch (viaGh: IOException) {
                if (notFoundIsNull && viaGh.message.orEmpty().contains("404")) return null
                throw IOException("${e.message} The GitHub CLI failed too: ${viaGh.message}", e)
            }
        }
    }

    private fun api(url: String, notFoundIsNull: Boolean = false): String? {
        val token = effectiveToken()
        return HttpRequests.request(url)
            .productNameAsUserAgent()
            .accept("application/vnd.github+json")
            .throwStatusCodeException(false)
            .tuner { connection -> token?.let { connection.setRequestProperty("Authorization", "Bearer $it") } }
            .connect { request ->
                val connection = request.connection as HttpURLConnection
                val status = connection.responseCode
                when {
                    status < 400 -> request.readString()
                    status == HttpURLConnection.HTTP_NOT_FOUND && notFoundIsNull -> null
                    else -> throw IOException(describeFailure(
                        status,
                        connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty(),
                        connection.getHeaderField("x-ratelimit-remaining"),
                        token != null))
                }
            }
    }

    /** Turns a GitHub error response into something a person can act on. */
    internal fun describeFailure(status: Int, body: String, rateLimitRemaining: String?, hadToken: Boolean): String {
        val message = runCatching { JsonParser.parseString(body).asJsonObject.get("message").asString }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(200).trim()
        val rateLimited = rateLimitRemaining == "0" || message.contains("rate limit", ignoreCase = true)
        val advice = when {
            rateLimited && !hadToken -> " GitHub allows only 60 anonymous requests an hour per network address. " +
                "Log in with the GitHub CLI (gh auth login) or enter a token with the GitHub Token button."
            rateLimited -> " The request limit of ${tokenSource ?: "this token"} is used up; wait until it resets."
            status == HttpURLConnection.HTTP_UNAUTHORIZED -> " The token from ${tokenSource ?: "the settings"} was rejected."
            status == HttpURLConnection.HTTP_FORBIDDEN && hadToken ->
                " The token from ${tokenSource ?: "the settings"} may not be allowed to read $REPOSITORY " +
                    "(for example an organisation that requires SSO authorisation)."
            else -> ""
        }
        return "GitHub answered $status" + (if (message.isNotEmpty()) ": $message" else "") + "." + advice
    }
}

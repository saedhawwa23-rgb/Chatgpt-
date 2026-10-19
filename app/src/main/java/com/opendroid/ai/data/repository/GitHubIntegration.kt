package com.opendroid.ai.data.repository

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.opendroid.ai.BuildConfig
import com.opendroid.ai.core.security.GitHubTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

sealed interface GitHubDevicePoll {
    data class Pending(val seconds: Int) : GitHubDevicePoll
    data class Authorized(val login: String) : GitHubDevicePoll
}
data class GitHubDeviceCode(val userCode: String, val verificationUri: String, val deviceCode: String, val intervalSeconds: Int)
data class GitHubAccount(val login: String, val name: String?, val avatarUrl: String)
data class GitHubRepoSummary(val id: Long, val fullName: String, val private: Boolean, val defaultBranch: String, val htmlUrl: String)
data class GitHubFile(val path: String, val type: String, val sha: String?, val htmlUrl: String?, val content: String? = null)
data class GitHubIssue(val number: Int, val title: String, val body: String, val state: String, val htmlUrl: String, val user: String)

@Singleton
class GitHubIntegration @Inject constructor(
    private val client: OkHttpClient,
    private val tokenStore: GitHubTokenStore
) {
    private val api = "https://api.github.com".toHttpUrl()

    fun isConfigured(): Boolean = BuildConfig.GITHUB_CLIENT_ID.isNotBlank()
    fun isAuthenticated(): Boolean = !tokenStore.read().isNullOrBlank()
    fun signOut(): Boolean = tokenStore.clear()

    suspend fun requestDeviceCode(): GitHubDeviceCode = withContext(Dispatchers.IO) {
        require(isConfigured()) { "GitHub OAuth client ID is not configured in this build." }
        val body = FormBody.Builder().add("client_id", BuildConfig.GITHUB_CLIENT_ID).add("scope", "repo read:user").build()
        val request = Request.Builder().url("https://github.com/login/device/code").post(body)
            .header("Accept", "application/json").header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            val json = response.body.string().asJsonObject()
            if (!response.isSuccessful) throw failure("device authorization", response.code, json)
            GitHubDeviceCode(
                userCode = json.string("user_code"),
                verificationUri = json.string("verification_uri"),
                deviceCode = json.string("device_code"),
                intervalSeconds = json.int("interval", 5)
            )
        }
    }

    suspend fun pollDeviceCode(code: GitHubDeviceCode): GitHubDevicePoll = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().add("client_id", BuildConfig.GITHUB_CLIENT_ID).add("device_code", code.deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build()
        val request = Request.Builder().url("https://github.com/login/oauth/access_token").post(body)
            .header("Accept", "application/json").header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            val json = response.body.string().asJsonObject()
            val error = json.stringOrNull("error")
            when {
                error == "authorization_pending" -> GitHubDevicePoll.Pending(code.intervalSeconds)
                error == "slow_down" -> GitHubDevicePoll.Pending(code.intervalSeconds + 5)
                error != null -> throw DiscoveryException("GitHub authorization failed: ${json.stringOrNull("error_description") ?: error}")
                !response.isSuccessful -> throw failure("token exchange", response.code, json)
                else -> {
                    val token = json.string("access_token")
                    require(tokenStore.write(token)) { "GitHub token could not be stored securely." }
                    GitHubDevicePoll.Authorized(currentUser().login)
                }
            }
        }
    }

    suspend fun currentUser(): GitHubAccount = getObject("user").let {
        GitHubAccount(it.string("login"), it.stringOrNull("name"), it.string("avatar_url"))
    }

    suspend fun repositories(): List<GitHubRepoSummary> = withContext(Dispatchers.IO) {
        val request = authenticatedRequest("user/repos?sort=updated&per_page=50").get().build()
        client.newCall(request).execute().use { response ->
            val json = response.body.string().asJsonArray()
            ensureSuccess(response.code, "repository listing", json)
            json.map { it.asJsonObject }.map { repo ->
                GitHubRepoSummary(repo.long("id"), repo.string("full_name"), repo.bool("private"), repo.string("default_branch"), repo.string("html_url"))
            }
        }
    }

    suspend fun files(owner: String, repo: String, path: String = ""): List<GitHubFile> = withContext(Dispatchers.IO) {
        val url = api.newBuilder().addPathSegment("repos").addPathSegment(owner).addPathSegment(repo).addPathSegment("contents")
            .apply { if (path.isNotBlank()) path.trim('/').split('/').forEach { addPathSegment(it) } }.build()
        client.newCall(authenticatedRequest(url).get().build()).execute().use { response ->
            val raw = response.body.string().asJson()
            ensureSuccess(response.code, "file listing", raw)
            val entries = if (raw.isJsonArray) raw.asJsonArray else JsonArray().apply { add(raw) }
            entries.map { item ->
                val obj = item.asJsonObject
                GitHubFile(obj.string("path"), obj.string("type"), obj.stringOrNull("sha"), obj.stringOrNull("html_url"))
            }
        }
    }

    suspend fun readFile(owner: String, repo: String, path: String, ref: String? = null): GitHubFile = withContext(Dispatchers.IO) {
        val builder = api.newBuilder().addPathSegment("repos").addPathSegment(owner).addPathSegment(repo).addPathSegment("contents")
        path.trim('/').split('/').forEach { builder.addPathSegment(it) }
        ref?.let { builder.addQueryParameter("ref", it) }
        client.newCall(authenticatedRequest(builder.build()).get().build()).execute().use { response ->
            val obj = response.body.string().asJsonObject()
            ensureSuccess(response.code, "file inspection", obj)
            val encoded = obj.stringOrNull("content").orEmpty().filterNot(Char::isWhitespace)
            val content = encoded.takeIf { it.isNotBlank() }?.let { String(Base64.getDecoder().decode(it), Charsets.UTF_8).take(MAX_FILE_CHARS) }
            GitHubFile(obj.string("path"), obj.string("type"), obj.stringOrNull("sha"), obj.stringOrNull("html_url"), content)
        }
    }

    suspend fun issues(owner: String, repo: String): List<GitHubIssue> = withContext(Dispatchers.IO) {
        val url = api.newBuilder().addPathSegment("repos").addPathSegment(owner).addPathSegment(repo).addPathSegment("issues")
            .addQueryParameter("state", "open").addQueryParameter("per_page", "50").build()
        client.newCall(authenticatedRequest(url).get().build()).execute().use { response ->
            val json = response.body.string().asJsonArray()
            ensureSuccess(response.code, "issue listing", json)
            json.map { it.asJsonObject }.filterNot { it.has("pull_request") }.map { issue ->
                GitHubIssue(issue.int("number"), issue.string("title"), issue.stringOrNull("body").orEmpty(), issue.string("state"), issue.string("html_url"), issue.obj("user").string("login"))
            }
        }
    }

    suspend fun createIssue(owner: String, repo: String, title: String, body: String): GitHubIssue = withContext(Dispatchers.IO) {
        require(title.trim().isNotEmpty()) { "Issue title cannot be empty." }
        val payload = JsonObject().apply { addProperty("title", title.trim()); addProperty("body", body) }
        val request = authenticatedRequest("repos/$owner/$repo/issues").post(payload.toString().toJsonRequestBody()).build()
        client.newCall(request).execute().use { response ->
            val issue = response.body.string().asJsonObject()
            ensureSuccess(response.code, "issue creation", issue)
            GitHubIssue(issue.int("number"), issue.string("title"), issue.stringOrNull("body").orEmpty(), issue.string("state"), issue.string("html_url"), issue.obj("user").string("login"))
        }
    }

    private suspend fun getObject(path: String): JsonObject = withContext(Dispatchers.IO) {
        val request = client.newCall(authenticatedRequest(path).get().build()).execute()
        request.use { response ->
            val obj = response.body.string().asJsonObject(); ensureSuccess(response.code, path, obj); obj
        }
    }
    private fun authenticatedRequest(path: String): Request.Builder = authenticatedRequest(api.newBuilder().addPathSegments(path).build())
    private fun authenticatedRequest(url: HttpUrl): Request.Builder = Request.Builder().url(url).header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", USER_AGENT).apply {
        tokenStore.read()?.let { header("Authorization", "Bearer $it") } ?: throw DiscoveryException("Authenticate with GitHub first.")
    }
    private fun ensureSuccess(code: Int, action: String, json: Any) { if (code !in 200..299) throw failure(action, code, json) }
    private fun failure(action: String, code: Int, json: Any): DiscoveryException = DiscoveryException("GitHub $action failed (HTTP $code): ${json.errorMessage()}")
    private fun Any.errorMessage(): String = when (this) { is JsonObject -> stringOrNull("message") ?: "request rejected"; else -> "request rejected" }
    private companion object { const val USER_AGENT = "USMAN-AND-UMAR-Android-App"; const val MAX_FILE_CHARS = 100_000 }
}

private fun String.asJson() = Gson().fromJson(this, com.google.gson.JsonElement::class.java)
private fun String.asJsonArray() = Gson().fromJson(this, JsonArray::class.java)
private fun String.asJsonObject() = Gson().fromJson(this, JsonObject::class.java)
private fun JsonObject.string(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString ?: throw IOException("GitHub response omitted $key")
private fun JsonObject.stringOrNull(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString
private fun JsonObject.int(key: String, default: Int = 0) = get(key)?.takeUnless { it.isJsonNull }?.asInt ?: default
private fun JsonObject.long(key: String) = get(key)?.asLong ?: 0L
private fun JsonObject.bool(key: String) = get(key)?.asBoolean ?: false
private fun JsonObject.obj(key: String) = getAsJsonObject(key)
private fun String.toJsonRequestBody() = toRequestBody("application/json".toMediaType())

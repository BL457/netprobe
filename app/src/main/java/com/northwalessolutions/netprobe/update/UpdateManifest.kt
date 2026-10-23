package com.northwalessolutions.netprobe.update

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URISyntaxException

/** latest.json as written (spec 1); every field nullable so a missing one is ours to judge, not the decoder's. */
@Serializable
private data class RawManifest(
    val versionCode: Int? = null, val versionName: String? = null, val apkUrl: String? = null, val sha256: String? = null,
    val sizeBytes: Long? = null, val notes: String? = null, val minSdk: Int? = null,
)

data class UpdateManifest(
    val versionCode: Int, val versionName: String, val apkUrl: String, val sha256: String,
    val sizeBytes: Long? = null, val notes: String = "", val minSdk: Int? = null,
)

sealed interface ManifestParse {
    data class Valid(val manifest: UpdateManifest) : ManifestParse
    data object Invalid : ManifestParse
}

object UpdateManifestParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val HEX = Regex("^[0-9a-fA-F]{${UpdateConfig.SHA256_HEX_CHARS}}$")

    /** Any missing required field, non-https URL or malformed hash makes the whole manifest invalid. */
    fun parse(bytes: ByteArray): ManifestParse {
        val raw = try {
            json.decodeFromString<RawManifest>(String(bytes, Charsets.UTF_8))
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: SerializationException) {
            return ManifestParse.Invalid
        } catch (_: IllegalArgumentException) {
            return ManifestParse.Invalid
        }
        val code = raw.versionCode?.takeIf { it > 0 } ?: return ManifestParse.Invalid
        val name = raw.versionName?.takeIf { it.isNotBlank() } ?: return ManifestParse.Invalid
        val url = raw.apkUrl?.takeIf { isHttps(it) } ?: return ManifestParse.Invalid
        val hash = raw.sha256?.takeIf { HEX.matches(it) } ?: return ManifestParse.Invalid
        if (raw.sizeBytes != null && raw.sizeBytes <= 0) return ManifestParse.Invalid
        return ManifestParse.Valid(UpdateManifest(code, name, url, hash.lowercase(), raw.sizeBytes,
            (raw.notes ?: "").take(UpdateConfig.NOTES_MAX_CHARS), raw.minSdk))
    }

    fun isHttps(url: String): Boolean {
        val uri = try { URI(url) } catch (_: URISyntaxException) { return false }
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
    }
}

package com.northwalessolutions.netprobe.update

import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest

/** Why a downloaded APK may not be installed. The package-manager half of the check lives behind [ApkVerifier]. */
enum class ApkVerdict { OK, UNREADABLE, WRONG_PACKAGE, NOT_NEWER, WRONG_SIGNER }

interface ApkVerifier {
    /** [file] must be our package, newer than [installedVersionCode], and signed with the installed app's certificate. */
    fun verify(file: File, installedVersionCode: Long): ApkVerdict
}

enum class InstallFailure { DOWNLOAD, TOO_LARGE, SIZE, HASH, REJECTED, SAVE }

sealed interface PrepResult {
    data class Ready(val file: File) : PrepResult
    data class Failed(val reason: InstallFailure) : PrepResult
}

/**
 * Download, size check, SHA-256 and signing-certificate check (spec 4). The file only reaches the system installer if every step
 * passed; any failure, and any cancel, leaves nothing behind in cacheDir/updates.
 */
class UpdateInstaller(private val cacheDir: File, private val fetcher: UpdateFetcher, private val verifier: ApkVerifier,
                      private val installedVersionCode: Int, private val log: (Throwable) -> Unit = {},
                      private val prefs: UpdatePrefs? = null) {
    private val dir get() = File(cacheDir, UpdateConfig.UPDATES_DIR)

    fun apkFile(versionCode: Int) = File(dir, "${UpdateConfig.FILE_PREFIX}$versionCode${UpdateConfig.APK_SUFFIX}")
    private fun partFile(versionCode: Int) = File(dir, "${UpdateConfig.FILE_PREFIX}$versionCode${UpdateConfig.PART_SUFFIX}")

    /** [onVerifying] fires once the bytes are all in and the hash and signature checks begin. */
    suspend fun download(manifest: UpdateManifest, onProgress: (Long, Long?) -> Unit, onVerifying: () -> Unit = {}): PrepResult {
        if (manifest.sizeBytes != null && manifest.sizeBytes > UpdateConfig.APK_MAX_BYTES) return PrepResult.Failed(InstallFailure.TOO_LARGE)
        val part = partFile(manifest.versionCode)
        val apk = apkFile(manifest.versionCode)
        var keep = false
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return PrepResult.Failed(InstallFailure.SAVE)
            apk.delete()
            val written = FileOutputStream(part).use { out ->
                fetcher.fetch(URL(manifest.apkUrl), UpdateConfig.APK_MAX_BYTES, out, onProgress)
            }
            when (written) {
                is UpdateFetch.Failed -> return PrepResult.Failed(if (written.error == FetchError.TOO_LARGE) InstallFailure.TOO_LARGE else InstallFailure.DOWNLOAD)
                is UpdateFetch.Ok -> Unit
            }
            onVerifying()
            if (manifest.sizeBytes != null && part.length() != manifest.sizeBytes) return PrepResult.Failed(InstallFailure.SIZE)
            if (!sha256(part).equals(manifest.sha256, ignoreCase = true)) return PrepResult.Failed(InstallFailure.HASH)
            if (!part.renameTo(apk)) return PrepResult.Failed(InstallFailure.SAVE)
            if (verifier.verify(apk, installedVersionCode.toLong()) != ApkVerdict.OK) return PrepResult.Failed(InstallFailure.REJECTED)
            keep = true
            prefs?.let { it.pendingVersionCode = manifest.versionCode; it.pendingNotes = manifest.notes }   // for the What's new card after the update
            return PrepResult.Ready(apk)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            log(e)
            return PrepResult.Failed(InstallFailure.SAVE)
        } finally {
            if (!keep) { part.delete(); apk.delete() }   // cancel, failure or rejection: nothing stays
        }
    }

    /** Startup sweep (spec 4): every .part file and every APK at or below the installed version. */
    fun housekeeping() {
        val files = dir.listFiles() ?: return
        for (f in files) {
            val name = f.name
            val stale = name.endsWith(UpdateConfig.PART_SUFFIX) || versionOf(name)?.let { it <= installedVersionCode } ?: true
            if (stale) f.delete()
        }
    }

    private fun versionOf(name: String): Int? {
        if (!name.startsWith(UpdateConfig.FILE_PREFIX) || !name.endsWith(UpdateConfig.APK_SUFFIX)) return null
        return name.removePrefix(UpdateConfig.FILE_PREFIX).removeSuffix(UpdateConfig.APK_SUFFIX).toIntOrNull()
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(HASH_BUFFER_BYTES)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object { const val HASH_BUFFER_BYTES = 16 * 1024 }
}

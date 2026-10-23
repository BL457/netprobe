package com.northwalessolutions.netprobe.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/** The PackageManager half of the APK check (spec 4); Android-only, so the JVM tests use a fake [ApkVerifier]. */
class AndroidApkVerifier(private val context: Context) : ApkVerifier {
    override fun verify(file: File, installedVersionCode: Long): ApkVerdict {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, FLAGS) ?: return ApkVerdict.UNREADABLE
        if (archive.packageName != context.packageName) return ApkVerdict.WRONG_PACKAGE
        if (versionCodeOf(archive) <= installedVersionCode) return ApkVerdict.NOT_NEWER
        val installed = try { pm.getPackageInfo(context.packageName, FLAGS) } catch (_: PackageManager.NameNotFoundException) { return ApkVerdict.UNREADABLE }
        val wanted = digests(installed)
        val offered = digests(archive)
        return if (wanted.isNotEmpty() && wanted == offered) ApkVerdict.OK else ApkVerdict.WRONG_SIGNER
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun digests(info: PackageInfo): Set<String> {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private companion object {
        @Suppress("DEPRECATION")
        val FLAGS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    }
}

/** Hands a verified APK to the system installer through a FileProvider content URI (spec 4). Android asks the user again. */
object UpdateInstallIntent {
    fun authority(context: Context) = context.packageName + UpdateConfig.FILE_PROVIDER_SUFFIX

    fun build(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, authority(context), apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, UpdateConfig.APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** False when no installer took it or the file cannot be shared. */
    fun launch(context: Context, apk: File): Boolean {
        return try {
            context.startActivity(build(context, apk))
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + context.packageName))
}

package com.hpu.mymoviestore.presentation.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 安装工具。
 *
 * - Android 8.0+ 需要用户授予「安装未知应用」权限（REQUEST_INSTALL_PACKAGES），
 *   未授权时先跳转系统设置页
 * - 通过 FileProvider 共享 cacheDir 下的 APK 文件给系统安装器
 */
object ApkInstaller {

    /**
     * 是否可以直接发起安装（已授予安装未知应用权限）。
     * Android 8.0 以下始终返回 true（未知来源为全局开关，Manifest 声明即可）。
     */
    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /** 跳转到「安装未知应用」授权设置页（Android 8.0+） */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * 发起 APK 安装。
     *
     * @param apkFile 下载完成的 APK 文件（须位于 cacheDir 下）
     */
    fun install(context: Context, apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

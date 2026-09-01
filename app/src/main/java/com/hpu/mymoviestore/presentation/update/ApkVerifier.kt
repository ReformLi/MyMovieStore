package com.hpu.mymoviestore.presentation.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * APK 更新包校验器（安装前双重校验）。
 *
 * 核心校验：签名证书比对（方案③）
 * - 下载 APK 的签名者证书 SHA-256 必须与硬编码的 [EXPECTED_SIGNING_CERT_SHA256] 一致
 * - 锚定在本地代码中，远程配置被篡改也无法绕过——攻击者没有签名私钥就造不出同证书的 APK
 *
 * 辅助校验：文件完整性
 * - 远程配置了 update_sha256 时，下载文件全量 SHA-256 必须一致（防传输损坏/被替换）
 * - 远程未配置时跳过（旧配置兼容）
 *
 * 签名常量获取方式（填入后校验才真正生效，为空时跳过核心校验）：
 * - 从已构建的 APK：keytool -printcert -jarfile app-release.apk
 * - 从签名密钥库：keytool -list -v -keystore MovieStore_key.jks
 * - 或安装 debug 包后查看 logcat（TAG=ApkVerifier）输出的「本地签名证书」
 */
object ApkVerifier {

    private const val TAG = "ApkVerifier"

    /**
     * 预期的签名证书 SHA-256（release 分发包的签名者证书）。
     *
     * ⚠️ 注意：换签名（如 keystore 丢失后重建）会导致校验拒绝自己的新包，
     * 届时必须同步更新此常量并发版。
     */
    private const val EXPECTED_SIGNING_CERT_SHA256 = "A8367CA48F4FAF696A7707FB29F1A7FA497A716CB32853F6C173EE89744C4855"

    /**
     * 校验结果：成功返回 null，失败返回用户可读的错误原因。
     *
     * @param context 上下文
     * @param apk 待安装的 APK 文件
     * @param remoteSha256 远程配置的 update_sha256（null = 远程未配置，跳过辅助校验）
     */
    fun verify(context: Context, apk: File, remoteSha256: String?): String? {
        // ---- 核心校验：签名证书 ----
        if (EXPECTED_SIGNING_CERT_SHA256.isNotEmpty()) {
            val localCert = signingCertSha256(context)
            val apkCert = apkSigningCertSha256(context, apk)
            if (apkCert == null) {
                Log.w(TAG, "无法解析 APK 签名（文件损坏或未签名）: ${apk.name}")
                return "安装包无效或已损坏"
            }
            Log.d(TAG, "签名证书比对: 本地=$localCert, 安装包=$apkCert")
            if (!apkCert.equals(EXPECTED_SIGNING_CERT_SHA256, ignoreCase = true)) {
                Log.e(TAG, "签名证书不匹配，拒绝安装!")
                return "安装包签名校验失败"
            }
        } else {
            // 常量未配置：跳过核心校验，同时打日志方便获取当前证书值
            Log.w(TAG, "EXPECTED_SIGNING_CERT_SHA256 未配置，跳过签名校验。本地签名证书=${signingCertSha256(context)}")
        }

        // ---- 辅助校验：文件 SHA-256 ----
        if (!remoteSha256.isNullOrBlank()) {
            val actual = fileSha256(apk)
            if (!actual.equals(remoteSha256.trim(), ignoreCase = true)) {
                Log.e(TAG, "文件 SHA-256 不匹配: 远程=${remoteSha256}, 实际=$actual")
                return "安装包完整性校验失败"
            }
            Log.d(TAG, "文件 SHA-256 校验通过: $actual")
        } else {
            Log.d(TAG, "远程未配置 update_sha256，跳过文件校验")
        }

        return null
    }

    /** 当前已安装 App 的签名证书 SHA-256（未安装/异常时返回空串） */
    fun signingCertSha256(context: Context): String {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()
            }
            signature?.let { sha256Hex(it.toByteArray()) } ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "获取本地签名失败: ${e.message}")
            ""
        }
    }

    /** APK 文件的签名者证书 SHA-256（不安装直接解析；解析失败返回 null） */
    private fun apkSigningCertSha256(context: Context, apk: File): String? {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            } ?: return null // 文件损坏/非 APK/未签名

            val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()
            } ?: return null
            sha256Hex(signature.toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "解析 APK 签名失败: ${e.message}")
            null
        }
    }

    /** 文件全量 SHA-256（流式读取，适合几十 MB 的 APK） */
    fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return sha256Hex(digest.digest())
    }

    private fun sha256Hex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format(Locale.US, "%02x", it) }
}

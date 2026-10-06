package com.ccatq.xdylupdater2.downloads

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

object FileIntegrity {
    fun safeName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\x00-\\x1f:?%*|\"<>]"), "-").trim().take(160).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "resource.bin"
    fun sha256(file: File, checkActive: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1_048_576)
            while (true) { checkActive(); val count = input.read(buffer); if (count == -1) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun commit(staging: File, destination: File, expected: String?, checkActive: () -> Unit = {}) {
        if (!expected.isNullOrBlank()) {
            require(Regex("[a-fA-F0-9]{64}").matches(expected)) { "清单 SHA-256 格式无效" }
            require(sha256(staging, checkActive).equals(expected, true)) { "文件校验失败" }
        }
        checkActive()
        destination.parentFile!!.mkdirs()
        Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
}

package com.ccatq.xdylupdater2.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretFiles(private val context: Context) {
    private val alias = "starwave.private.v1"
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun file(name: String) = AtomicFile(File(context.noBackupFilesDir, "$name.enc"))
    @Synchronized fun read(name: String): String? {
        val file = file(name)
        if (!file.baseFile.exists()) return null
        return runCatching {
            val bytes = file.readFully(); val n = bytes[0].toInt() and 255
            require(n == 12 && bytes.size > n + 1)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, n + 1)))
            cipher.doFinal(bytes.copyOfRange(n + 1, bytes.size)).toString(Charsets.UTF_8)
        }.getOrElse { file.delete(); null }
    }
    @Synchronized fun write(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(value.toByteArray())
        val file = file(name); val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized fun delete(name: String) { file(name).delete() }
}
class SessionStore(private val secrets: SecretFiles) : TokenStore {
    private val state = MutableStateFlow(secrets.read("session")?.let { runCatching { wireJson.decodeFromString<AuthTokens>(it) }.getOrNull() })
    override val tokens = state.asStateFlow()
    @Synchronized override fun save(tokens: AuthTokens) { secrets.write("session", wireJson.encodeToString(tokens)); state.value = tokens }
    @Synchronized override fun clear() { secrets.delete("session"); state.value = null }
}

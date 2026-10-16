package io.agentspace.quest.connection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The dedicated SSH private key is only decrypted into memory for JSch. */
class DeviceKeyStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "agentspace-device-key.json"))

    fun publicKey(): String = synchronized(lock) { ensureKey().getString("publicKey") }

    internal fun configureIdentity(jsch: JSch) = synchronized(lock) {
        val saved = ensureKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, Base64.getDecoder().decode(saved.getString("iv"))))
        val privateBytes = cipher.doFinal(Base64.getDecoder().decode(saved.getString("ciphertext")))
        try { jsch.addIdentity("agentspace-device", privateBytes, null, null) }
        finally { privateBytes.fill(0) }
    }

    private fun ensureKey(): JSONObject {
        if (file.baseFile.exists()) return JSONObject(String(file.readFully(), Charsets.UTF_8))
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
        val output = WipingBuffer()
        try {
            pair.writePrivateKey(output)
            val privateBytes = output.toByteArray()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
            val ciphertext = try { cipher.doFinal(privateBytes) } finally { privateBytes.fill(0) }
            val publicOutput = ByteArrayOutputStream()
            pair.writePublicKey(publicOutput, "agentspace-quest")
            val record = JSONObject().put("version", 1)
                .put("publicKey", publicOutput.toString("UTF-8").trim())
                .put("iv", Base64.getEncoder().encodeToString(cipher.iv))
                .put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
            val stream = file.startWrite()
            try {
                stream.write(record.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
            return record
        } finally { output.wipe(); pair.dispose() }
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(!file.baseFile.exists()) { "Device key storage is unavailable. Pair this installation again." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private class WipingBuffer : ByteArrayOutputStream() { fun wipe() { buf.fill(0); reset() } }
    companion object {
        private const val ALIAS = "agentspace-device-key-wrap-v1"
        private val lock = Any()
    }
}

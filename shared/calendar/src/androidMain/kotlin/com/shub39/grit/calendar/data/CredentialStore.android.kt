/*
 * Copyright (C) 2026  Niels Robin-Aubertin
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.shub39.grit.calendar.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.koin.core.annotation.Single

/**
 * Encrypts passwords with AES-GCM and a key that never leaves the Android Keystore. The file is in
 * `noBackupFilesDir`, because the key is not part of a backup.
 */
@Single
actual class CredentialStore(private val context: Context) {
    private val file: File
        get() = File(context.applicationContext.noBackupFilesDir, FILE_NAME)

    @Synchronized
    actual fun save(accountId: Long, password: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        val properties = read()
        properties.setProperty(
            accountId.toString(),
            encoder.encodeToString(cipher.iv) + ":" + encoder.encodeToString(encrypted),
        )
        write(properties)
    }

    @Synchronized
    actual fun load(accountId: Long): String? =
        runCatching {
                val value = read().getProperty(accountId.toString()) ?: return null
                val (iv, encrypted) = value.split(":").map { Base64.getDecoder().decode(it) }
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                String(cipher.doFinal(encrypted), Charsets.UTF_8)
            }
            .getOrNull()

    @Synchronized
    actual fun delete(accountId: Long) {
        val properties = read()
        properties.remove(accountId.toString())
        write(properties)
    }

    private fun read(): Properties =
        Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }

    private fun write(properties: Properties) {
        file.outputStream().use { properties.store(it, null) }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
            return it
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE_NAME = "calendar_credentials.properties"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "grit_calendar_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

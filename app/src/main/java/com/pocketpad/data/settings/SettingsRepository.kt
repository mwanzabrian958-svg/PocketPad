package com.pocketpad.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val Context.preferencesDataStore by preferencesDataStore(name = "pocketpad_settings")

data class AppSettings(
    val rememberedMethod: String = "Auto",
    val rememberConnection: Boolean = true,
    val darkTheme: Boolean? = true,
    val highContrast: Boolean = false,
    val lowPower: Boolean = false,
    val haptics: Boolean = true,
    val audioFeedback: Boolean = false,
    val controllerMode: String = "Xbox",
    val controllerSkin: String = "Neon Dark",
    val sensitivity: Float = 0.75f,
    val deadZone: Float = 0.15f,
    val turboMode: Boolean = false,
    val rememberedHost: String = "",
    val rememberedPort: Int = 26760,
    val hasRememberedPairingKey: Boolean = false
)

data class RememberedConnection(val host: String, val port: Int, val pairingKey: String)

class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val settings: Flow<AppSettings> = context.preferencesDataStore.data.map { prefs ->
        AppSettings(
            rememberedMethod = prefs[REMEMBERED_METHOD] ?: "Auto",
            rememberConnection = prefs[REMEMBER_CONNECTION] ?: true,
            darkTheme = prefs[DARK_THEME] ?: true,
            highContrast = prefs[HIGH_CONTRAST] ?: false,
            lowPower = prefs[LOW_POWER] ?: false,
            haptics = prefs[HAPTICS] ?: true,
            audioFeedback = prefs[AUDIO_FEEDBACK] ?: false,
            controllerMode = prefs[CONTROLLER_MODE] ?: "Xbox",
            controllerSkin = prefs[CONTROLLER_SKIN] ?: "Neon Dark",
            sensitivity = prefs[SENSITIVITY] ?: 0.75f,
            deadZone = prefs[DEAD_ZONE] ?: 0.15f,
            turboMode = prefs[TURBO_MODE] ?: false,
            rememberedHost = prefs[REMEMBERED_HOST].orEmpty(),
            rememberedPort = prefs[REMEMBERED_PORT] ?: 26760,
            hasRememberedPairingKey = prefs[REMEMBERED_PAIRING_KEY] != null
        )
    }

    suspend fun setRememberedMethod(method: String) {
        context.preferencesDataStore.edit { it[REMEMBERED_METHOD] = method }
    }

    suspend fun setRememberConnection(enabled: Boolean) {
        context.preferencesDataStore.edit { it[REMEMBER_CONNECTION] = enabled }
    }

    suspend fun setDarkTheme(enabled: Boolean?) {
        context.preferencesDataStore.edit {
            if (enabled == null) it.remove(DARK_THEME) else it[DARK_THEME] = enabled
        }
    }

    suspend fun setLowPower(enabled: Boolean) {
        context.preferencesDataStore.edit { it[LOW_POWER] = enabled }
    }

    suspend fun setHighContrast(enabled: Boolean) {
        context.preferencesDataStore.edit { it[HIGH_CONTRAST] = enabled }
    }

    suspend fun setHaptics(enabled: Boolean) {
        context.preferencesDataStore.edit { it[HAPTICS] = enabled }
    }

    suspend fun setAudioFeedback(enabled: Boolean) {
        context.preferencesDataStore.edit { it[AUDIO_FEEDBACK] = enabled }
    }

    suspend fun setControllerMode(mode: String) {
        require(mode == "Xbox" || mode == "PlayStation") { "Unsupported controller button layout." }
        context.preferencesDataStore.edit { it[CONTROLLER_MODE] = mode }
    }

    suspend fun setControllerSkin(skin: String) {
        val validSkins = setOf("Neon Dark", "Cyberpunk", "Transparent Sleek", "Retro Arcade")
        require(skin in validSkins) { "Unsupported controller skin." }
        context.preferencesDataStore.edit { it[CONTROLLER_SKIN] = skin }
    }

    suspend fun setSensitivity(value: Float) {
        context.preferencesDataStore.edit { it[SENSITIVITY] = value.coerceIn(0f, 1f) }
    }

    suspend fun setDeadZone(value: Float) {
        context.preferencesDataStore.edit { it[DEAD_ZONE] = value.coerceIn(0f, 0.5f) }
    }

    suspend fun setTurboMode(enabled: Boolean) {
        context.preferencesDataStore.edit { it[TURBO_MODE] = enabled }
    }

    suspend fun saveRememberedConnection(host: String, port: Int, pairingKey: String) {
        require(host.isNotBlank()) { "Host address must not be blank." }
        require(port in 1..65535) { "Port must be between 1 and 65535." }
        val encryptedKey = encrypt(pairingKey)
        context.preferencesDataStore.edit {
            it[REMEMBERED_HOST] = host.trim()
            it[REMEMBERED_PORT] = port
            it[REMEMBERED_PAIRING_KEY] = encryptedKey
        }
    }

    /**
     * Remembers a bonded Bluetooth host. Bluetooth HID authenticates at the Bluetooth
     * layer, so no pairing secret is stored and [hasRememberedPairingKey] stays false.
     */
    suspend fun saveRememberedBluetoothHost(address: String) {
        require(address.isNotBlank()) { "Bluetooth host address must not be blank." }
        context.preferencesDataStore.edit {
            it[REMEMBERED_HOST] = address.trim()
            it[REMEMBERED_PORT] = 0
            it.remove(REMEMBERED_PAIRING_KEY)
        }
    }

    /**
     * Returns the remembered host. Bluetooth entries carry no pairing secret and use port
     * [BLUETOOTH_PORT], so they are returned with a blank key rather than being rejected.
     */
    suspend fun loadRememberedConnection(): RememberedConnection? {
        val prefs = context.preferencesDataStore.data.first()
        val host = prefs[REMEMBERED_HOST].orEmpty()
        if (host.isBlank()) return null
        val port = prefs[REMEMBERED_PORT] ?: 26760
        val encryptedKey = prefs[REMEMBERED_PAIRING_KEY]
        if (encryptedKey == null) {
            return if (port == BLUETOOTH_PORT) RememberedConnection(host, port, "") else null
        }
        if (port !in 1..65535) return null
        return RememberedConnection(host, port, decrypt(encryptedKey))
    }

    suspend fun clearRememberedConnection() {
        context.preferencesDataStore.edit {
            it.remove(REMEMBERED_HOST)
            it.remove(REMEMBERED_PORT)
            it.remove(REMEMBERED_PAIRING_KEY)
        }
    }

    private companion object {
        const val KEY_ALIAS = "pocketpad_pairing_key_v1"

        /**
         * Sentinel port marking a remembered entry as a Bluetooth host, which has no
         * network port and no pairing key.
         */
        const val BLUETOOTH_PORT = 0
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
        val REMEMBERED_METHOD = stringPreferencesKey("remembered_method")
        val REMEMBER_CONNECTION = booleanPreferencesKey("remember_connection")
        val REMEMBERED_HOST = stringPreferencesKey("remembered_host")
        val REMEMBERED_PORT = intPreferencesKey("remembered_port")
        val REMEMBERED_PAIRING_KEY = stringPreferencesKey("remembered_pairing_key")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        val LOW_POWER = booleanPreferencesKey("low_power")
        val HAPTICS = booleanPreferencesKey("haptics")
        val AUDIO_FEEDBACK = booleanPreferencesKey("audio_feedback")
        val CONTROLLER_MODE = stringPreferencesKey("controller_mode")
        val CONTROLLER_SKIN = stringPreferencesKey("controller_skin")
        val SENSITIVITY = androidx.datastore.preferences.core.floatPreferencesKey("sensitivity")
        val DEAD_ZONE = androidx.datastore.preferences.core.floatPreferencesKey("dead_zone")
        val TURBO_MODE = booleanPreferencesKey("turbo_mode")
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val combined = Base64.decode(value, Base64.NO_WRAP)
        require(combined.size > GCM_IV_LENGTH) { "Stored pairing key is invalid." }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val encrypted = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

}

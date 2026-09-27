package dev.zed.mobile

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Stores a v2 per-device credential. Implement this interface in JVM tests instead of depending on
 * Android DataStore or Android Keystore.
 */
interface CredentialStore {
    suspend fun load(): DeviceCredential?
    suspend fun save(credential: DeviceCredential)
    suspend fun clear()
}

/** A simple test double for JVM tests and non-persistent previews. */
class InMemoryCredentialStore(initial: DeviceCredential? = null) : CredentialStore {
    private var value = initial

    override suspend fun load(): DeviceCredential? = synchronized(this) { value }

    override suspend fun save(credential: DeviceCredential) {
        synchronized(this) { value = credential }
    }

    override suspend fun clear() {
        synchronized(this) { value = null }
    }
}

private val Context.zedCredentialDataStore by preferencesDataStore(name = "zed_mobile_credentials")
private val encryptedCredentialKey = stringPreferencesKey("v2_encrypted_credential")

/**
 * Android production implementation. DataStore only receives an AES-GCM ciphertext; its AES key
 * is non-exportable and held by Android Keystore.
 */
class AndroidCredentialStore(context: Context) : CredentialStore {
    private val appContext = context.applicationContext

    override suspend fun load(): DeviceCredential? {
        val encrypted = appContext.zedCredentialDataStore.data.first()[encryptedCredentialKey] ?: return null
        val encoded = try {
            withContext(Dispatchers.IO) { decrypt(encrypted) }
        } catch (error: Exception) {
            discardInvalidCredential(error)
        }
        val credential = try {
            decodeCredential(encoded)
        } catch (error: Exception) {
            discardInvalidCredential(error)
        }
        try {
            V2PairingValidation.deviceCredential(credential, isDebuggable())
        } catch (error: IllegalArgumentException) {
            discardInvalidCredential(error)
        }
        return credential
    }

    override suspend fun save(credential: DeviceCredential) {
        try {
            V2PairingValidation.deviceCredential(credential, isDebuggable())
        } catch (error: IllegalArgumentException) {
            throw CredentialStoreException("Refusing to save an invalid device credential.", error)
        }
        val encrypted = try {
            withContext(Dispatchers.IO) { encrypt(encodeCredential(credential)) }
        } catch (error: Exception) {
            throw CredentialStoreException("Unable to securely save the pairing.", error)
        }
        appContext.zedCredentialDataStore.edit { preferences ->
            preferences[encryptedCredentialKey] = encrypted
        }
    }

    override suspend fun clear() {
        appContext.zedCredentialDataStore.edit { preferences ->
            preferences.remove(encryptedCredentialKey)
        }
        withContext(Dispatchers.IO) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private suspend fun discardInvalidCredential(cause: Exception): Nothing {
        try {
            clear()
        } catch (clearError: Exception) {
            cause.addSuppressed(clearError)
        }
        throw CredentialStoreException("Saved pairing is invalid. Pair this device again.", cause)
    }

    private fun isDebuggable(): Boolean =
        appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun encrypt(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext)
        val payload = cipher.iv + ciphertext
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }

    private fun decrypt(encoded: String): ByteArray {
        val payload = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw CredentialStoreException("Stored pairing ciphertext is malformed.", error)
        }
        require(payload.size > GCM_IV_BYTES + GCM_TAG_BYTES) { "Stored pairing ciphertext is too short." }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun secretKey(): SecretKey = synchronized(keyLock) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        generator.generateKey()
    }

    private fun encodeCredential(credential: DeviceCredential): ByteArray = JsonObject().apply {
        addProperty("origin", credential.origin)
        addProperty("device_id", credential.deviceId)
        addProperty("credential", credential.credential)
    }.toString().toByteArray(StandardCharsets.UTF_8)

    private fun decodeCredential(value: ByteArray): DeviceCredential {
        val objectValue = JsonParser.parseString(String(value, StandardCharsets.UTF_8)).asJsonObject
        require(objectValue.keySet() == CREDENTIAL_FIELDS) { "Stored pairing fields are invalid." }
        return DeviceCredential(
            origin = objectValue.requiredString("origin"),
            deviceId = objectValue.requiredString("device_id"),
            credential = objectValue.requiredString("credential"),
        )
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dev.zeddesktop.mobile.v2.device-credential"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        val CREDENTIAL_FIELDS = setOf("origin", "device_id", "credential")
        val keyLock = Any()
    }
}

private fun JsonObject.requiredString(name: String): String {
    val value = get(name)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
        "Stored pairing field '$name' is invalid."
    }
    return value.asString
}

/** Initialized by [ZedMobileApplication] before any production view model is created. */
object CredentialStores {
    @Volatile
    private var productionStore: CredentialStore? = null

    fun initialize(context: Context) {
        if (productionStore == null) {
            synchronized(this) {
                if (productionStore == null) productionStore = AndroidCredentialStore(context)
            }
        }
    }

    internal fun production(): CredentialStore = checkNotNull(productionStore) {
        "CredentialStores has not been initialized. Use an injected CredentialStore in JVM tests."
    }
}

/** Delays Android access so v1-only JVM tests can still construct [ZedViewModel]. */
class ApplicationCredentialStore : CredentialStore {
    override suspend fun load(): DeviceCredential? = CredentialStores.production().load()
    override suspend fun save(credential: DeviceCredential) = CredentialStores.production().save(credential)
    override suspend fun clear() = CredentialStores.production().clear()
}

class ZedMobileApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CredentialStores.initialize(this)
    }
}

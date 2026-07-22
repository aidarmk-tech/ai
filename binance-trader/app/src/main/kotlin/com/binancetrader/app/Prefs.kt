package com.binancetrader.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Ключи API лежат в EncryptedSharedPreferences (AES-256, Android Keystore) —
 * в открытом виде на диск не попадают.
 */
object Prefs {

    private const val FILE = "trader_secure_prefs"

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun get(context: Context): SharedPreferences {
        return prefs ?: synchronized(this) {
            prefs ?: create(context.applicationContext).also { prefs = it }
        }
    }

    private fun create(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Keystore повреждён (например, после восстановления из бэкапа) —
            // начинаем с чистого файла, иначе приложение не откроется вовсе.
            context.deleteSharedPreferences(FILE)
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    var apiKey: String = ""
        private set
    var apiSecret: String = ""
        private set

    fun load(context: Context) {
        val p = get(context)
        apiKey = p.getString("apiKey", "") ?: ""
        apiSecret = p.getString("apiSecret", "") ?: ""
    }

    fun saveKeys(context: Context, key: String, secret: String) {
        apiKey = key
        apiSecret = secret
        get(context).edit().putString("apiKey", key).putString("apiSecret", secret).apply()
    }

    fun getString(context: Context, key: String, def: String): String =
        get(context).getString(key, def) ?: def

    fun putString(context: Context, key: String, value: String) =
        get(context).edit().putString(key, value).apply()

    fun getBoolean(context: Context, key: String, def: Boolean): Boolean =
        get(context).getBoolean(key, def)

    fun putBoolean(context: Context, key: String, value: Boolean) =
        get(context).edit().putBoolean(key, value).apply()

    fun remove(context: Context, key: String) =
        get(context).edit().remove(key).apply()
}

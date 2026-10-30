// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.*

class Storage(private val context: Context) {
    private val prefs = context.getSharedPreferences("lad", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("lad-token", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                "lad-token",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    }

    fun token(): String {
        val saved = prefs.getString("session", null) ?: return ""
        val parts = saved.split('.')
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)))
    }

    fun token(value: String) {
        if (value.isBlank()) {
            prefs.edit().remove("session").apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        check(prefs.edit().putString("session", "$iv.$encrypted").commit()) {
            "Не удалось сохранить сессию"
        }
    }

    fun settings() =
        Settings(
            prefs.getBoolean("oled", false),
            prefs.getBoolean("glass", true),
            prefs.getBoolean("tinted", false),
            prefs.getLong("accent", 0xFFE2E6EC),
            prefs.getFloat("scale", 1f).coerceIn(.85f, 1.2f),
            prefs.getBoolean("blockExplicit", false),
            prefs.getLong("lyricOffset", 0),
            prefs.getFloat("waveIntensity", 100f).let { if(it.isFinite())it.coerceIn(0f,100f)else 100f },
            prefs.getFloat("waveSmoothing", 50f).let { if(it.isFinite())it.coerceIn(0f,100f)else 50f },
        )

    fun settings(s: Settings) {
        prefs
            .edit()
            .putBoolean("oled", s.oled)
            .putBoolean("glass", s.glass)
            .putBoolean("tinted", s.tinted)
            .putLong("accent", s.accent)
            .putFloat("scale", s.scale)
            .putBoolean("blockExplicit", s.blockExplicit)
            .putLong("lyricOffset", s.lyricOffsetMs)
            .putFloat("waveIntensity", s.waveIntensity)
            .putFloat("waveSmoothing", s.waveSmoothing)
            .apply()
    }

    fun queue(uid: String): Pair<List<Track>, Int> =
        runCatching {
                val j = JSONObject(prefs.getString("queue:$uid", "{}")!!)
                j.array("tracks").objects().map(Track::fromJson) to j.optInt("index", -1)
            }
            .getOrDefault(emptyList<Track>() to -1)

    fun queue(uid: String, tracks: List<Track>, index: Int) {
        prefs
            .edit()
            .putString(
                "queue:$uid",
                JSONObject()
                    .put("tracks", JSONArray(tracks.map(Track::json)))
                    .put("index", index)
                    .toString(),
            )
            .apply()
    }

    fun host(uid: String, id: String) = prefs.getString("host:$uid:$id", "").orEmpty()

    fun host(uid: String, id: String, value: String) {
        prefs.edit().putString("host:$uid:$id", value).apply()
    }
}

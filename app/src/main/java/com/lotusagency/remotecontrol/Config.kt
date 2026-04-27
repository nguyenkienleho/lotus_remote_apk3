package com.lotusagency.remotecontrol

import java.net.URL
import java.security.MessageDigest

object Config {
    // URL Gist để lấy server URL động
    const val GIST_RAW_URL = "https://gist.githubusercontent.com/nguyenkienleho/15c5796dbd51ccb61123055850aea9da/raw/lotus_server.txt"
    // Fallback nếu không fetch được
    const val FALLBACK_URL = "wss://coordinate-participation-innovation-effects.trycloudflare.com"
    const val SECRET_KEY   = "LotusAgency2025!"
    const val DEVICE_ID    = "phone_01"
    const val NOTIF_CH     = "lotus_remote"

    fun makeToken(): String {
        val md   = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(SECRET_KEY.toByteArray())
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }

    fun fetchServerUrl(): String {
        return try {
            val url = URL(GIST_RAW_URL).readText(Charsets.UTF_8).trim()
            if (url.startsWith("wss://") || url.startsWith("ws://")) url
            else FALLBACK_URL
        } catch (e: Exception) {
            FALLBACK_URL
        }
    }
}

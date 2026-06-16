package com.streamvault.player.playback

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

fun OkHttpClient.Builder.applyUnsafeTlsBypass(): OkHttpClient.Builder {
    val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAllManager), SecureRandom())
    }
    return sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier(HostnameVerifier { _, _ -> true })
}

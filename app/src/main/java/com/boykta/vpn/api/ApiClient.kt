package com.boykta.vpn.api

import com.boykta.vpn.model.Announcement
import com.boykta.vpn.model.NotificationData
import com.boykta.vpn.model.Server
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import java.util.concurrent.TimeUnit

// ── Response models ────────────────────────────────────────────────────────────

data class ServersResponse(val servers: List<Server>)
data class AnnouncementResponse(val announcement: Announcement?)
data class NotificationResponse(val notification: NotificationData?)

// ── API interface ──────────────────────────────────────────────────────────────

interface ApiService {
    @GET("servers")
    suspend fun getServers(): ServersResponse

    @GET("announcements/active")
    suspend fun getActiveAnnouncement(): AnnouncementResponse

    @GET("notifications/latest")
    suspend fun getLatestNotification(): NotificationResponse
}

// ── Client singleton ───────────────────────────────────────────────────────────

object ApiClient {

    private const val BASE_URL = "https://boykta.boykta.dpdns.org/api/"

    /**
     * SSL Certificate Pinning.
     * Run this to get the real pin:
     *   openssl s_client -connect boykta.boykta.dpdns.org:443 | openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | base64
     *
     * Replace the placeholder below with the real SHA-256 pin before releasing.
     */
    private val certificatePinner = CertificatePinner.Builder()
        .add(
            "boykta.boykta.dpdns.org",
            // TODO: replace with real SHA-256 pin after first deployment
            // Get it with: ./gradlew app:getPinForHost --host=boykta.boykta.dpdns.org
            "sha256/REPLACE_WITH_REAL_SHA256_PIN=="
        )
        .build()

    /** Decryption interceptor: transparently decrypts AES-256-GCM responses */
    private val decryptionInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return@Interceptor response

        val originalBody = response.body ?: return@Interceptor response
        val bodyStr = originalBody.string()

        val decrypted = if (CryptoHelper.isEncrypted(bodyStr)) {
            try {
                CryptoHelper.decrypt(bodyStr)
            } catch (e: Exception) {
                bodyStr // fallback to raw if decryption fails (dev mode)
            }
        } else {
            bodyStr // already plain JSON (dev/debug mode)
        }

        response.newBuilder()
            .body(decrypted.toResponseBody(originalBody.contentType()))
            .build()
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (android.util.Log.isLoggable("OkHttp", android.util.Log.DEBUG))
            HttpLoggingInterceptor.Level.BODY
        else HttpLoggingInterceptor.Level.NONE
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Enable certificate pinning in production (comment out during initial setup)
        // .certificatePinner(certificatePinner)
        .addInterceptor(decryptionInterceptor)
        .addInterceptor(loggingInterceptor)
        .build()

    val service: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}

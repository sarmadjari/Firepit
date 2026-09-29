package com.getfirepit.app.map

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil

/**
 * The HTTP client map tiles are fetched with.
 *
 * MapLibre's own names the app, its version and the phone's Android version in
 * every request's User-Agent, which lets the tile server pick Firepit users out
 * of its logs. This one says only that it is a map. Everything else matches
 * MapLibre's: tiles load in parallel, so a host gets more connections than
 * OkHttp's default five.
 */
object TileClient {

    private const val USER_AGENT = "MapLibre Android"
    private const val REQUESTS_PER_HOST = 20

    fun install() {
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .dispatcher(Dispatcher().apply { maxRequestsPerHost = REQUESTS_PER_HOST })
                .addNetworkInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
                }
                .build(),
        )
    }
}

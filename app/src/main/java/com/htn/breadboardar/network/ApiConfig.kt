package com.htn.breadboardar.network

import com.htn.breadboardar.BuildConfig

/** Build-time default. The app's Settings may override the service origin; no provider key. */
object ApiConfig {
    val baseUrl: String = BuildConfig.CIRCUIT_API_URL
}

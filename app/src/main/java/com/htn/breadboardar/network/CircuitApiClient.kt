package com.htn.breadboardar.network

import android.os.Handler
import android.os.Looper
import com.htn.breadboardar.circuit.CircuitDefinition
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class CircuitApiClient(
    private val kitJson: String,
    private val baseUrlProvider: () -> String = { ApiConfig.baseUrl },
) {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS).callTimeout(160, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    private var active: Call? = null
    sealed class Result {
        data class Success(val circuit: CircuitDefinition) : Result()
        data class Failure(val userMessage: String) : Result()
    }
    fun cancel() { active?.cancel(); active = null; main.removeCallbacksAndMessages(null) }
    fun generate(prompt: String, sessionId: String, callback: (Result) -> Unit) {
        cancel()
        val request = try {
            Request.Builder().url(baseUrlProvider().trimEnd('/') + "/api/circuits/generate")
                .post(JSONObject(kitJson).put("prompt", prompt).put("sessionId", sessionId).toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        } catch (_: IllegalArgumentException) { callback(Result.Failure("Check the circuit service URL in Settings.")); return }
        val call = client.newCall(request); active = call
        fun deliver(result: Result) { main.post { if (active === call && !call.isCanceled()) { active = null; callback(result) } } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { deliver(Result.Failure("Couldn't reach the circuit service. For USB testing, run scripts/run-android-usb.sh with the phone connected. For a hosted build, check the URL in Settings.")) }
            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        if (it.isSuccessful) {
                            val circuit = CircuitDefinition.parse(body)
                            if (circuit.sessionId != sessionId) Result.Failure("The service returned a different circuit. Please try again.") else Result.Success(circuit)
                        } else {
                            val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
                            val message = when (error?.optString("code")) {
                                "UNSUPPORTED_CIRCUIT", "UNSUPPORTED_PART", "MISSING_PARTS", "INVALID_REQUEST" -> error.optString("message", "This circuit is not supported by the current kit.")
                                "PROVIDER_ERROR", "PROVIDER_UNAVAILABLE", "INVALID_PROVIDER", "INVALID_PROVIDER_RESPONSE", "INCOMPLETE_GENERATION" -> error.optString("message", "The model provider could not complete this circuit.")
                                "BUSY" -> "The circuit service is busy. Try again in a moment."
                                "API_KEY_MISSING", "WRONG_PROVIDER_KEY" -> "Your circuit service needs its model key configured."
                                else -> "Couldn't generate a circuit. Please try again."
                            }
                            Result.Failure(message)
                        }
                    }
                } catch (e: Exception) { Result.Failure(e.message?.takeIf { e is com.htn.breadboardar.circuit.CircuitParseException } ?: "The service returned an unreadable circuit. Please try again.") }
                deliver(result)
            }
        })
    }
}

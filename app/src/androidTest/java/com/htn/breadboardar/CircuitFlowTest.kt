package com.htn.breadboardar

import android.content.Context
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CircuitFlowTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    @Volatile private var failGeneration = false
    @Before fun setUp() {
        File(context.filesDir, "last-circuit.json").delete()
        context.getSharedPreferences("circuit", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (failGeneration) return MockResponse().setResponseCode(503).setBody("{\"error\":{\"code\":\"PROVIDER_UNAVAILABLE\"}}")
                val sent = JSONObject(request.body.readUtf8())
                val fixture = JSONObject(context.assets.open("led.placement.json").bufferedReader().use { it.readText() })
                fixture.put("sessionId", sent.getString("sessionId"))
                return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(fixture.toString())
            }
        }
        server.start()
        context.getSharedPreferences("circuit", Context.MODE_PRIVATE).edit().putString("api_url", server.url("/").toString().trimEnd('/')).commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }
    @After fun tearDown() {
        scenario.close(); server.shutdown()
        context.getSharedPreferences("circuit", Context.MODE_PRIVATE).edit().remove("api_url").commit()
    }
    private fun awaitText(id: Int, text: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            var found = false
            scenario.onActivity { found = it.findViewById<TextView>(id).text.toString().contains(text) }
            if (found) return
            Thread.sleep(50)
        } while (System.nanoTime() < deadline)
        fail("Timed out waiting for $text")
    }
    private fun generate() {
        onView(withId(R.id.example_led)).perform(scrollTo(), click())
        onView(withId(R.id.generate_button)).perform(scrollTo(), click())
        val request = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("/api/circuits/generate", request.path)
        awaitText(R.id.circuit_title, "Simple LED")
        onView(withId(R.id.review_panel)).check(matches(isDisplayed()))
    }
    @Test fun promptToReviewAndSavedCircuitSurvivesRecreation() {
        generate()
        val saved = File(context.filesDir, "last-circuit.json").readText()
        assertTrue(JSONObject(saved).getString("sessionId").startsWith("android-"))
        onView(withId(R.id.next_step)).perform(scrollTo(), click())
        onView(withId(R.id.step_label)).check(matches(withText("Step 1 of 5")))
        scenario.recreate()
        onView(withId(R.id.circuit_title)).check(matches(withText("Simple LED")))
        awaitText(R.id.step_label, "Step 1 of 5")
    }
    @Test fun failedRegenerationDoesNotOverwriteValidCircuit() {
        generate()
        val saved = File(context.filesDir, "last-circuit.json").readText()
        onView(withId(R.id.new_circuit_button)).perform(scrollTo(), click())
        failGeneration = true
        onView(withId(R.id.generate_button)).perform(scrollTo(), click())
        awaitText(R.id.circuit_status, "Your previous circuit is still saved")
        assertEquals(saved, File(context.filesDir, "last-circuit.json").readText())
        onView(withId(R.id.return_button)).perform(scrollTo(), click())
        onView(withId(R.id.circuit_title)).check(matches(withText("Simple LED")))
    }
    @Test fun unityEntryReceivesTheReviewedCircuit() {
        if (!BuildConfig.UNITY_AVAILABLE) return
        generate()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
        onView(withId(R.id.show_ar_button)).perform(scrollTo(), click())
        val saved = File(context.filesDir, "last-circuit.json").readText()
        assertEquals(saved, File(context.filesDir, "unity-circuit.json").readText())
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        Thread.sleep(12000) // Allow IL2CPP and the AR provider to initialize on the emulator.
        assertTrue("Unity process must survive startup", manager.runningAppProcesses.any { it.processName == context.packageName + ":unity" })
        val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "unity-startup.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

}

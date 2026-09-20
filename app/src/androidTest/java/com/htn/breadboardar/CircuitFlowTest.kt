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
                if (!sent.has("availableParts") || !sent.has("breadboardModel")) return MockResponse().setResponseCode(400).setBody("{\"error\":{\"code\":\"INVALID_REQUEST\",\"message\":\"Full kit required\"}}")
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
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
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
    private fun capturePreview() {
        onView(withId(R.id.preview_3d_button)).perform(scrollTo(), click())
        val saved = File(context.filesDir, "last-circuit.json").readText()
        assertEquals(saved, File(context.filesDir, "ar-circuit.json").readText())
        Thread.sleep(4000) // Filament asynchronously uploads the actual mesh resources.
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        var screenshot: android.graphics.Bitmap? = null
        instrumentation.runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<CircuitPreviewActivity>().single()
            fun texture(view: android.view.View): android.view.TextureView? {
                if (view is android.view.TextureView) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) texture(view.getChildAt(i))?.let { return it }
                return null
            }
            screenshot = texture(activity.window.decorView)?.bitmap
        }
        val bitmap = requireNotNull(screenshot) { "Native renderer did not produce an image" }
        val colors = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step 5) for (x in 0 until bitmap.width step 5) colors.add(bitmap.getPixel(x,y))
        assertTrue("Circuit preview must contain rendered geometry", colors.size > 100)
        File(context.getExternalFilesDir(null), "native-circuit-preview.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        val screen=instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "native-circuit-screen.png").outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        instrumentation.runOnMainSync {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<CircuitPreviewActivity>().forEach { it.finish() }
        }
    }
    @Test fun nativePreviewReceivesAndRendersTheReviewedCircuit() {
        generate()
        capturePreview()
    }
    @Test fun nativeArOpensTheReviewedCircuitWithoutManualModelUrl() {
        generate()
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
        onView(withId(R.id.show_ar_button)).perform(scrollTo(), click())
        Thread.sleep(3000)
        assertEquals(File(context.filesDir,"last-circuit.json").readText(),File(context.filesDir,"ar-circuit.json").readText())
        instrumentation.runOnMainSync {
            val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<ArViewerActivity>().single()
            assertNotNull(activity.findViewById<android.view.TextureView>(R.id.model_overlay))
            activity.finish()
        }
    }
    @Test fun liveButtonPromptThroughReviewAndNativeModels() {
        val liveUrl = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("liveApi")
        org.junit.Assume.assumeTrue("Opt-in live provider check", !liveUrl.isNullOrBlank())
        context.getSharedPreferences("circuit", Context.MODE_PRIVATE).edit().putString("api_url",liveUrl).commit()
        onView(withId(R.id.prompt_input)).perform(scrollTo(), androidx.test.espresso.action.ViewActions.replaceText("make red led with button"))
        onView(withId(R.id.generate_button)).perform(scrollTo(), click())
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(160)
        val saved=File(context.filesDir,"last-circuit.json")
        while (!saved.isFile && System.nanoTime()<deadline) Thread.sleep(200)
        assertTrue("Live generation did not save a validated circuit",saved.isFile)
        val model=JSONObject(saved.readText())
        assertNotEquals("fixture",model.getString("source"))
        val parts=model.getJSONArray("components")
        assertTrue((0 until parts.length()).any { parts.getJSONObject(it).getString("type")=="button" })
        awaitText(R.id.circuit_title, model.getString("title"))
        capturePreview()
    }
}

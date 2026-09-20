package com.htn.breadboardar

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ArControlsLayoutTest {
    @Test
    fun backLabelFitsOnSmallPhonesAndAtLargerFontSizes() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (widthDp in listOf(320, 360, 412)) {
                for (fontScale in listOf(1f, 1.3f, 2f)) {
                    val config = Configuration(application.resources.configuration).apply {
                        this.fontScale = fontScale
                    }
                    val context = ContextThemeWrapper(application.createConfigurationContext(config),
                        R.style.Theme_BreadboardArViewer)
                    val controls = LayoutInflater.from(context).inflate(R.layout.view_ar_controls, null)
                    val density = context.resources.displayMetrics.density
                    controls.measure(View.MeasureSpec.makeMeasureSpec((widthDp * density).roundToInt(),
                        View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    controls.layout(0, 0, controls.measuredWidth, controls.measuredHeight)
                    val back = controls.findViewById<Button>(R.id.back_to_circuit)
                    val calibrate = controls.findViewById<Button>(R.id.calibrate_button)
                    val reset = controls.findViewById<Button>(R.id.reset_button)
                    val case = "width=$widthDp fontScale=$fontScale"
                    assertEquals(case, 1, back.layout.lineCount)
                    assertEquals(case, 0, back.layout.getEllipsisCount(0))
                    assertTrue(case, back.layout.getLineWidth(0) <= back.width - back.compoundPaddingLeft - back.compoundPaddingRight)
                    assertTrue(case, back.height >= 48 * density)
                    assertEquals(case, controls.width - controls.paddingLeft - controls.paddingRight, back.width)
                    assertTrue(case, back.top >= (calibrate.parent as View).bottom)
                    assertEquals(case, calibrate.measuredHeight, reset.measuredHeight)
                    var clicked = false
                    back.setOnClickListener { clicked = true }
                    assertTrue(case, back.performClick())
                    assertTrue(case, clicked)
                }
            }
        }
    }
}

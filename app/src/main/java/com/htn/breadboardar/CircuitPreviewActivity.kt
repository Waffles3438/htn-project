package com.htn.breadboardar

import android.graphics.Color
import android.os.Bundle
import android.view.TextureView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.htn.breadboardar.circuit.CircuitDefinition
import com.htn.breadboardar.render.NativeBreadboardRenderer
import java.io.File

/** The exact AR scene, inspectable without camera permission or tracking hardware. */
class CircuitPreviewActivity : AppCompatActivity() {
    private var renderer: NativeBreadboardRenderer? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(22,42,35)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        setContentView(root)
        androidx.core.view.WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = false
        try {
            val file=File(filesDir,"ar-circuit.json")
            require(file.isFile && file.length() <= 1024*1024) { "Choose a circuit first." }
            val circuit=CircuitDefinition.parse(file.readText())
            root.addView(TextView(this).apply { text=circuit.title; textSize=24f; setTextColor(Color.WHITE); setPadding(32,24,32,8) })
            root.addView(TextView(this).apply { text="Pinch to zoom · drag to rotate · same models as AR"; textSize=14f; setTextColor(Color.rgb(192,213,197)); setPadding(32,0,32,12) })
            val surface=TextureView(this)
            root.addView(surface,LinearLayout.LayoutParams(-1,0,1f))
            root.addView(MaterialButton(this).apply { text="Back to circuit"; setOnClickListener { finish() } },LinearLayout.LayoutParams(-1,-2).apply { setMargins(24,12,24,24) })
            renderer=NativeBreadboardRenderer(surface,assets,circuit,previewMode=true)
        } catch (error: Exception) {
            MaterialAlertDialogBuilder(this).setTitle("Could not preview circuit").setMessage(error.message)
                .setPositiveButton("Back to circuit") { _,_->finish() }.setOnCancelListener { finish() }.show()
        }
    }
    override fun onResume() { super.onResume();renderer?.resume() }
    override fun onPause() { renderer?.pause();super.onPause() }
    override fun onDestroy() { renderer?.destroy();super.onDestroy() }
}

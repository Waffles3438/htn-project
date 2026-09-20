package com.htn.breadboardar.unity;

import android.os.Process;
import com.unity3d.player.UnityPlayerActivity;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Dedicated process lets Unity own the camera and cleanly exit without killing the designer. */
public class CircuitUnityActivity extends UnityPlayerActivity {
    public String getCircuitJson() throws Exception {
        File circuit = new File(getFilesDir(), "unity-circuit.json");
        if (!circuit.isFile() || circuit.length() > 1024 * 1024) throw new IllegalStateException("Circuit is missing or too large");
        return new String(Files.readAllBytes(circuit.toPath()), StandardCharsets.UTF_8);
    }
    public void returnToCircuit() { runOnUiThread(() -> { finish(); }); }
    @Override public void onBackPressed() { returnToCircuit(); }
    @Override protected void onDestroy() {
        super.onDestroy();
        // Unity is isolated in :unity. Each new launch loads exactly the latest private JSON.
        Process.killProcess(Process.myPid());
    }
    @Override public void onUnityPlayerUnloaded() { returnToCircuit(); }
}

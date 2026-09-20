using System;
using System.Collections.Generic;
using System.IO;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.XR.ARFoundation;
using UnityEngine.XR.ARSubsystems;

namespace CircuitXR
{
    public class CircuitArController : MonoBehaviour
    {
        public ARRaycastManager raycasts;
        public ARAnchorManager anchors;
        public CircuitBuilder builder;
        public Camera arCamera;
        private readonly List<ARRaycastHit> hits = new List<ARRaycastHit>();
        private readonly List<Vector3> points = new List<Vector3>();
        private readonly List<GameObject> markers = new List<GameObject>();
        private ARAnchor anchor;
        private bool placing;
        private bool loaded;
        private bool tracking;
        private string status = "Move your phone slowly over the board to find its surface.";
        private string error;
        private bool rightSide = true;
        private CircuitDefinition circuit;
        private int step = -1;
        private float guiScale;
        private GUIStyle heading, body, button, panel;
        private Texture2D panelTexture, buttonTexture;

        private void Start()
        {
            Screen.orientation = ScreenOrientation.Portrait;
            Application.targetFrameRate = 60;
            LoadCircuit();
        }
        private void LoadCircuit()
        {
            try {
#if UNITY_ANDROID && !UNITY_EDITOR
                using (var unity = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
                using (var activity = unity.GetStatic<AndroidJavaObject>("currentActivity")) {
                    circuit = CircuitDefinition.FromJson(activity.Call<string>("getCircuitJson"));
                }
#else
                circuit = CircuitDefinition.FromJson(Resources.Load<TextAsset>("demo-circuit").text);
#endif
                var result = builder.Build(circuit);
                if (!result.Success) throw new Exception(string.Join("\n", result.Errors));
                builder.boardRoot.gameObject.SetActive(false); loaded = true;
            } catch (Exception e) { error = "Could not open this circuit: " + e.Message; }
        }
        private void Update()
        {
            tracking = ARSession.state == ARSessionState.SessionTracking;
            if (!tracking) {
                if (builder.boardRoot) builder.boardRoot.gameObject.SetActive(false);
                return;
            }
            if (anchor && !placing) builder.boardRoot.gameObject.SetActive(anchor.trackingState == TrackingState.Tracking);
            if (!loaded || anchor || placing || points.Count >= 3) return;
            var touch = Touchscreen.current;
            if (touch == null || !touch.primaryTouch.press.wasPressedThisFrame) return;
            var pixel = touch.primaryTouch.position.ReadValue();
            var top = Screen.height - pixel.y;
            if (top < 170 * guiScale || top > Screen.height - 180 * guiScale) return;
            if (!raycasts.Raycast(pixel, hits, TrackableType.PlaneWithinPolygon)) {
                status = "Surface not found there. Move slowly and try the labeled hole again."; return;
            }
            points.Add(hits[0].pose.position);
            var marker = GameObject.CreatePrimitive(PrimitiveType.Sphere);
            marker.transform.position = hits[0].pose.position; marker.transform.localScale = Vector3.one * .002f;
            Destroy(marker.GetComponent<Collider>()); markers.Add(marker);
            if (points.Count == 3) Place();
        }
        private async void Place()
        {
            placing = true;
            try {
                var pose = BoardCalibration.FromReferences(points[0], points[1], points[2]);
                var result = await anchors.TryAddAnchorAsync(pose);
                if (!this) return;
                if (!result.status.IsSuccess()) throw new Exception("Couldn't anchor this surface. Move slowly and try again.");
                anchor = result.value;
                builder.boardRoot.SetParent(anchor.transform, false);
                builder.boardRoot.localRotation = Quaternion.identity;
                ApplySide(); builder.boardRoot.gameObject.SetActive(true);
                status = "Your circuit is beside the physical board. Keep the board still.";
                ClearMarkers();
            } catch (Exception e) { error = e.Message; ResetPlacement(); }
            finally { placing = false; }
        }
        private void ApplySide() { builder.boardRoot.localPosition = new Vector3(rightSide ? .15f : -.09f, 0, 0); }
        private void ClearMarkers() { foreach (var m in markers) if (m) Destroy(m); markers.Clear(); }
        private void ResetPlacement()
        {
            builder.boardRoot.gameObject.SetActive(false); builder.boardRoot.SetParent(null, false);
            if (anchor) Destroy(anchor.gameObject); anchor = null;
            points.Clear(); ClearMarkers(); step = -1; ApplyStep();
            status = "Tap the center of A1, then J1, then A63. Keep the board flat and still.";
        }
        private void ApplyStep()
        {
            if (circuit == null) return;
            foreach (var part in builder.boardRoot.GetComponentsInChildren<CircuitPart>(true))
                part.gameObject.SetActive(step < 0 || part.buildStep <= circuit.Instructions[step].Step);
        }
        private void OnGUI()
        {
            guiScale = Mathf.Max(1, Screen.width / 390f);
            GUI.matrix = Matrix4x4.Scale(Vector3.one * guiScale);
            var w = Screen.width / guiScale; var h = Screen.height / guiScale;
            if (heading == null) {
                panelTexture = new Texture2D(1,1); panelTexture.SetPixel(0,0,new Color(.97f,.97f,.94f,.96f)); panelTexture.Apply();
                buttonTexture = new Texture2D(1,1); buttonTexture.SetPixel(0,0,new Color(.14f,.35f,.25f,1)); buttonTexture.Apply();
                panel = new GUIStyle(GUI.skin.box); panel.normal.background = panelTexture;
                heading = new GUIStyle(GUI.skin.label) { fontSize = 23, fontStyle = FontStyle.Bold, wordWrap = true };
                body = new GUIStyle(GUI.skin.label) { fontSize = 15, wordWrap = true };
                button = new GUIStyle(GUI.skin.button) { fontSize = 14, wordWrap = true };
                heading.normal.textColor = new Color(.09f,.25f,.19f);
                body.normal.textColor = new Color(.22f,.33f,.27f);
                button.normal.background = buttonTexture; button.active.background = buttonTexture;
                button.normal.textColor = Color.white; button.active.textColor = Color.white;
            }
            GUI.Box(new Rect(12, 24, w - 24, 136), GUIContent.none, panel);
            GUI.Label(new Rect(24, 34, w-48, 34), anchor ? "Your circuit, in place" : "Find your breadboard", heading);
            var trackingText = ARSession.state == ARSessionState.Unsupported ? "This device does not support AR." : "Move the phone slowly. Waiting for surface tracking…";
            GUI.Label(new Rect(24, 76, w-48, 75), error ?? (!tracking ? trackingText : status), body);
            GUI.Box(new Rect(12, h-178, w-24, 166), GUIContent.none, panel);
            if (!anchor) {
                var target = points.Count == 0 ? "A1" : points.Count == 1 ? "J1" : "A63";
                GUI.Label(new Rect(24,h-168,w-48,56), placing ? "Saving position…" : $"{points.Count+1} / 3   Tap {target}\nA1 → J1: across letters. A1 → A63: along rows.", body);
                GUI.enabled = !placing;
                if (GUI.Button(new Rect(24,h-94,(w-60)/2,48), "Start again", button)) { error = null; ResetPlacement(); }
                if (GUI.Button(new Rect(w/2+6,h-94,(w-60)/2,48), "Back to circuit", button)) Back();
            } else {
                var instruction = step >= 0 ? circuit.Instructions[step].Text : "Completed view · power connects last.";
                GUI.Label(new Rect(24,h-168,w-48,68), instruction, body);
                if (GUI.Button(new Rect(24,h-94,(w-72)/3,40), "Move side", button)) { rightSide = !rightSide; ApplySide(); }
                if (GUI.Button(new Rect(36+(w-72)/3,h-94,(w-72)/3,40), step == circuit.Instructions.Count-1 ? "Show all" : "Next step", button)) { step = step == circuit.Instructions.Count-1 ? -1 : step+1; ApplyStep(); }
                if (GUI.Button(new Rect(48+2*(w-72)/3,h-94,(w-72)/3,40), "Reposition", button)) { error = null; ResetPlacement(); }
                if (GUI.Button(new Rect(24,h-48,w-48,30), "Back to circuit", button)) Back();
            }
            GUI.enabled = true;
        }
        private void Back()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            using (var unity = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (var activity = unity.GetStatic<AndroidJavaObject>("currentActivity")) activity.Call("returnToCircuit");
#endif
        }
        private void OnDestroy() { ClearMarkers(); if (panelTexture) Destroy(panelTexture); if (buttonTexture) Destroy(buttonTexture); }
    }
}

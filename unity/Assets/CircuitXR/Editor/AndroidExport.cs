using System;
using System.IO;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEditor.SceneManagement;
using UnityEditor.XR.Management;
using UnityEditor.Android;
using UnityEditor.XR.ARCore;
using UnityEditor.XR.Management.Metadata;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.InputSystem.XR;
using UnityEngine.XR.ARFoundation;
using UnityEngine.XR.Management;
using Unity.XR.CoreUtils;

namespace CircuitXR.Editor
{
    public static class AndroidExport
    {
        [MenuItem("Circuit/Prepare Android scene")]
        public static void Prepare()
        {
            Directory.CreateDirectory("Assets/CircuitXR/Generated");
            AssetDatabase.Refresh();
            var catalogPath = "Assets/CircuitXR/Generated/Catalog.asset";
            var catalog = AssetDatabase.LoadAssetAtPath<CircuitPrefabCatalog>(catalogPath);
            if (!catalog) { catalog = ScriptableObject.CreateInstance<CircuitPrefabCatalog>(); AssetDatabase.CreateAsset(catalog, catalogPath); }
            catalog.surfaceShader = Shader.Find("Standard");
            catalog.labelFont = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
            if (!catalog.surfaceShader || !catalog.labelFont) throw new Exception("Required rendering resources are missing.");
            catalog.breadboardComponents.Clear();
            foreach (var pair in new[]{("led","Led"),("resistor","Resistor"),("button","Pushbutton")}) {
                var prefab = AssetDatabase.LoadAssetAtPath<GameObject>("Assets/Prefabs/"+pair.Item2+".prefab");
                if (!prefab) throw new Exception("Missing team prefab: " + pair.Item2);
                catalog.breadboardComponents.Add(new CircuitPrefabCatalog.BreadboardPrefab { componentType = pair.Item1, prefab = prefab });
            }
            EditorUtility.SetDirty(catalog);
            var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);
            var session = new GameObject("AR Session"); session.AddComponent<ARSession>();
            var originGo = new GameObject("XR Origin"); var origin = originGo.AddComponent<XROrigin>();
            var offset = new GameObject("Camera Offset"); offset.transform.SetParent(originGo.transform, false); origin.CameraFloorOffsetObject = offset;
            var cameraGo = new GameObject("AR Camera"); cameraGo.tag = "MainCamera"; cameraGo.transform.SetParent(offset.transform, false);
            var camera = cameraGo.AddComponent<Camera>(); camera.nearClipPlane = .01f; camera.farClipPlane = 20;
            camera.clearFlags = CameraClearFlags.SolidColor; camera.backgroundColor = Color.black;
            origin.Camera = camera; origin.RequestedTrackingOriginMode = XROrigin.TrackingOriginMode.Device;
            cameraGo.AddComponent<ARCameraManager>(); cameraGo.AddComponent<ARCameraBackground>();
            var driver = cameraGo.AddComponent<TrackedPoseDriver>();
            driver.positionInput = new InputActionProperty(new InputAction("Position", binding:"<XRHMD>/centerEyePosition"));
            driver.rotationInput = new InputActionProperty(new InputAction("Rotation", binding:"<XRHMD>/centerEyeRotation"));
            driver.trackingStateInput = new InputActionProperty(new InputAction("Tracking State", binding:"<XRHMD>/trackingState"));
            originGo.AddComponent<ARPlaneManager>().requestedDetectionMode = UnityEngine.XR.ARSubsystems.PlaneDetectionMode.Horizontal;
            var raycasts = originGo.AddComponent<ARRaycastManager>(); var anchors = originGo.AddComponent<ARAnchorManager>();
            var root = new GameObject("BoardRoot").transform;
            var controllerGo = new GameObject("CircuitRuntime");
            var builder = controllerGo.AddComponent<CircuitBuilder>(); builder.boardRoot = root; builder.catalog = catalog;
            builder.boardMapJson = AssetDatabase.LoadAssetAtPath<TextAsset>("Assets/CircuitXR/Resources/board-map.json");
            var controller = controllerGo.AddComponent<CircuitArController>(); controller.builder = builder; controller.raycasts = raycasts; controller.anchors = anchors; controller.arCamera = camera;
            var light = new GameObject("Light").AddComponent<Light>(); light.type = LightType.Directional; light.intensity = 1; light.transform.rotation = Quaternion.Euler(50,-30,0);
            RenderSettings.ambientLight = new Color(.65f,.65f,.65f);
            const string scenePath = "Assets/CircuitXR/Generated/AndroidAR.unity";
            EditorSceneManager.SaveScene(scene, scenePath);
            EditorBuildSettings.scenes = new[]{new EditorBuildSettingsScene(scenePath, true)};

            var xrPath = "Assets/CircuitXR/Generated/XRSettings.asset";
            var xr = AssetDatabase.LoadAssetAtPath<XRGeneralSettingsPerBuildTarget>(xrPath);
            if (!xr) { xr = ScriptableObject.CreateInstance<XRGeneralSettingsPerBuildTarget>(); AssetDatabase.CreateAsset(xr, xrPath); }
            EditorBuildSettings.AddConfigObject(XRGeneralSettings.k_SettingsKey, xr, true);
            if (!xr.HasSettingsForBuildTarget(BuildTargetGroup.Android)) xr.CreateDefaultSettingsForBuildTarget(BuildTargetGroup.Android);
            if (!xr.HasManagerSettingsForBuildTarget(BuildTargetGroup.Android)) xr.CreateDefaultManagerSettingsForBuildTarget(BuildTargetGroup.Android);
            var general = xr.SettingsForBuildTarget(BuildTargetGroup.Android); general.InitManagerOnStart = true;
            if (!XRPackageMetadataStore.AssignLoader(general.Manager, "UnityEngine.XR.ARCore.ARCoreLoader", BuildTargetGroup.Android)) throw new Exception("ARCore loader assignment failed.");
            EditorUtility.SetDirty(xr); EditorUtility.SetDirty(general);
            var arCorePath = "Assets/CircuitXR/Generated/ARCoreSettings.asset";
            var arCore = AssetDatabase.LoadAssetAtPath<ARCoreSettings>(arCorePath);
            if (!arCore) { arCore = ScriptableObject.CreateInstance<ARCoreSettings>(); AssetDatabase.CreateAsset(arCore, arCorePath); }
            arCore.requirement = ARCoreSettings.Requirement.Optional;
            arCore.depth = ARCoreSettings.Requirement.Optional;
            ARCoreSettings.currentSettings = arCore;
            EditorUtility.SetDirty(arCore);
            PlayerSettings.SetApplicationIdentifier(NamedBuildTarget.Android, "com.htn.breadboardar");
            PlayerSettings.productName = "Circuit";
            PlayerSettings.defaultInterfaceOrientation = UIOrientation.Portrait;
            PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel26;
            PlayerSettings.Android.targetSdkVersion = AndroidSdkVersions.AndroidApiLevel35;
            PlayerSettings.Android.applicationEntry = AndroidApplicationEntry.Activity;
            PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;
            PlayerSettings.SetScriptingBackend(NamedBuildTarget.Android, ScriptingImplementation.IL2CPP);
            PlayerSettings.SetGraphicsAPIs(BuildTarget.Android, new[]{UnityEngine.Rendering.GraphicsDeviceType.OpenGLES3});
            // New input system drives both touchscreen calibration and XR camera poses.
            var player = new SerializedObject(AssetDatabase.LoadAllAssetsAtPath("ProjectSettings/ProjectSettings.asset")[0]);
            player.FindProperty("activeInputHandler").intValue = 1; player.ApplyModifiedPropertiesWithoutUndo();
            AssetDatabase.SaveAssets();
        }
        [MenuItem("Circuit/Render circuit preview")]
        public static void RenderPreview()
        {
            Prepare();
            var builder = UnityEngine.Object.FindFirstObjectByType<CircuitBuilder>();
            var result = builder.BuildFromJson(Resources.Load<TextAsset>("demo-circuit").text);
            if (!result.Success) throw new Exception(string.Join(",", result.Errors));
            var camera = new GameObject("Preview Camera").AddComponent<Camera>();
            camera.transform.position = new Vector3(.16f, .19f, -.025f);
            camera.transform.LookAt(new Vector3(.01f,0,.075f));
            camera.orthographic = true; camera.orthographicSize = .145f;
            camera.nearClipPlane = .01f; camera.farClipPlane = 2;
            camera.clearFlags = CameraClearFlags.SolidColor; camera.backgroundColor = new Color(.91f,.92f,.87f);
            var target = RenderTexture.GetTemporary(1000,1400,24);
            camera.targetTexture = target; camera.Render();
            RenderTexture.active = target;
            var image = new Texture2D(1000,1400,TextureFormat.RGB24,false);
            image.ReadPixels(new Rect(0,0,1000,1400),0,0); image.Apply();
            Directory.CreateDirectory("../build"); File.WriteAllBytes("../build/unity-circuit-preview.png", image.EncodeToPNG());
            RenderTexture.active = null; camera.targetTexture = null; RenderTexture.ReleaseTemporary(target);
            UnityEngine.Object.DestroyImmediate(image); UnityEngine.Object.DestroyImmediate(camera.gameObject);
        }
        [MenuItem("Circuit/Export Android library")]
        public static void Export()
        {
            var sdk = Environment.GetEnvironmentVariable("ANDROID_HOME");
            var jdk = Environment.GetEnvironmentVariable("JAVA_HOME");
            if (!string.IsNullOrEmpty(sdk)) {
                AndroidExternalToolsSettings.sdkRootPath = sdk;
                AndroidExternalToolsSettings.ndkRootPath = Path.Combine(sdk, "ndk/27.2.12479018");
            }
            if (!string.IsNullOrEmpty(jdk)) AndroidExternalToolsSettings.jdkRootPath = jdk;
            Prepare();
            EditorUserBuildSettings.exportAsGoogleAndroidProject = true;
            var output = Path.GetFullPath(Path.Combine(Application.dataPath, "../../unity-export"));
            var result = BuildPipeline.BuildPlayer(new BuildPlayerOptions { scenes = new[]{"Assets/CircuitXR/Generated/AndroidAR.unity"}, locationPathName = output, target = BuildTarget.Android, options = BuildOptions.AcceptExternalModificationsToPlayer });
            if (result.summary.result != BuildResult.Succeeded) throw new Exception("Unity Android export failed: " + result.summary.result);
        }
    }
}

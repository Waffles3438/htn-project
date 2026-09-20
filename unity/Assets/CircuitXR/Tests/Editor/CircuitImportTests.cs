#if UNITY_INCLUDE_TESTS
using System;
using NUnit.Framework;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEditor;

namespace CircuitXR.Tests
{
    public class CircuitImportTests
    {
        private string Map => AssetDatabase.LoadAssetAtPath<TextAsset>("Assets/CircuitXR/Resources/board-map.json").text;
        private string Fixture => AssetDatabase.LoadAssetAtPath<TextAsset>("Assets/CircuitXR/Resources/demo-circuit.json").text;
        [Test] public void LoadsObjectCoordinatesAndRailExtremes()
        {
            var map = new BoardMapResolver(Map);
            Assert.AreEqual(830, map.Holes.Count);
            Assert.IsTrue(map.TryResolveBoardAddress("BB1:RAIL:L:+:A:1", out var p));
            Assert.AreEqual(-.01016f, p.x, .000001f);
            Assert.AreEqual(.00508f, p.z, .000001f);
            Assert.IsTrue(map.TryResolveBoardAddress("BB1:RAIL:R:-:B:25", out p));
            Assert.AreEqual(.03810f, p.x, .000001f);
            Assert.AreEqual(.15240f, p.z, .000001f);
            Assert.IsFalse(map.TryResolveBoardAddress("BB1:RAIL:L:+:A", out _));
        }
        [Test] public void RejectsMissingValidation()
        {
            var json = JObject.Parse(Fixture); json.Remove("validation");
            Assert.Throws<CircuitImportException>(() => CircuitDefinition.FromJson(json.ToString()));
        }
        [Test] public void CalibrationKeepsMetersAndPositiveHeight()
        {
            var pose = BoardCalibration.FromReferences(Vector3.zero, new Vector3(.02794f,0,0), new Vector3(0,0,.15748f));
            Assert.Less(Vector3.Distance(pose.rotation * Vector3.up, Vector3.up), .00001f);
            Assert.Throws<ArgumentException>(() => BoardCalibration.FromReferences(Vector3.zero, Vector3.right, Vector3.forward));
            Assert.Throws<ArgumentException>(() => BoardCalibration.FromReferences(Vector3.zero, new Vector3(.02794f,0,0), new Vector3(0,0,-.15748f)));
        }
        [Test] public void EverySupportedFixtureBuildsWithBundledRenderingResources()
        {
            CircuitXR.Editor.AndroidExport.Prepare();
            var builder = UnityEngine.Object.FindFirstObjectByType<CircuitBuilder>();
            Assert.IsNotNull(builder.catalog.surfaceShader);
            Assert.IsNotNull(builder.catalog.labelFont);
            foreach (var name in new[] { "led", "button_led", "arduino_led" }) {
                var path = System.IO.Path.Combine(Application.dataPath, "../../app/src/main/assets/" + name + ".placement.json");
                var result = builder.BuildFromJson(System.IO.File.ReadAllText(path));
                Assert.IsTrue(result.Success, string.Join(",", result.Errors));
                Assert.AreEqual(1, builder.boardRoot.childCount);
            }
        }
        [Test] public void InvalidImportKeepsPreviousCircuitAndLocalWires()
        {
            CircuitXR.Editor.AndroidExport.Prepare();
            var builder = UnityEngine.Object.FindFirstObjectByType<CircuitBuilder>();
            var first = builder.BuildFromJson(Fixture);
            Assert.IsTrue(first.Success, string.Join(",", first.Errors));
            var previous = builder.boardRoot.GetChild(0);
            var json = JObject.Parse(Fixture); json["jumperWires"][0]["to"] = "BB1:A99";
            Assert.IsFalse(builder.BuildFromJson(json.ToString()).Success);
            Assert.AreSame(previous, builder.boardRoot.GetChild(0));
            builder.boardRoot.SetPositionAndRotation(new Vector3(1,2,3), Quaternion.Euler(0,45,0));
            foreach (var line in previous.GetComponentsInChildren<LineRenderer>()) {
                Assert.IsFalse(line.useWorldSpace);
                Assert.IsTrue(line.transform.IsChildOf(builder.boardRoot));
            }
        }
    }
}
#endif

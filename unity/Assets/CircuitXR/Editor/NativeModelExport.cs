using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Newtonsoft.Json;
using UnityEditor;
using UnityEngine;

namespace CircuitXR.Editor
{
    /// <summary>Exports the team's actual prefab meshes for the native Filament renderer.</summary>
    public static class NativeModelExport
    {
        [MenuItem("Circuit/Export native component meshes")]
        public static void Export()
        {
            var models = new Dictionary<string, object>();
            foreach (var item in new[] { ("led", "Led", .012f), ("resistor", "Resistor", .014f), ("button", "Pushbutton", .010f) }) {
                var prefab = AssetDatabase.LoadAssetAtPath<GameObject>($"Assets/Prefabs/{item.Item2}.prefab");
                if (!prefab) throw new Exception("Missing team prefab: " + item.Item2);
                var instance = UnityEngine.Object.Instantiate(prefab);
                try {
                    instance.transform.SetPositionAndRotation(Vector3.zero, Quaternion.identity);
                    instance.transform.localScale = Vector3.one;
                    var filters = instance.GetComponentsInChildren<MeshFilter>();
                    var all = filters.SelectMany(f => f.sharedMesh.vertices.Select(v => f.transform.TransformPoint(v))).ToArray();
                    var bounds = new Bounds(all[0], Vector3.zero);
                    foreach (var v in all) bounds.Encapsulate(v);
                    var factor = item.Item3 / Mathf.Max(bounds.size.x, bounds.size.y, bounds.size.z);
                    var center = new Vector3(bounds.center.x, bounds.min.y, bounds.center.z);
                    var primitives = new List<object>();
                    foreach (var filter in filters) {
                        var mesh = filter.sharedMesh;
                        var positions = mesh.vertices.Select(v => (filter.transform.TransformPoint(v) - center) * factor).ToArray();
                        var normalMatrix = filter.transform.localToWorldMatrix.inverse.transpose;
                        var normals = mesh.normals.Select(n => normalMatrix.MultiplyVector(n).normalized).ToArray();
                        var materials = filter.GetComponent<Renderer>().sharedMaterials;
                        for (int sub = 0; sub < mesh.subMeshCount; sub++) {
                            var mat = materials[Math.Min(sub, materials.Length - 1)];
                            var color = mat.HasProperty("_BaseColor") ? mat.GetColor("_BaseColor") : mat.HasProperty("_Color") ? mat.color : Color.gray;
                            // glTF is right-handed. Reflect Z and reverse the triangle winding.
                            var indices = mesh.GetTriangles(sub);
                            for (int t = 0; t < indices.Length; t += 3) { var swap = indices[t]; indices[t] = indices[t+2]; indices[t+2] = swap; }
                            primitives.Add(new {
                                positions = positions.SelectMany(v => new[] {v.x, v.y, -v.z}).ToArray(),
                                normals = normals.SelectMany(n => new[] {n.x, n.y, -n.z}).ToArray(),
                                indices, color = new[] {color.r, color.g, color.b, 1f}, name = mat.name,
                                metallic = mat.HasProperty("_Metallic") ? mat.GetFloat("_Metallic") : 0f,
                            });
                        }
                    }
                    models[item.Item1] = new { source = $"Assets/Prefabs/{item.Item2}.prefab", height = bounds.size.y * factor, primitives };
                    Debug.Log($"Native model {item.Item1}: {all.Length} vertices, source bounds {bounds.size}, height {bounds.size.y * factor}");
                } finally { UnityEngine.Object.DestroyImmediate(instance); }
            }
            var output = Path.GetFullPath(Path.Combine(Application.dataPath, "../../app/src/main/assets/models/components.json"));
            File.WriteAllText(output, JsonConvert.SerializeObject(new { version = 1, units = "meters", models }));
            Debug.Log("Native component meshes exported: " + output);
        }
    }
}

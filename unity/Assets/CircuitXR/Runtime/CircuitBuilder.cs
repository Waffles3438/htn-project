using System;
using System.Collections.Generic;
using System.Linq;
using UnityEngine;

namespace CircuitXR
{
    public class CircuitBuildResult
    {
        public bool Success;
        public readonly List<string> Errors = new List<string>();
        public readonly List<GameObject> Spawned = new List<GameObject>();
    }

    /// <summary>Transactional import. Every endpoint resolves before replacing the visible circuit.</summary>
    public class CircuitBuilder : MonoBehaviour
    {
        public Transform boardRoot;
        public TextAsset boardMapJson;
        public CircuitPrefabCatalog catalog;
        public CircuitBuildResult LastResult { get; private set; }
        private GameObject generated;
        private readonly Dictionary<string, Material> materials = new Dictionary<string, Material>();
        public static readonly Dictionary<string, string[]> Terminals = new Dictionary<string, string[]> {
            {"led", new[]{"anode", "cathode"}}, {"resistor", new[]{"a", "b"}},
            {"button", new[]{"a1", "a2", "b1", "b2"}}, {"power_supply", new[]{"positive", "negative"}}
        };

        public CircuitBuildResult BuildFromJson(string json)
        {
            try { return Build(CircuitDefinition.FromJson(json)); }
            catch (Exception e) { return Failed(e.Message); }
        }
        private CircuitBuildResult Failed(string message)
        {
            LastResult = new CircuitBuildResult(); LastResult.Errors.Add(message);
            Debug.LogWarning("[CircuitXR] " + message);
            return LastResult;
        }
        public CircuitBuildResult Build(CircuitDefinition circuit)
        {
            GameObject staged = null;
            try {
                circuit.ValidateContract();
                if (!boardRoot || !boardMapJson) throw new CircuitImportException("Board root/map is missing.");
                var map = new BoardMapResolver(boardMapJson.text);
                if (map.HoleMapVersion != circuit.Board.HoleMapVersion) throw new CircuitImportException("Circuit and board map versions differ.");
                var ids = new HashSet<string>();
                var endpoints = new Dictionary<string, Vector3>();
                var occupied = new HashSet<string>();
                Vector3 Hole(string address) {
                    if (!map.TryResolveBoardAddress(address, out var p)) throw new CircuitImportException("Invalid hole: " + address);
                    return p;
                }
                foreach (var c in circuit.Components) {
                    if (c == null || string.IsNullOrWhiteSpace(c.Id) || !ids.Add(c.Id) || !Terminals.ContainsKey(c.Type)) throw new CircuitImportException("Unknown or duplicate part.");
                    if (c.Mount == null || !c.Mount.IsBreadboard || c.Mount.Board != "BB1" || c.Mount.Terminals == null || !new HashSet<string>(c.Mount.Terminals.Keys).SetEquals(Terminals[c.Type]))
                        throw new CircuitImportException("Invalid terminal map: " + c.Id);
                    if (c.Type != "power_supply" && (catalog == null || catalog.GetBreadboard(c.Type)?.prefab == null)) throw new CircuitImportException("Missing model: " + c.Type);
                    foreach (var pin in c.Mount.Terminals) {
                        if (!occupied.Add(pin.Value)) throw new CircuitImportException("Two leads occupy " + pin.Value);
                        endpoints.Add(c.Id + ":" + pin.Key, Hole(pin.Value));
                    }
                }
                foreach (var d in circuit.ExternalDevices ?? new List<ExternalDeviceDefinition>()) {
                    if (d == null || !ids.Add(d.Id) || d.Type != "arduino_uno" || d.AssetId != "arduino_uno_r3_v1" || d.Mount == null || !d.Mount.IsExternal || d.Mount.RelativeTo != "BB1" || d.Mount.Side != "left")
                        throw new CircuitImportException("Unsupported external device/mount.");
                    // Schematic Uno proxy, explicitly separate from measured prefab pin geometry.
                    endpoints.Add(d.Id + ":D13", new Vector3(-.024f, .003f, .025f));
                    endpoints.Add(d.Id + ":GND", new Vector3(-.024f, .003f, .033f));
                }
                Vector3 Endpoint(string value) {
                    if (value != null && endpoints.TryGetValue(value, out var p)) return p;
                    return Hole(value);
                }
                foreach (var w in circuit.JumperWires) {
                    if (w == null || string.IsNullOrWhiteSpace(w.Id) || !ids.Add(w.Id)) throw new CircuitImportException("Duplicate wire.");
                    Endpoint(w.From); Endpoint(w.To);
                }
                staged = new GameObject("GeneratedCircuit"); staged.SetActive(false);
                staged.transform.SetParent(boardRoot, false);
                DrawBoard(staged.transform, map);
                var result = new CircuitBuildResult();
                foreach (var c in circuit.Components) {
                    var part = new GameObject(c.Id); part.transform.SetParent(staged.transform, false);
                    var points = Terminals[c.Type].Select(pin => endpoints[c.Id + ":" + pin]).ToArray();
                    var center = points.Aggregate(Vector3.zero, (sum, p) => sum + p) / points.Length;
                    if (c.Type != "power_supply") {
                        // Team meshes are artwork. Normalize one uniform scale; pin guides remain exact.
                        var visual = Instantiate(catalog.GetBreadboard(c.Type).prefab, part.transform);
                        visual.transform.localPosition = Vector3.zero; visual.transform.localRotation = Quaternion.identity;
                        visual.transform.localScale = Vector3.one;
                        FitArtwork(visual, center + Vector3.up * .003f, c.Type == "button" ? .006f : c.Type == "resistor" ? .007f : .005f);
                    }
                    for (int i = 0; i < points.Length; i++) {
                        var pin = points[i];
                        Line(part.transform, "Pin " + Terminals[c.Type][i], pin, c.Type == "power_supply" ? pin + Vector3.up * .004f : center + Vector3.up * .003f, "silver", false);
                        Cube(part.transform, "Hole guide", pin + Vector3.up * .0005f, Vector3.one * .0013f, c.Type == "power_supply" && i == 0 ? "red" : "green");
                    }
                    Metadata(part, c.Id, c.BuildStep); result.Spawned.Add(part);
                }
                foreach (var d in circuit.ExternalDevices ?? new List<ExternalDeviceDefinition>()) {
                    var body = Cube(staged.transform, d.Id, new Vector3(-.0525f, .001f, .0385f), new Vector3(.059f, .002f, .049f), "teal");
                    Label(body.transform, "UNO R3", new Vector3(0, .002f, 0), .005f);
                    Label(staged.transform, "D13", endpoints[d.Id + ":D13"] + Vector3.up * .001f, .002f);
                    Label(staged.transform, "GND", endpoints[d.Id + ":GND"] + Vector3.up * .001f, .002f);
                }
                foreach (var w in circuit.JumperWires) {
                    var wire = Line(staged.transform, w.Id, Endpoint(w.From), Endpoint(w.To), w.Color, true);
                    Metadata(wire, w.Id, w.BuildStep); result.Spawned.Add(wire);
                }
                var previous = generated;
                generated = staged; generated.SetActive(true); staged = null;
                if (previous) { previous.SetActive(false); Remove(previous); }
                result.Success = true; LastResult = result; return result;
            } catch (Exception e) {
                if (staged) Remove(staged);
                return Failed(e.Message);
            }
        }
        private void FitArtwork(GameObject visual, Vector3 center, float extent)
        {
            var renderers = visual.GetComponentsInChildren<Renderer>();
            if (renderers.Length == 0) throw new CircuitImportException("Prefab has no renderable mesh.");
            var local = new Bounds(); var first = true;
            foreach (var renderer in renderers) {
                var b = renderer.localBounds;
                for (int i = 0; i < 8; i++) {
                    var corner = b.center + Vector3.Scale(b.extents, new Vector3((i&1)==0?-1:1, (i&2)==0?-1:1, (i&4)==0?-1:1));
                    var p = visual.transform.InverseTransformPoint(renderer.transform.TransformPoint(corner));
                    if (first) { local = new Bounds(p, Vector3.zero); first = false; } else local.Encapsulate(p);
                }
                // Source materials use URP; the mobile scene uses the built-in pipeline.
                renderer.sharedMaterials = renderer.sharedMaterials.Select(m => {
                    var color = m && m.HasProperty("_BaseColor") ? m.GetColor("_BaseColor") : Color.gray;
                    var key = "mesh-" + ColorUtility.ToHtmlStringRGBA(color);
                    if (!materials.ContainsKey(key)) materials[key] = new Material(catalog.surfaceShader ? catalog.surfaceShader : Shader.Find("Standard")) { color = color };
                    return materials[key];
                }).ToArray();
            }
            var max = Mathf.Max(local.size.x, local.size.y, local.size.z);
            if (max < .000001f) throw new CircuitImportException("Empty prefab geometry.");
            var factor = extent / max;
            visual.transform.localScale = Vector3.one * factor;
            visual.transform.localPosition = center - local.center * factor;
        }
        private void DrawBoard(Transform parent, BoardMapResolver map)
        {
            Cube(parent, "Breadboard", new Vector3(.01397f, -.003f, .07874f), new Vector3(.058f, .006f, .174f), "ivory");
            Cube(parent, "Center channel", new Vector3(.01397f, .0001f, .07874f), new Vector3(.003f, .0002f, .16f), "gray");
            // One combined mesh for 830 hole marks; no 830 per-frame draw calls.
            var temp = GameObject.CreatePrimitive(PrimitiveType.Cube);
            var mesh = temp.GetComponent<MeshFilter>().sharedMesh;
            var combine = map.Holes.Values.Select(p => new CombineInstance { mesh = mesh, transform = Matrix4x4.TRS(p + Vector3.up * .0002f, Quaternion.identity, new Vector3(.0008f, .0003f, .0008f)) }).ToArray();
            var marks = new GameObject("Hole marks", typeof(MeshFilter), typeof(MeshRenderer)); marks.transform.SetParent(parent, false);
            var combined = new Mesh { name = "Board holes" }; combined.CombineMeshes(combine);
            marks.GetComponent<MeshFilter>().sharedMesh = combined; marks.GetComponent<MeshRenderer>().sharedMaterial = Material("gray");
            marks.AddComponent<OwnedMesh>().mesh = combined;
            Remove(temp);
            Label(parent, "A1", new Vector3(-.003f, .0006f, -.002f), .002f);
            Label(parent, "J1", new Vector3(.02794f, .0006f, -.002f), .002f);
            Label(parent, "A63", new Vector3(-.003f, .0006f, .160f), .002f);
        }
        private GameObject Cube(Transform parent, string name, Vector3 position, Vector3 size, string color)
        {
            // Geometry child keeps the wrapper scale at one, so pin/label children use meters.
            var root = new GameObject(name); root.transform.SetParent(parent, false); root.transform.localPosition = position;
            var cube = GameObject.CreatePrimitive(PrimitiveType.Cube); cube.transform.SetParent(root.transform, false); cube.transform.localScale = size;
            Remove(cube.GetComponent<Collider>()); cube.GetComponent<Renderer>().sharedMaterial = Material(color); return root;
        }
        private GameObject Line(Transform parent, string name, Vector3 from, Vector3 to, string color, bool arc)
        {
            var go = new GameObject(name); go.transform.SetParent(parent, false);
            var line = go.AddComponent<LineRenderer>(); line.useWorldSpace = false;
            line.sharedMaterial = Material(color); line.widthMultiplier = arc ? .0009f : .0005f;
            line.positionCount = arc ? 24 : 2; line.numCapVertices = 3;
            for (int i = 0; i < line.positionCount; i++) {
                var t = i / (float)(line.positionCount-1);
                line.SetPosition(i, Vector3.Lerp(from, to, t) + Vector3.up * (arc ? .008f * 4 * t * (1-t) : 0));
            }
            return go;
        }
        private void Label(Transform parent, string text, Vector3 at, float size)
        {
            var go = new GameObject(text); go.transform.SetParent(parent, false); go.transform.localPosition = at;
            go.transform.localRotation = Quaternion.Euler(90, 0, 0);
            var label = go.AddComponent<TextMesh>(); label.font = catalog.labelFont; go.GetComponent<MeshRenderer>().sharedMaterial = catalog.labelFont.material; label.text = text; label.characterSize = size; label.fontSize = 64; label.anchor = TextAnchor.MiddleCenter; label.color = Color.black;
        }
        private Material Material(string name)
        {
            if (materials.TryGetValue(name, out var result)) return result;
            var colors = new Dictionary<string, Color> { {"red",new Color(.8f,.15f,.12f)}, {"yellow",new Color(.85f,.63f,.08f)}, {"green",new Color(.18f,.5f,.3f)}, {"blue",Color.blue}, {"silver",new Color(.6f,.65f,.64f)}, {"ivory",new Color(.94f,.93f,.86f)}, {"gray",new Color(.24f,.28f,.24f)}, {"teal",new Color(.06f,.4f,.43f)} };
            result = new Material(catalog.surfaceShader ? catalog.surfaceShader : Shader.Find("Standard")) { color = colors.TryGetValue(name, out var color) ? color : new Color(.06f,.08f,.07f) };
            materials[name] = result; return result;
        }
        private void Metadata(GameObject go, string id, int step) { var p = go.AddComponent<CircuitPart>(); p.id = id; p.buildStep = step; }
        private static void Remove(UnityEngine.Object value) {
            if (!value) return;
            if (Application.isPlaying) Destroy(value); else DestroyImmediate(value);
        }
        private void OnDestroy() { foreach (var material in materials.Values) if (material) Remove(material); }
    }
    public class CircuitPart : MonoBehaviour { public string id; public int buildStep; }
    public class OwnedMesh : MonoBehaviour { public Mesh mesh; private void OnDestroy() { if (mesh) { if (Application.isPlaying) Destroy(mesh); else DestroyImmediate(mesh); } } }
}

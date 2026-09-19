using UnityEngine;
using System.Collections.Generic;

[System.Serializable]
public struct AssemblyStep
{
    public string localPinName;
    public string targetGridPin;
    public Vector3 worldPosition;
    public string commandText;
}

[System.Serializable]
public struct PinMapping
{
    public string localPinName;
    public string gridPin;
}

public class objectPlacer : MonoBehaviour
{
    [System.Serializable]
    public struct ComponentDefinition
    {
        public string typeKey;
        public GameObject prefab;
        public List<string> orderedLocalPins;
    }

    [Header("References")]
    public GridMapper gridMapper;

    [Header("Component Catalog")]
    public List<ComponentDefinition> catalogDefinitions = new List<ComponentDefinition>();
    private Dictionary<string, ComponentDefinition> _catalogDictionary;

    [ContextMenu("Auto-Generate Catalog Skeleton")]
    public void AutoGenerateCatalog()
    {
        catalogDefinitions.Clear();

        catalogDefinitions.Add(new ComponentDefinition { typeKey = "Capacitor", orderedLocalPins = new List<string> { "Pin1", "Pin2" } });
        catalogDefinitions.Add(new ComponentDefinition { typeKey = "Diode", orderedLocalPins = new List<string> { "Pin1", "Pin2" } });
        catalogDefinitions.Add(new ComponentDefinition { typeKey = "Led", orderedLocalPins = new List<string> { "Pin1", "Pin2" } });
        catalogDefinitions.Add(new ComponentDefinition { typeKey = "MOSFET", orderedLocalPins = new List<string> { "Pin1", "Pin2", "Pin3" } });
        catalogDefinitions.Add(new ComponentDefinition { typeKey = "Pushbutton", orderedLocalPins = new List<string> { "Pin1", "Pin2", "Pin3", "Pin4" } });
        catalogDefinitions.Add(new ComponentDefinition { typeKey = "Resistor", orderedLocalPins = new List<string> { "Pin1", "Pin2" } });
        catalogDefinitions.Add(new ComponentDefinition { typeKey = "Thermistor", orderedLocalPins = new List<string> { "Pin1", "Pin2" } });

        Debug.Log("[objectPlacer] Generated 7 catalog slots! Now drag your 3D models into the empty 'Prefab' slots in the Inspector.");
    }

    private void Awake()
    {
        BuildCatalog();
    }

    public void BuildCatalog()
    {
        _catalogDictionary = new Dictionary<string, ComponentDefinition>();
        foreach (var def in catalogDefinitions)
        {
            if (!string.IsNullOrEmpty(def.typeKey) && def.prefab != null)
            {
                if (!_catalogDictionary.ContainsKey(def.typeKey))
                {
                    _catalogDictionary.Add(def.typeKey, def);
                }
            }
        }
    }

    public List<AssemblyStep> GeneratePinAssemblySteps(string typeKey, List<string> gridPins)
    {
        List<AssemblyStep> steps = new List<AssemblyStep>();
        if (_catalogDictionary == null || _catalogDictionary.Count == 0) BuildCatalog();

        if (!_catalogDictionary.TryGetValue(typeKey, out var def))
        {
            Debug.LogError($"[objectPlacer] Catalog missing typeKey '{typeKey}'");
            return steps;
        }

        if (def.orderedLocalPins == null || def.orderedLocalPins.Count != gridPins.Count)
        {
            Debug.LogError($"[objectPlacer] Pin count mismatch for '{typeKey}'. Expected {def.orderedLocalPins?.Count ?? 0}, got {gridPins.Count}");
            return steps;
        }

        for (int i = 0; i < def.orderedLocalPins.Count; i++)
        {
            string localPin = def.orderedLocalPins[i];
            string gridPin = gridPins[i];
            Vector3 worldPos = gridMapper != null ? gridMapper.GetPinWorldPosition(gridPin) : Vector3.zero;

            steps.Add(new AssemblyStep
            {
                localPinName = localPin,
                targetGridPin = gridPin,
                worldPosition = worldPos,
                commandText = $"Step {i + 1} ({typeKey}): Place [{localPin}] into slot [{gridPin}]"
            });
        }

        return steps;
    }

    public GameObject PlaceComponentByType(string typeKey, List<string> gridPins, bool logPerPinCommands = false)
    {
        var steps = GeneratePinAssemblySteps(typeKey, gridPins);
        if (steps.Count == 0) return null;

        if (logPerPinCommands)
        {
            foreach (var step in steps)
            {
                Debug.Log($"[Assembly Guide] {step.commandText} at WorldPos {step.worldPosition:F3}");
            }
        }

        List<PinMapping> mappings = new List<PinMapping>();
        foreach (var s in steps)
        {
            mappings.Add(new PinMapping { localPinName = s.localPinName, gridPin = s.targetGridPin });
        }

        if (!_catalogDictionary.TryGetValue(typeKey, out var def) || def.prefab == null) return null;

        GameObject obj = Instantiate(def.prefab, Vector3.zero, Quaternion.identity);
        ApplyNamedPinAlignment(obj, mappings);
        return obj;
    }

    private void ApplyNamedPinAlignment(GameObject obj, List<PinMapping> mappings)
    {
        if (gridMapper == null || mappings == null || mappings.Count == 0) return;
        
        Transform GetPin(string name) => FindPinRecursive(obj.transform, name);

        if (mappings.Count == 1)
        {
            Transform t = GetPin(mappings[0].localPinName);
            Vector3 target = gridMapper.GetPinWorldPosition(mappings[0].gridPin);
            obj.transform.rotation = Quaternion.identity;
            obj.transform.position += (t != null) ? (target - t.position) : target;
            return;
        }

        Transform t1 = GetPin(mappings[0].localPinName);
        Transform t2 = GetPin(mappings[1].localPinName);
        Vector3 target1 = gridMapper.GetPinWorldPosition(mappings[0].gridPin);
        Vector3 target2 = gridMapper.GetPinWorldPosition(mappings[1].gridPin);

        if (t1 != null && t2 != null)
        {
            Vector3 localDirWorld = obj.transform.TransformDirection(t2.localPosition - t1.localPosition);
            Vector3 worldDir = target2 - target1;

            if (localDirWorld.sqrMagnitude > 1e-6f && worldDir.sqrMagnitude > 1e-6f)
            {
                Quaternion rotDelta = Quaternion.FromToRotation(localDirWorld, worldDir);
                obj.transform.rotation = rotDelta * obj.transform.rotation;
            }
            
            obj.transform.position += (target1 - t1.position);
        }
        else
        {
            Debug.LogWarning($"[objectPlacer] Missing named pin(s) on prefab {obj.name}. Falling back to midpoint.");
            obj.transform.position = (target1 + target2) * 0.5f;
        }
    }

    private Transform FindPinRecursive(Transform parent, string pinName)
    {
        foreach (Transform child in parent)
        {
            if (string.Equals(child.name, pinName, System.StringComparison.OrdinalIgnoreCase))
                return child;
            
            Transform nested = FindPinRecursive(child, pinName);
            if (nested != null) return nested;
        }
        return null;
    }
}
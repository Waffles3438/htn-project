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

    [Header("Placement Settings")]
    [Tooltip("How far down (in Unity meters) to sink the components into the breadboard.")]
    public float insertionDepth = 0.005f; 

    [Header("Component Catalog")]
    public List<ComponentDefinition> catalogDefinitions = new List<ComponentDefinition>();
    private Dictionary<string, ComponentDefinition> _catalogDictionary;

    [ContextMenu("Auto-Generate Catalog Skeleton")]
    public void AutoGenerateCatalog()
    {
        catalogDefinitions.Clear();
        catalogDefinitions.Add(new ComponentDefinition { typeKey = "Capacitor", orderedLocalPins = new List<string> { "Cathode", "Anode" } });
        catalogDefinitions.Add(new ComponentDefinition { typeKey = "Diode", orderedLocalPins = new List<string> { "Cathode", "Anode" } });
        catalogDefinitions.Add(new ComponentDefinition { typeKey = "Led", orderedLocalPins = new List<string> { "Cathode", "Anode" } });
        catalogDefinitions.Add(new ComponentDefinition { typeKey = "MOSFET", orderedLocalPins = new List<string> { "Base", "Collector", "Emitter" } });
        catalogDefinitions.Add(new ComponentDefinition { typeKey = "Pushbutton", orderedLocalPins = new List<string> { "Cathode", "Anode", "Output" } });
        catalogDefinitions.Add(new ComponentDefinition { typeKey = "Resistor", orderedLocalPins = new List<string> { "Cathode", "Anode" } });
        catalogDefinitions.Add(new ComponentDefinition { typeKey = "Thermistor", orderedLocalPins = new List<string> { "Cathode", "Anode" } });
        Debug.Log("[objectPlacer] Generated custom 7 catalog slots using Cathode/Anode and 3-pin Pushbutton.");
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
            if (!string.IsNullOrEmpty(def.typeKey) && def.prefab != null && !_catalogDictionary.ContainsKey(def.typeKey))
            {
                _catalogDictionary.Add(def.typeKey, def);
            }
        }
    }

    public List<AssemblyStep> GeneratePinAssemblySteps(string typeKey, List<string> gridPins)
    {
        List<AssemblyStep> steps = new List<AssemblyStep>();
        if (_catalogDictionary == null || _catalogDictionary.Count == 0) BuildCatalog();

        if (!_catalogDictionary.TryGetValue(typeKey, out var def)) return steps;
        if (def.orderedLocalPins == null || def.orderedLocalPins.Count != gridPins.Count) return steps;

        for (int i = 0; i < def.orderedLocalPins.Count; i++)
        {
            string gridPin = gridPins[i];
            steps.Add(new AssemblyStep
            {
                localPinName = def.orderedLocalPins[i],
                targetGridPin = gridPin,
                worldPosition = gridMapper != null ? gridMapper.GetPinWorldPosition(gridPin) : Vector3.zero,
            });
        }
        return steps;
    }

    public GameObject PlaceComponentByType(string typeKey, List<string> gridPins, bool logPerPinCommands = false)
    {
        var steps = GeneratePinAssemblySteps(typeKey, gridPins);
        if (steps.Count == 0) return null;

        List<PinMapping> mappings = new List<PinMapping>();
        foreach (var s in steps) mappings.Add(new PinMapping { localPinName = s.localPinName, gridPin = s.targetGridPin });

        if (!_catalogDictionary.TryGetValue(typeKey, out var def) || def.prefab == null) return null;

        GameObject obj = Instantiate(def.prefab, Vector3.zero, Quaternion.identity);
        ApplyNamedPinAlignment(obj, mappings);
        return obj;
    }

    private void ApplyNamedPinAlignment(GameObject obj, List<PinMapping> mappings)
    {
        if (gridMapper == null || mappings == null || mappings.Count == 0) return;
        Transform GetPin(string name) => FindPinRecursive(obj.transform, name);

        obj.transform.rotation = Quaternion.identity;

        if (mappings.Count == 1)
        {
            Transform t = GetPin(mappings[0].localPinName);
            Vector3 target = gridMapper.GetPinWorldPosition(mappings[0].gridPin);
            obj.transform.position += (t != null) ? (target - t.position) : target;
            obj.transform.position -= Vector3.up * insertionDepth;
            return;
        }

        // FIX: Always use the FIRST and LAST pin in the list to calculate the rotation vector.
        // This ignores staggered middle pins (like the MOSFET Collector) preventing twisting.
        Transform t1 = GetPin(mappings[0].localPinName);
        Transform t2 = GetPin(mappings[mappings.Count - 1].localPinName);
        Vector3 target1 = gridMapper.GetPinWorldPosition(mappings[0].gridPin);
        Vector3 target2 = gridMapper.GetPinWorldPosition(mappings[mappings.Count - 1].gridPin);

        if (t1 != null && t2 != null)
        {
            Vector3 currentDir = t2.position - t1.position;
            Vector3 targetDir = target2 - target1;

            currentDir.y = 0;
            targetDir.y = 0;

            if (currentDir.sqrMagnitude > 1e-6f && targetDir.sqrMagnitude > 1e-6f)
            {
                float angle = Vector3.SignedAngle(currentDir, targetDir, Vector3.up);
                obj.transform.Rotate(0, angle, 0, Space.World);
            }
            
            // Re-calculate t1 position since spinning shifts it
            obj.transform.position += (target1 - t1.position);
        }
        else
        {
            obj.transform.position = (target1 + target2) * 0.5f;
        }

        // FIX: Translate down into the breadboard
        obj.transform.position -= Vector3.up * insertionDepth;
    }

    private Transform FindPinRecursive(Transform parent, string pinName)
    {
        foreach (Transform child in parent)
        {
            if (string.Equals(child.name, pinName, System.StringComparison.OrdinalIgnoreCase)) return child;
            Transform nested = FindPinRecursive(child, pinName);
            if (nested != null) return nested;
        }
        return null;
    }
}
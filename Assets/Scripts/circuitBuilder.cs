using UnityEngine;
using System.Collections.Generic;

public class circuitBuilder : MonoBehaviour
{
    [Header("Core References")]
    public objectPlacer placer;

    [Header("Execution Options")]
    public bool logPerPinInstructions = true;

    [ContextMenu("Build Circuit Manually")]
    public void BuildCircuitManual()
    {
        if (placer == null)
        {
            Debug.LogError("[circuitBuilder] Missing objectPlacer reference!");
            return;
        }

        GameObject circuitRoot = new GameObject("AR_Circuit_Root");
        circuitRoot.transform.position = Vector3.zero;
        circuitRoot.transform.rotation = Quaternion.identity;

        // ==============================================================
        // 🛠️ MANUALLY INSTRUCT COMPONENT PLACEMENT HERE
        // ==============================================================
        
        // Ensure the number of pins you list here EXACTLY MATCHES 
        // the number of "Ordered Local Pins" in the Unity Inspector!

        PlaceAndParent("Capacitor", new List<string> { "A1", "A2" }, circuitRoot.transform);
        PlaceAndParent("Diode", new List<string> { "B1", "B4" }, circuitRoot.transform);
        PlaceAndParent("Led", new List<string> { "C1", "C2" }, circuitRoot.transform);
        PlaceAndParent("MOSFET", new List<string> { "D5", "D6", "D7" }, circuitRoot.transform);
        PlaceAndParent("Pushbutton", new List<string> { "E1", "E3", "F1", "F3" }, circuitRoot.transform); // e.g., 4 pins
        PlaceAndParent("Resistor", new List<string> { "G1", "G5" }, circuitRoot.transform);
        PlaceAndParent("Thermistor", new List<string> { "H1", "H2" }, circuitRoot.transform);

        Debug.Log($"[circuitBuilder] Manual build complete. Spawned {circuitRoot.transform.childCount} components.");
    }

    private void PlaceAndParent(string typeKey, List<string> pins, Transform parent)
    {
        GameObject spawned = placer.PlaceComponentByType(typeKey, pins, logPerPinInstructions);
        if (spawned != null)
        {
            spawned.transform.SetParent(parent);
            spawned.name = $"{typeKey}_[{string.Join(",", pins)}]";
        }
        else
        {
            Debug.LogWarning($"[circuitBuilder] Failed to place '{typeKey}'. Check your catalog keys and pin counts.");
        }
    }
}
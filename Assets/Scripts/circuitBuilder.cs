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
        if (placer == null) return;

        // Cleanup previous instances to prevent duplicates
        GameObject oldRoot = GameObject.Find("AR_Circuit_Root");
        if (oldRoot != null)
        {
            DestroyImmediate(oldRoot);
        }

        GameObject circuitRoot = new GameObject("AR_Circuit_Root");
        circuitRoot.transform.position = Vector3.zero;
        circuitRoot.transform.rotation = Quaternion.identity;
        
        // Ensure the grid coordinates match the spacing of the 3D meshes
        PlaceAndParent("Resistor", new List<string> { "G1", "G4" }, circuitRoot.transform);
        PlaceAndParent("Thermistor", new List<string> { "H1", "H2" }, circuitRoot.transform);
        PlaceAndParent("MOSFET", new List<string> { "D5", "D6", "D7" }, circuitRoot.transform);
        PlaceAndParent("Diode", new List<string> { "B1", "B4" }, circuitRoot.transform);
        PlaceAndParent("Led", new List<string> { "C1", "C2" }, circuitRoot.transform);
        PlaceAndParent("Pushbutton", new List<string> { "E18", "E20", "F20" }, circuitRoot.transform);
        PlaceAndParent("Capacitor", new List<string> { "J14", "J16" }, circuitRoot.transform);
    }

    private void PlaceAndParent(string typeKey, List<string> pins, Transform parent)
    {
        GameObject spawned = placer.PlaceComponentByType(typeKey, pins, logPerPinInstructions);
        if (spawned != null)
        {
            spawned.transform.SetParent(parent);
            spawned.name = $"{typeKey}_[{string.Join(",", pins)}]";
        }
    }
}
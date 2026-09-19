using UnityEngine;

public class GridMapper : MonoBehaviour
{
    [Header("Calibration Roots")]
    public Transform a1Anchor;
    public float verticalHeight = 0.001f;

    [Header("Calibrated World-Space Step Vectors (Per Single Step)")]
    public Vector3 colStepWorld = new Vector3(-0.243f, 0f, 0f);
    public Vector3 rowStepWorld = new Vector3(0f, 0f, -0.2395f);

    [Header("Center Gap Offset (Rows F-J)")]
    [Tooltip("Extra world offset applied starting at Row F due to the center breadboard gap.")]
    public Vector3 rowGapOffset = new Vector3(0f, 0f, -0.585f);

    [Header("Validation Gizmos")]
    public bool showGizmos = true;
    public float gizmoRadius = 0.04f;

    private void OnDrawGizmos()
    {
        if (!showGizmos) return;
        Transform refT = a1Anchor != null ? a1Anchor : transform;

        Gizmos.color = Color.green;
        Gizmos.DrawWireCube(refT.position, Vector3.one * 0.02f);

        if (a1Anchor == null) return;

        Vector3 pos1 = GetPinWorldPosition("A1");
        Vector3 pos2 = GetPinWorldPosition("H5");

        // Visualize E5 to F5 gap bridge
        Gizmos.color = Color.magenta;
        Gizmos.DrawLine(pos1, pos2);

        Gizmos.color = Color.red;
        Gizmos.DrawSphere(pos1, gizmoRadius);

        Gizmos.color = Color.cyan;
        Gizmos.DrawSphere(pos2, gizmoRadius);
    }

    public Vector3 GetPinWorldPosition(string pinName)
    {
        Transform refT = a1Anchor != null ? a1Anchor : transform;
        if (string.IsNullOrEmpty(pinName) || pinName.Length < 2) return refT.position;

        char rowChar = char.ToUpper(pinName[0]);
        if (!int.TryParse(pinName.Substring(1), out int colNum)) return refT.position;

        int rowIndex = rowChar - 'A'; // A=0 ... E=4, F=5 ... J=9
        int colIndex = colNum - 1;    // 1->0 ... 30->29

        Vector3 origin = a1Anchor != null ? a1Anchor.position : transform.position;
        Vector3 pos = origin + (colStepWorld * colIndex) + (rowStepWorld * rowIndex);

        // Apply center gap offset for Row F and beyond (index >= 5)
        if (rowIndex >= 5)
        {
            pos += rowGapOffset;
        }

        return pos + (Vector3.up * verticalHeight);
    }

    public GameObject PlaceComponentBetweenPins(GameObject prefab, string pinA, string pinB)
    {
        if (prefab == null) return null;
        Vector3 posA = GetPinWorldPosition(pinA);
        Vector3 posB = GetPinWorldPosition(pinB);
        Vector3 midpoint = (posA + posB) * 0.5f;

        GameObject obj = Instantiate(prefab, midpoint, Quaternion.identity);
        if (posA != posB) obj.transform.LookAt(posB);
        return obj;
    }
}
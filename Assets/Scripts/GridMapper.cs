using UnityEngine;

public class GridMapper : MonoBehaviour
{
    [Header("Calibration")]
    public Transform boardRoot;
    public float pinPitch = 0.254f; // Scaled up 10x (was 0.0254f)
    public Vector3 localOffset = Vector3.zero; 
    public float verticalHeight = 0.05f; 
    public float gizmoSphereRadius = 0.05f;

    [Header("Runtime Visualizer")]
    public bool spawnRuntimeSpheres = true;
    public float runtimeSphereScale = 0.05f; // Scaled up for visibility

    private void Awake()
    {
        if (boardRoot == null)
        {
            GameObject boardObj = GameObject.Find("Breadboard");
            if (boardObj != null) boardRoot = boardObj.transform;
        }
    }

    private void Start()
    {
        Transform rootToUse = boardRoot != null ? boardRoot : transform;
        
        if (rootToUse.TryGetComponent(out Renderer boardRenderer))
        {
            Debug.Log($"[GridMapper] Board bounds center: {boardRenderer.bounds.center}, size: {boardRenderer.bounds.size}");
        }

        Vector3 testPos = GetWorldPositionFromPin(5, 'A', rootToUse);
        Debug.Log($"Pin A5 world position: {testPos}");

        if (spawnRuntimeSpheres)
        {
            SpawnMarker(GetWorldPositionFromPin(1, 'A', rootToUse), Color.green, "Marker_A1", rootToUse);
            SpawnMarker(GetWorldPositionFromPin(5, 'E', rootToUse), Color.red, "Marker_E5", rootToUse);
        }
    }

    public Vector3 GetWorldPositionFromPin(int row, char col, Transform root)
    {
        Transform activeRoot = root != null ? root : transform;
        int colIndex = char.ToUpper(col) - 'A';
        if (colIndex >= 5) colIndex--; // account for breadboard center gap
        
        float xOffset = colIndex * pinPitch;
        float zOffset = row * pinPitch;
        
        Vector3 localPos = new Vector3(xOffset, verticalHeight, zOffset) + localOffset;
        return activeRoot != null ? activeRoot.TransformPoint(localPos) : localPos;
    }

    private void OnDrawGizmos()
    {
        Transform rootToUse = boardRoot != null ? boardRoot : transform;
        Gizmos.color = Color.green;
        Gizmos.DrawSphere(GetWorldPositionFromPin(1, 'A', rootToUse), gizmoSphereRadius);
        Gizmos.color = Color.red;
        Gizmos.DrawSphere(GetWorldPositionFromPin(5, 'E', rootToUse), gizmoSphereRadius);
    }

    private void SpawnMarker(Vector3 pos, Color color, string name, Transform parent)
    {
        Transform existing = parent.Find(name);
        GameObject sphere = existing != null ? existing.gameObject : GameObject.CreatePrimitive(PrimitiveType.Sphere);
        
        if (existing == null)
        {
            sphere.name = name;
            sphere.transform.SetParent(parent, true);
        }
        
        sphere.transform.position = pos;
        sphere.transform.localScale = Vector3.one * runtimeSphereScale;
        
        if (sphere.TryGetComponent(out Collider col)) Destroy(col);
        
        if (sphere.TryGetComponent(out Renderer rend))
        {
            Shader shader = Shader.Find("Universal Render Pipeline/Lit");
            if (shader == null) shader = Shader.Find("Standard");
            if (shader == null) shader = Shader.Find("Unlit/Color");
            
            Material mat = new Material(shader);
            if (mat.HasProperty("_BaseColor")) mat.SetColor("_BaseColor", color);
            else if (mat.HasProperty("_Color")) mat.color = color;
            rend.material = mat;
        }
    }
}
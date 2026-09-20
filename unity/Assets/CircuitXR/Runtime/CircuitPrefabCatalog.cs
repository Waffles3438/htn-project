using System;
using System.Collections.Generic;
using UnityEngine;

namespace CircuitXR
{
    [CreateAssetMenu(fileName = "CircuitPrefabCatalog", menuName = "Circuit/Prefab Catalog")]
    public class CircuitPrefabCatalog : ScriptableObject
    {
        [Serializable]
        public class BreadboardPrefab
        {
            public string componentType;
            public GameObject prefab;
        }
        public Shader surfaceShader;
        public Font labelFont;
        public List<BreadboardPrefab> breadboardComponents = new List<BreadboardPrefab>();
        public BreadboardPrefab GetBreadboard(string type) => breadboardComponents.Find(p => p.componentType == type);
    }
}

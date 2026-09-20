// Board map + semantic address resolution (UNITY_HANDOFF.md "Deriving coordinates from addresses").
// Two resolver sources, so the existing calibrated GridMapper stays authoritative when present:
//   • BoardMapResolver  — board-local meters from board-hole-map.meters.json (put it in StreamingAssets).
//   • GridMapperResolver — delegates A–J strip holes to the existing GridMapper calibration and
//     derives rail holes with the same step vectors (rails sit 4/3 columns left of A, 12/13 right).

using System;
using System.Collections.Generic;
using System.Globalization;
using Newtonsoft.Json;
using UnityEngine;

namespace CircuitXR
{
    public interface IBoardResolver
    {
        // Resolves "BB1:A15", "BB1:RAIL:L:+:A:12" to a position in the board's space.
        bool TryResolveBoardAddress(string address, out Vector3 position);
        // True when positions are world-space (calibrated GridMapper) instead of board-local meters.
        bool IsWorldSpace { get; }
        string HoleMapVersion { get; }
    }

    public class BoardMapResolver : IBoardResolver
    {
        [Serializable]
        private class MapFile
        {
            public string id, model, holeMapVersion;
            public bool physicalVerified;
            public List<Hole> holes;
        }

        [Serializable]
        private class Hole
        {
            public string id, net;
            public Point position; // x, y, z in meters, board-local, A1 origin
        }

        [Serializable] private class Point { public float x, y, z; }
        public IReadOnlyDictionary<string, Vector3> Holes => holes;
        private readonly Dictionary<string, Vector3> holes;
        public string HoleMapVersion { get; }
        public bool IsWorldSpace => false;

        public BoardMapResolver(string mapJson)
        {
            var map = JsonConvert.DeserializeObject<MapFile>(mapJson)
                      ?? throw new CircuitImportException("Board map JSON did not contain an object.");
            if (map.id != "BB1" || map.model != CircuitDefinition.SupportedBoardModel || map.holes == null || map.holes.Count != 830)
                throw new CircuitImportException("Unsupported or incomplete board map.");
            HoleMapVersion = map.holeMapVersion;
            holes = new Dictionary<string, Vector3>(map.holes.Count);
            foreach (var hole in map.holes)
            {
                if (hole.position == null)
                    throw new CircuitImportException("Board map hole '" + hole.id + "' has no 3D position.");
                holes[hole.id] = new Vector3(hole.position.x, hole.position.y, hole.position.z);
            }
        }

        public bool TryResolveBoardAddress(string address, out Vector3 position)
        {
            position = default;
            if (string.IsNullOrEmpty(address)) return false;
            if (address.StartsWith("BB1:RAIL:", StringComparison.Ordinal))
            {
                // "BB1:RAIL:L:+:A:12" → hole id "L+A12". Whole-segment net addresses ("BB1:RAIL:L:+:A")
                // are electrical names, not insertion points, and resolve only through nets.
                var parts = address.Split(':');
                if (parts.Length != 6) return false;
                if (!int.TryParse(parts[5], NumberStyles.Integer, CultureInfo.InvariantCulture, out int index) || index < 1 || index > 25)
                    return false;
                return holes.TryGetValue(parts[2] + parts[3] + parts[4] + index, out position);
            }
            if (address.StartsWith("BB1:", StringComparison.Ordinal))
                return holes.TryGetValue(address.Substring(4), out position);
            return false;
        }
    }

    // Grammar helper shared with the builder; component endpoints ("led_1:anode") are
    // resolved by CircuitBuilder from placed components and prefab pin anchors.
    public static class BoardAddress
    {
        public static bool TryComponentEndpoint(string endpoint, out string componentId, out string pin)
        {
            componentId = pin = null;
            if (string.IsNullOrEmpty(endpoint) || endpoint.StartsWith("BB1:", StringComparison.Ordinal)) return false;
            int separator = endpoint.IndexOf(':');
            if (separator <= 0 || separator == endpoint.Length - 1) return false;
            componentId = endpoint.Substring(0, separator);
            pin = endpoint.Substring(separator + 1);
            return true;
        }
    }
}

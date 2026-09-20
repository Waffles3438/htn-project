using System;
using System.Globalization;
using UnityEngine;

namespace CircuitXR
{
    public class GridMapperResolver : IBoardResolver
    {
        private readonly GridMapper mapper;
        public bool IsWorldSpace => true;
        public string HoleMapVersion => "grid-mapper-calibration";

        public GridMapperResolver(GridMapper mapper)
        {
            this.mapper = mapper ?? throw new ArgumentNullException(nameof(mapper));
        }

        public bool TryResolveBoardAddress(string address, out Vector3 position)
        {
            position = default;
            if (string.IsNullOrEmpty(address)) return false;
            if (address.StartsWith("BB1:", StringComparison.Ordinal))
                address = address.Substring(4);
            if (address.StartsWith("RAIL:", StringComparison.Ordinal))
            {
                // "RAIL:L:+:A:12" → hole id "L+A12" for the shared rail math.
                var parts = address.Split(':');
                if (parts.Length != 5) return false;
                return TryResolveRail(parts[1] + parts[2] + parts[3] + parts[4], out position);
            }
            if (!System.Text.RegularExpressions.Regex.IsMatch(address, @"^[A-J]([1-9]|[1-5][0-9]|6[0-3])$")) return false;
            position = mapper.GetPinWorldPosition(address);
            return true;
        }

        // Same symmetric rail model as the API (rails 3 pitches outside A and J; five 5-hole
        // groups per segment starting at strip rows 3 and 33), expressed in the GridMapper's
        // calibrated step vectors so rails land next to the calibrated strip columns.
        private bool TryResolveRail(string holeId, out Vector3 position)
        {
            position = default;
            if (!System.Text.RegularExpressions.Regex.IsMatch(holeId, @"^[LR][+-][AB]([1-9]|1[0-9]|2[0-5])$")) return false;
            var side = holeId[0];
            var polarity = holeId[1];
            var segment = holeId[2];
            var index = int.Parse(holeId.Substring(3), CultureInfo.InvariantCulture);
            int row = (segment == 'A' ? 3 : 33) + ((index - 1) / 5) * 6 + (index - 1) % 5;
            // GridMapper uses colStepWorld for NUMBERED rows and rowStepWorld for LETTERS.
            // Resolve J1 through the mapper to preserve its calibrated center-gap correction.
            var edge = mapper.GetPinWorldPosition(side == 'L' ? "A1" : "J1");
            int offset = side == 'L' ? (polarity == '+' ? -4 : -3) : (polarity == '+' ? 3 : 4);
            position = edge + mapper.rowStepWorld * offset + mapper.colStepWorld * (row - 1);
            return true;
        }
    }

}

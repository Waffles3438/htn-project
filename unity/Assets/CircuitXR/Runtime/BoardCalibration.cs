using System;
using UnityEngine;

namespace CircuitXR
{
    public static class BoardCalibration
    {
        // Both AR Foundation hit poses and board-local geometry are Unity coordinates.
        // +X = A1->J1, +Z = A1->A63, +Y = cross(Z, X). No ARCore reflection here.
        public static Pose FromReferences(Vector3 a1, Vector3 j1, Vector3 a63)
        {
            var x = j1 - a1; var z = a63 - a1;
            if (Mathf.Abs(x.magnitude - .02794f) > .012f || Mathf.Abs(z.magnitude - .15748f) > .035f)
                throw new ArgumentException("Reference spacing is off. Tap the holes A1, J1 and A63, not the board corners.");
            if (Mathf.Abs(Vector3.Dot(x.normalized, z.normalized)) > .3f)
                throw new ArgumentException("The reference lines should meet at a right angle. Try again.");
            var up = Vector3.Cross(z.normalized, x.normalized).normalized;
            if (Vector3.Dot(up, Vector3.up) < .65f)
                throw new ArgumentException("Lay the board flat, with A1 → J1 → A63 in the indicated order.");
            z = Vector3.Cross(x.normalized, up).normalized;
            return new Pose(a1, Quaternion.LookRotation(z, up));
        }
    }
}

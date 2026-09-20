// Typed model for the Circuit API "placement v3" contract (schemas/placement.schema.json).
// The JSON names semantic addresses only ("BB1:A15", "led_1:anode"); every renderer derives
// its own geometry from the board map and prefab anchors. See UNITY_HANDOFF.md.
//
// One-time setup: add Unity's official Newtonsoft JSON package (com.unity.nuget.newtonsoft-json)
// via Package Manager → "Add package by name".

using System;
using System.Collections.Generic;
using Newtonsoft.Json;

namespace CircuitXR
{
    public class CircuitImportException : Exception
    {
        public CircuitImportException(string message) : base(message) { }
    }

    [Serializable]
    public class CircuitDefinition
    {
        public const int SupportedVersion = 3;
        public const string SupportedBoardModel = "demo_breadboard_v1";

        [JsonProperty("version")] public int Version;
        [JsonProperty("sessionId")] public string SessionId;
        [JsonProperty("breadboardModel")] public string BreadboardModel;
        [JsonProperty("title")] public string Title;
        [JsonProperty("prompt")] public string Prompt;
        [JsonProperty("source")] public string Source;
        [JsonProperty("generatedAt")] public string GeneratedAt;
        [JsonProperty("breadboard")] public BoardInfo Board;
        [JsonProperty("requiredParts")] public List<PartDefinition> RequiredParts = new List<PartDefinition>();
        [JsonProperty("components")] public List<ComponentDefinition> Components = new List<ComponentDefinition>();
        [JsonProperty("jumperWires")] public List<ConnectionDefinition> JumperWires = new List<ConnectionDefinition>();
        [JsonProperty("nets")] public List<NetDefinition> Nets = new List<NetDefinition>();
        [JsonProperty("validation")] public ValidationDefinition Validation;
        [JsonProperty("instructions")] public List<BuildStepDefinition> Instructions = new List<BuildStepDefinition>();
        [JsonProperty("externalDevices")] public List<ExternalDeviceDefinition> ExternalDevices;
        [JsonProperty("firmware")] public FirmwareDefinition Firmware;

        public static CircuitDefinition FromJson(string json)
        {
            var circuit = JsonConvert.DeserializeObject<CircuitDefinition>(json);
            if (circuit == null) throw new CircuitImportException("Circuit JSON did not contain an object.");
            circuit.ValidateContract();
            return circuit;
        }

        // Reject unsupported major versions and incompatible maps before any rendering happens.
        public void ValidateContract()
        {
            if (Version != SupportedVersion)
                throw new CircuitImportException(
                    $"Unsupported placement version {Version}: this importer reads version {SupportedVersion} only. Regenerate the circuit.");
            if (!string.Equals(BreadboardModel, SupportedBoardModel, StringComparison.Ordinal))
                throw new CircuitImportException(
                    $"Unsupported board model '{BreadboardModel}': expected '{SupportedBoardModel}'.");
            if (string.IsNullOrWhiteSpace(SessionId) || Components == null || Components.Count == 0 || JumperWires == null || Instructions == null || Nets == null)
                throw new CircuitImportException("Circuit is incomplete.");
            if (Board == null || Board.Model != SupportedBoardModel || string.IsNullOrEmpty(Board.HoleMapVersion))
                throw new CircuitImportException("Circuit is missing breadboard.holeMapVersion.");
            if (Validation == null || !Validation.Valid)
                throw new CircuitImportException("Circuit must explicitly pass validation before rendering.");
        }
    }

    [Serializable]
    public class BoardInfo
    {
        [JsonProperty("model")] public string Model;
        [JsonProperty("holeMapVersion")] public string HoleMapVersion;
        [JsonProperty("physicalVerified")] public bool PhysicalVerified;
    }

    [Serializable]
    public class PartDefinition
    {
        [JsonProperty("type")] public string Type;
        [JsonProperty("value")] public string Value;
        [JsonProperty("quantity")] public int Quantity;
    }

    [Serializable]
    public class ComponentDefinition
    {
        [JsonProperty("id")] public string Id;
        [JsonProperty("type")] public string Type;
        [JsonProperty("value")] public string Value;
        [JsonProperty("assetId")] public string AssetId;
        [JsonProperty("mount")] public MountDefinition Mount;
        [JsonProperty("buildStep")] public int BuildStep;
    }

    // One class for both mount kinds; Type selects the interpretation, exactly as the schema does.
    [Serializable]
    public class MountDefinition
    {
        [JsonProperty("type")] public string Type; // "breadboard" | "external"
        // Breadboard mount: map from stable terminal id ("anode", "a", "a1") to a board address.
        [JsonProperty("board")] public string Board;
        [JsonProperty("terminals")] public Dictionary<string, string> Terminals;
        // Reserved footprint-based mounting for breadboard-compatible controllers.
        [JsonProperty("anchor")] public string Anchor;
        [JsonProperty("orientation")] public string Orientation;
        // External mount: which side of which board the renderer should place the device.
        [JsonProperty("relativeTo")] public string RelativeTo;
        [JsonProperty("side")] public string Side;

        public bool IsBreadboard => Type == "breadboard";
        public bool IsExternal => Type == "external";
    }

    [Serializable]
    public class ConnectionDefinition
    {
        [JsonProperty("id")] public string Id;
        [JsonProperty("from")] public string From;
        [JsonProperty("to")] public string To;
        [JsonProperty("color")] public string Color;
        [JsonProperty("buildStep")] public int BuildStep;
    }

    [Serializable]
    public class NetDefinition
    {
        [JsonProperty("id")] public string Id;
        [JsonProperty("members")] public List<string> Members = new List<string>();
    }

    [Serializable]
    public class ValidationDefinition
    {
        [JsonProperty("valid")] public bool Valid;
        [JsonProperty("checks")] public List<string> Checks = new List<string>();
        [JsonProperty("warnings")] public List<string> Warnings = new List<string>();
    }

    [Serializable]
    public class BuildStepDefinition
    {
        [JsonProperty("step")] public int Step;
        [JsonProperty("componentIds")] public List<string> ComponentIds = new List<string>();
        [JsonProperty("text")] public string Text;
    }

    [Serializable]
    public class ExternalDeviceDefinition
    {
        [JsonProperty("id")] public string Id;
        [JsonProperty("type")] public string Type;
        [JsonProperty("model")] public string Model;
        [JsonProperty("assetId")] public string AssetId;
        [JsonProperty("mount")] public MountDefinition Mount;
    }

    [Serializable]
    public class FirmwareDefinition
    {
        [JsonProperty("filename")] public string Filename;
        [JsonProperty("board")] public string Board;
        [JsonProperty("language")] public string Language;
        [JsonProperty("code")] public string Code;
        [JsonProperty("uploadInstructions")] public string UploadInstructions;
    }
}

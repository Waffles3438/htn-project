# Hardware source snapshot

`person2/*.json` are byte-for-byte copies from:

- Repository: https://github.com/Waffles3438/htn-project
- Branch: `person2`
- Commit: `9d81633c65a42059bfc6307aa8fd0ec1554cd114`
- Paths: `person2/data/{breadboard,components,placement,assets}.json`

Copied into the circuit owner's folder to avoid merging unrelated branch changes (including checked-in `node_modules`). The source board has 830 holes: 630 terminal-strip holes and 200 rail holes. Its coordinate axes are X right, Y up, Z forward; coordinates are millimeters.

The API preserves raw files and all 630 A–J terminal coordinates (converted to meters), adds documented electrical connectivity assumptions, and uses the existing LED asset ID. Runtime map `person2-9d81633+rails1` changes only the 200 rail positions to a symmetric model with five-hole groups. This is not measured hardware; `physicalVerified` remains false. See `../UNITY_HANDOFF.md` for exact old/new coordinates, migration, footprints and asset gaps. The upstream `placement.json` is a syntax example and is not used as a valid circuit.

Keep the raw snapshot untouched. Old saved placements are not automatically migrated; regenerate against the current map or retain the matching old map. Browser circle size is schematic, not a physical aperture measurement. Browser and XR must share versioned geometry, not UI-only coordinate shifts.

When the hardware owner changes this data, refresh all four files from the reviewed commit, update `holeMapVersion`/provenance, rerun tests, regenerate fixtures, and coordinate any frame changes with Unity. Do not silently regenerate coordinates from the nominal dimensions.

## User-supplied mesh

`assets/breadboard.fbx` is an unchanged copy of the user-supplied `breadboard.fbx`. `assets/breadboard.metadata.json` records its checksum, embedded transforms and alignment targets. Its presence does not imply that its scale, labeled holes or pivots have been verified against the physical board. No asset license was supplied with this attachment.

The accompanying JSON paste ends mid-record at C57, with a truncation marker. Its 562 complete hole records exactly match the pinned source above; it was not used to replace the full GitHub data.

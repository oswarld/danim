# Synthetic vehicle support pack (test resource)

Regenerated on 2026-10-26 (Asia/Seoul) from the consolidated simulator with
`node scripts/vehicle-lab/export-support.mjs`, including the corrected `unsupported`
status for warning signals that the simulator does not model. The manifest records
the source hashes used to build this test-only pack.

| File | SHA-256 |
| --- | --- |
| `pack.json` | `1d387ad91005d2334900b470ab46a2b1b4b6e635229c521a03810aca69c27b48` |
| `manifest.json` | `2a4a58206052ee3b70739c7973230cc13c5b445a96b97608e9d2227ca66f7737` |

`manifest.json` records the simulator/helper source hashes the pack was built from.
Everything here is `synthetic: true`, `dataMode: "demo"`, `evidenceClass: "synthetic_fixture"`,
`source: "virtual_vehicle"`. It is consumed only by `src/test` through `VirtualVehiclePack.kt`;
nothing in `src/main` reads it. It is not Pleos, AAOS, emulator, phone or real-car evidence and
must never be uploaded to a backend. `simulationEpoch` (`2026-10-26T00:00:00.000Z`) is the fixture
clock; replaying the fixed timestamps against a wall clock makes every snapshot stale by design.

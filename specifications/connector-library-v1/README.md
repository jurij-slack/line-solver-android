# Phase 6 portable connector records and rig library v5

Kotlin/Android implementation only. Python sources/data and original research are
read-only. The canonical shipped connector authority is the portable UTF-8 JSON
compiled in `model/ConnectorShipped.kt`, decoded/validated by `ConnectorLibrary`.
There is no second editable runtime database. `ConnectorLibrary.json` exports the
same document on JVM and Android. The phase validation inventory is an evidence
projection, not runtime authority. The raw candidate package and SHA-256 are retained.

Catalogue `schemaVersion=1` contains `products`, `review` and `researchHash`.
Products identify **exact configuration records**, with opaque `id`, immutable
`revision`, `manufacturer`, `article` when known, `name`, category, material,
`diameterM`, source-qualified `size`, physical `massKg`, separate `mbsN`, `blN`,
`mbsLowerBoundN`, `wllN`, certification text and `certifiedClaimN`. Runtime numeric
units are kg, m and N. A lower bound never becomes an exact failure strength.
Source URL, verification date, test basis, restrictions, uncertainty, original
source-unit information and `origin` are mandatory or explicitly unknown. A
verification date is not a test date. Manufacturer claims are not app-issued
certifications. BL is not renamed statistical MBS; MBS takes comparison priority
when both MBS and another published/certification value are present.

Categories: `soft_shackle`, `oval_maillon`, `rigid_anchor_shackle`, `sewn_loop`,
`permanent_serial`, `sewn_transverse`, `accessory`. Rigid shackles are offered only
at anchor/weblock nodes. A transverse sewing selection requires an explicit shared
main/backup tie; it does not create a new seam or alter topology. Catalogue-only
sewn research remains review-only until exact assembly and mass evidence suffice.
Custom sewing identifies its webbing pair/version, seam and direction in the
configuration/source, never by a generic percentage of raw webbing strength.

Review records retain candidate JSON, reason and source URLs. Four BC 5/6 mm
length-specific candidates are review-only: current manufacturer pages verify
MBS/length options, but the size-dependent mass and detailed WLL research values
were not independently recovered. The two kit descriptions and three sewn
records remain retained evidence. The exact 4 mm kit has an installation preset;
the 5 mm kit remains unresolved. Original zinc quick-link issues are not merged
with Peguet or stainless SKUs. Slack Inov LQCI8 stores its explicit 38 kN MBS,
25 kN CE claim and 10 kN WLL in distinct fields.

## Installed items, immutable snapshots and migration

Reuse Phase 5 `HardwareItem` IDs and `StructuralJoint` ends. Add optional
`specification` snapshot and `fingerprint`, plus `role` (`unspecified`, `main`,
`backup`, `shared`), `standingLoad`, `failedLoad`, and
`ratedInstallationConfirmed=false`. No item count, equal-share factor, hardware
spring or disconnected hardware path is added. One double-wrap shackle is one
item. Two shackles have two IDs, masses and immutable spec references.

Each load assumption has `status` (`unknown`, `loaded`, `unloaded`), `note` and
`source` (ordinary editor: `user assumption`). Standing/intact and failed/backup-
engaged states are independent. A previously unloaded item does not acquire an
unloaded failed-state default; that default is unknown. Roles and statuses do not
remove material edges, move nodes or affect equilibrium. The editor's Yes/No/Unknown
is an annotation, not a mechanical solver for engagement or sharing.

References require product ID and revision plus SHA-256 of the full default-expanded
specification in catalogue-v1 canonical JSON (`CatalogueCodec.canonical`). Hashes
are content checks, not signatures. Manufacturer snapshots must also equal an
installed immutable catalogue record. Unknown/mismatched manufacturer references
fail without substitution. Custom records use `user:connector:` namespace and
`user_declared` origin. Local `custom_connectors_v1.json` is an atomic, append-only
revision store separate from shipped records. Revision edits create new identities;
saved rigs retain old snapshots independently of local availability. Rig JSON
export includes selected custom snapshots; an unused custom library is not a cloud
or standalone synchronization service. No numerical material model fingerprint
or Phase 3 immutable curve record is changed.

Rig library v5 is required when Phase 6 fields/settings are present. Versions 1–4
remain readable; absent item roles/statuses remain unspecified/unknown and no
connector is fabricated. The Phase 5 connection layout remains version 1 with
additive fields inside the schema-5 rig boundary. Strict library decoding rejects
unknown future versions/fields/enums and invalid references/incidence. Existing
JSON import conflict policies and atomic write behavior remain.

## Optional mass, energy and load diagnostics

`Model connections=false`: topology and required stops/ties stay active. Optional
connector masses and strength diagnostics are omitted; item records/statuses remain.

When true, each populated item's known physical mass is attached once at its joint's
existing solver node. Unknown mass blocks gravity calculations with an actionable
error; no assumed zero is supplied. The potential is `U_c = sum(m_i g y_node)`;
the vertical gradient is `m_i g`, with zero added Hessian. The same mass inventory
survives cut/catch/rest, independent of a hardware loaded annotation. Anchored items
remain at fixed nodes and contribute weight to reactions/potential. Connector
gravity is reported separately from webbing and walker gravity and included in
all total-energy balances and the fall ledger. No failure of hardware is simulated.

The verified BC 2×4 mm preset installs two independent double-wrap shackles (12.8 g
each) and one two-thimble protection-pack record (10.4 g combined residual). Total
36 g. Residual is derived from exact kit total, not a universal measured thimble
mass. The pack is allowed only with exactly two matching shackles, has no breaking
rating and does not re-add the kit's entire mass. No combined MBS is provided.

Most common multi-way joints have **unknown individual hardware force**. Associated
cable endpoint tension may be shown as context and as an inconsistency warning for
an assumed-unloaded item with >1 N associated tension; it is not proof of that
item's actual load. The 1 N threshold is a reporting threshold, not a strength or
safety tolerance. Role annotations do not prove load routing across the common node.

A limited numerical strength comparison is allowed only for one installed item,
exactly two incident physical ends/solver edges, no rubber/limiter path, no transverse
seam, confirmed exact rated installation, axial collinear opposed forces, and equal
endpoint tensions within `max(1e-6 N, 1e-8 relative)`. It uses the solved axial
limiting-case tension and exact MBS (or explicitly named BL). Unknown rating or
lower-bound-only data produces no exact margin. Multiple-item joints, main/backup
branch incidence, triaxial/cross loading, unknown contact conditions and arbitrary
routing produce no capacity/safety factor. An unloaded assumption suppresses a
comparison and can conflict with associated solved tension. Ratings are never summed.

## Bungee visibility and settings ownership

`showBungeeOptions=false` is the new all-webbing draft default. It is shared in Solver
Settings alongside `Model connections`, represented in existing ModelPreferences,
working Draft and schema-5 RigConfiguration. It controls only the existing Phase 4
editor's visibility. Hidden rigs visibly list their existing bungees and offer a
settings entry. All stored rest lengths, folds, models, limits and topology are
preserved, and the solver never reads this visibility preference. Preview/results
continue to show bungees. Restart, JSON roundtrip and Simple/Mixed conversion retain
it. Reset Rig retains solver settings; Restore Defaults hides optional editing.

Metadata/visibility/status edits may retain computed results only when physical
mass signatures and all other numerical inputs remain equal. Toggling actual
connector mass or changing populated mass configuration invalidates a cached result.
Existing supported failure-reset baseline remains available across annotation-only
edits. Bungee catch/dynamic failure remains unavailable as in Phase 4.

Phase 7 must independently implement this portable schema, identity, gravity
contribution and qualified reporting. No Python parity, detailed sharing/routing,
progressive connector failure, frictional bending, slip or advanced dynamics are
claimed or implemented in Phase 6.

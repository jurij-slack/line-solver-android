# Android user material package v1

Phase 3, Android/Kotlin only. Catalogue record definitions and canonical form are
[component-catalogue-v1](../component-catalogue-v1/README.md). No Python reader or
new numerical law is introduced.

## Package envelope and ownership

The portable JSON object contains `package_version: 1`, `namespace` (UUID),
`catalogue` (catalogue schema version 1), `retired_model_ids` and `attribution`.
Runtime units remain N, m, kg, kg/m, J and dimensionless engineering strain.
All local record IDs use `user:<namespace-uuid>:<entity-uuid>`. A package may contain
multiple namespaces after imports. The library namespace governs newly created
IDs; importing into an empty library adopts the imported namespace.

Products, documented versions, user-declared batches, measurement snapshots and
models use the existing typed catalogue records. Unknown version/batch is null.
`synthetic_example` explicitly identifies documentation/test batches. A label
alone is not independently verified manufacturer provenance. Local IDs cannot
shadow any bundled ID. Bungee/connector definitions and instances are rejected.
Bundled products/versions/batches may be referenced by ID without copying their
source data into an export. The receiving installation must supply those exact
context references; missing references fail before persistence. Export retains
user-entered attribution/license notices and does not include bundled datasets.

New numerical snapshots receive a new model ID and increasing product-local
revision label; their immutable content fingerprint uses catalogue-v1 canonical
SHA-256. This is the Phase 1 snapshot alternative to multiple revisions sharing
one ID, matching the installed repository's one-revision-per-ID contract. The
physics/content identity includes dataset evidence, units, domains and model
specifications. Saved rigs keep exact ID plus content fingerprint. Runtime
metadata carries the revision explicitly. Changing local product display metadata
does not change old numerical snapshots; density/MBS remain model-specific.

## Raw observations and fitting

Each user dataset retains the exact source table string and form fields under
`raw_payload.editor`, explicit `origin`, `branch`, `license_notice`,
`batch_provenance`, `measurement_series`, and `parsed_si_rows`. The last field
retains successfully converted rows even when other rows are invalid; the dataset
`points` is null in that case, preventing accidental partial fits. Finite negative
or nonmonotonic rows remain in `points` for review with validation warnings. No
sort, smoothing, deletion of contradictory rows or averaging is performed.

`measurement_series` records snapshot ancestry, not an independent new experiment.
Duplicating an unchanged fit reuses the same dataset ID. Editing observations
creates a new source snapshot. Past sources/models remain retrievable. Source
dates/protocol/conditions/uncertainty are retained; observations are self-declared
and not claimed independently verified. `own_measured`, `manufacturer`,
`derived_from_source`, `estimated` are the supported origins. Estimated and
unloading data are review-only. SI conversion is checked again on JSON import,
including partial converted rows and declared source-unit metadata.

The native PCHIP implementation is used for at least two strictly increasing,
positive independent observations. One positive point yields a clearly provisional
origin-to-point linear law. Synthetic origin is imposed at evaluation time only
when needed, never stored as a measured observation. The law ends at the last
supported observation; no custom upper continuation is permitted. Nominal material
MBS and optional mass stay unknown when blank. Mass is positive kg/m when given;
MBS is positive N when given. Data above nominal MBS stay review-only. Missing
mass permits plotting but blocks gravity calculations. MBS is not WLL; no
termination strengths or rupture strain are inferred.

## CSV and editor conventions

Known headings: `T/N`, `T/kN`; `epsilon/1`, `epsilon/%`, `ε/1`, `ε/%`,
`extension/mm`, `extension/m`. Columns may be reversed. Comma, semicolon and tab
separators are supported; quoted cells, escaped double quotes and CRLF/LF are
supported. Force and elongation columns are explicitly selectable (UI is 1-based).
Nonstandard headings require the user to confirm the header checkbox, columns
and units; the importer never promotes a CSV automatically. No numeric magnitude
heuristic infers percent versus fraction. Scientific notation remains numeric.

Decimal dot or decimal comma is accepted without grouping; comma decimals need a
semicolon separator or CSV quoting. Ambiguous mixed separators and nonfinite
numbers are rejected. mm/m elongation requires finite positive reference `L0/m`;
strain is extension/reference length. Length units/reference remain in the raw
editor because catalogue-v1 `original_units` describes force/strain units only.
Source order and exact cell strings are preserved. CSV export writes explicit
unit headings and quoted original force/elongation cell lexemes; JSON backup is
required to retain reference length, product/provenance and full source text.

## Storage, conflicts and recovery

`my_materials_v1.json` is app-private and written with Android `AtomicFile`.
Validation and runtime-law construction finish before writing; in-memory state
changes after the atomic writer succeeds. Reads that fail block writes and expose
an explicit backup-restoration action, retaining the damaged file until restoration.
No storage permissions, account, service or database dependency are required.
A separate private preferences draft preserves unsaved fields, even invalid text.
The working rig draft is persisted separately from global preferences.

Import validates the whole bundle and all references/content hashes before commit.
Skip-identical rejects conflicting immutable records. Explicit Import-as-distinct
forks all local IDs, rewrites typed references, records `import_id_mapping` and
`imported_from_namespace` in dataset evidence, and reseals model hashes. Bundled
IDs stay unchanged. JSON restores all local source snapshots, revisions and
retirement state. There is no destructive replacement of a valid model revision.
Use the editor to create a new revision, or fork an imported conflicting package.

Retirement hides a model from new choices while preserving evaluator/source data
and exportability. Saved rigs can still resolve retired snapshots. Dataset
deletion requires zero model dependencies, including retired models; product
deletion requires zero datasets/models and then removes its unused versions and
batches. Missing model IDs/content hashes block loading and point to JSON restore,
with no default substitution. A damaged library can be explicitly restored from a
validated backup; unavailable models absent from that backup remain unresolved.

Custom product name/brand are also retained per model through its immutable raw
editor snapshot. Renaming a user product therefore cannot invalidate a rig's old
manufacturer selection. New selections use the newest eligible active local
snapshot when no curated default exists; switching models updates the manufacturer
selection explicitly. User restrictions are retained per source snapshot and
combined with current product restrictions. Bundled product identity remains the
authority for models attached to a shipped product.

For CSV imports, `raw_payload.editor.imported_csv_text` separately retains the
original document text (including line endings) even when table cells or units
are corrected before the first save. The editable `table` records the current
snapshot; original import text is not silently replaced by those corrections.

A third explicit conflict policy, `create_new_revisions`, keeps existing local
product metadata and creates new immutable dataset/model snapshots for conflicting
content. The product remains the same; affected version/batch/dataset references
are remapped where required. Old IDs/content are never overwritten. Models record
the original imported ID/revision/hash in their fit description; changed datasets
retain an import mapping and original dataset fingerprint. Identical re-imports,
including repeated conflicting review-only packages, are deduplicated. Standalone
metadata conflicts without a source dataset still require the normal skip/fork
rules. Explicit import-as-distinct intentionally creates another independent copy.

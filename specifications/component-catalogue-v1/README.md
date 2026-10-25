# Component catalogue, version 1

Status: Android Phase 1 metadata architecture, 2026-10-12. This is the portable
wire specification. Python support is deferred to root roadmap Phase 7. The JSON
contains no Kotlin class names and lives outside Python runtime loading paths.
The existing shared `data/materials/webbing_material_models_v1.json` remains the
sole numerical authority for installed legacy laws.

The normative implementation consists of
[records](../../app/src/main/java/org/linesolver/base/material/catalogue/CatalogueRecords.kt),
[codec/identity](../../app/src/main/java/org/linesolver/base/material/catalogue/CatalogueCodec.kt),
[validation](../../app/src/main/java/org/linesolver/base/material/catalogue/CatalogueValidation.kt)
and the [legacy adapter](../../app/src/main/java/org/linesolver/base/material/catalogue/LegacyV1Catalogue.kt).
This specification describes their portable semantics; it does not enable a new
constitutive evaluator, material selector, user editor or rig topology.

## Documents and units

The root object requires integer `schema_version: 1`. Optional arrays `products`,
`versions`, `batches`, `datasets`, `models`, `definitions` and `instances` default
to empty arrays. `units` defaults to the following **exact** object:

```json
{"force":"N","length":"m","linear_density":"kg/m","mass":"kg","energy":"J","strain":"1"}
```

Typed values use these SI units. Strain is engineering strain `(L - L0) / L0`.
The existing material integral is energy per reference length, J/m; multiplying
by reference length in m gives energy in J. Human documentation/plot examples use
ISO 80000 quotient notation, e.g. `T/kN`, `L/m`, `U/J`, and `ε/1`.
Phase 1 does not change existing UI/plot labels.

Unknown fields, unknown enum tokens, missing required fields, incompatible schema
versions and nonfinite typed numbers are rejected. Decoding is not an import-time
repair process. Missing or explicitly null optional values remain unknown; they
do not become zero, a working load, a breaking strain, or a product default.

### Records

Names below are serialized JSON names. Fields marked `?` are nullable and default
to null. Lists default to empty unless stated otherwise. Enum tokens are lowercase.
Required fields have no implicit value unless the table specifies a default.

| Record | Fields and rules |
|---|---|
| Product | `id`, `category` (`webbing`, `bungee`, `connector`), `manufacturer` (nullable), `name`; `aliases`, `provenance`, `restrictions`; `specifications` defaults to empty specifications; `legacy_records` holds original-record references. |
| ProductionVersion | `id`, `product_id`, documented `label`, `provenance`. This is a production/generation/weave identity, not a mathematical fit revision. |
| ProductionBatch | `id`, `product_id`, genuine traceable `batch_code`, `provenance`, `version_id?`; `synthetic_example` defaults false. Only fixtures use true. Different measurements do not imply different batches. |
| MeasurementDataset | `id`, `product_id`, `source`; `version_id?`, `batch_id?`, `original_units?`, `procedure?`, `conditions?`; `raw_data_status` (`raw`, `derived`, `unknown`, default `unknown`); `uncertainty_notes`, `conflict_flags`; `points?`, `legacy_record?`, `raw_payload?`. Inline points and an external legacy curve cannot coexist. |
| MaterialModel | `id`, `revision`, `content_fingerprint`, `product_id`, `type` (`pchip`, `provisional_linear`), `dataset_ids`, `domain`; `version_id?`, `batch_id?`; `source_quality` (`supplied`, `provisional`, `review_only`, default `supplied`), `curated_default` (default false), `fit_description?`, `fit_point_indices?`, `specifications` (empty default), `legacy_record?`. |
| ComponentDefinition | `id`, `product_id`, `name`. A reusable component definition, not a physical installation. |
| ComponentInstance | `id`, `definition_id`, pinned `model?`, `version_id?`, `batch_id?`, `bungee?`, `connector?`. Several instances may use the same definition/model. An absent model means no selected law; it never means “use the default”. |

Additional value objects:

| Object | Fields and interpretation |
|---|---|
| Provenance | Nonblank `citation`; `url?`, `date?`, `note?`. Date uses ISO year, year-month or calendar-date spelling; absent means unknown. A URL is stored as evidence, never fetched by this module. |
| Specifications | `density_kg_per_m?`, `mbs_n?`, `mass_kg?`, `source?`, `strength_basis?`, `termination_strengths`; `information` defaults to `{}`. The last field preserves uninterpreted informational source metadata, including explicit source units such as `width_mm`; it is never read as a solver parameter. |
| StrengthSpecification | `termination`, `strength_n?`, `compatible?`, `source?`, `note?`. No sum of strengths or automatic reduction is implied. MBS is not WLL. |
| SourceUnits | `force`, `force_to_n`, `strain`, `strain_to_fraction`. Version 1 supports exact conversions `N → 1`, `kN → 1000`, `1 → 1`, `% → 0.01`. Stored point values are already SI; factors are provenance, not instructions to multiply the stored values again. |
| MeasurementPoint | `strain`, `force_n`, `origin` (`measured`, `supplied`, `derived`, `synthetic_origin`). A synthetic origin is only `(0, 0)` at index zero. A genuinely measured `(0, 0)` can be tagged `measured`. No origin is inserted automatically. |
| ModelDomain | Positive finite `supported_max_strain`; `supplied_range?` (`lower`, `upper`); `range_evidence` (`measured`, `supplied`, default `supplied`); `compression` fixed `tension_only_zero`; `above_maximum` fixed `forbidden`; `continuation` (`none`, `legacy_bounded`, default `none`); `continuation_note?`. |
| ModelReference | `model_id`, `revision?`, `content_fingerprint?`, `version_id?`, `batch_id?`, `dataset_ids?`. Resolution requires nonempty revision and fingerprint; omitted context means “no additional assertion”, not a different model. If context is provided it must match the selected model. |
| LegacyRecordReference | `model_id`, `fingerprint`; `resource` fixed to `webbing_material_models_v1.json` (default). The current installation must provide that exact content identity. |
| BungeeConfiguration | `rest_length_m?`, `folded_legs?`, `rubber_layer_count?`, `limiter_length_m?`. Positive lengths/counts when supplied; limiter length cannot be shorter than rest length. Fields describe a future configuration only. Layer count is not a force multiplier. |
| ConnectorMetadata | `configuration?`, `loading_direction?`, `note?`. There are no connector properties, load paths, calculated masses, numbering or force-sharing rules in this phase. |

Original force/displacement units that need an unknown gauge length or preload
reference cannot be converted into strain by this schema. Keep those source cells
and units in `raw_payload`, with conflicts/uncertainty and review-only status until
a future explicit convention is supported. Do not relabel displacement as strain.
An empty original-units object is invalid; a null one states that source-unit
provenance is unknown.

## Identity, revisions and selection

IDs are opaque, case-sensitive strings matching `[A-Za-z0-9][A-Za-z0-9._:/-]*`.
They are unique within each entity table. Model identity is the pair `(id, revision)`;
several revisions of one model ID may coexist. Duplicate pairs are rejected even
if their content appears identical. IDs never come from a mutable display name.
The legacy adapter preserves existing `webbing_id` and `model_id` exactly.
Its deterministic synthetic *record identifiers*, not batch claims, are
`legacy-source:<model_id>` and `legacy-component:<webbing_id>`.

For legacy models, the content fingerprint is the **existing saved-rig hash**:
SHA-256 of the UTF-8 compact Kotlin `metadata.toString()` with its original field
order and number spelling. Revision is `legacy-sha256-<fingerprint>`. Do not
canonicalize that original metadata, add flags to it, or recompute this hash using
the new catalogue canonicalizer. No historical identity is redefined.

For new records, `CatalogueIdentity.seal` calculates SHA-256 of a canonical JSON
object with keys `model` and `datasets`:

1. `model` is the fully default-expanded model record, excluding
   `content_fingerprint` and `curated_default`.
2. `datasets` contains the fully default-expanded cited records in `dataset_ids`
   order. Changing data, fit indices, domain, specifications, source or revision
   changes identity. Product display labels are not constitutive parameters and
   are not part of this hash.
3. Object keys sort by UTF-16 code units, with no Unicode normalization. Strings
   use JSON escaping (quote/backslash and controls escaped, `/` and other Unicode
   characters unescaped). Numbers use plain base-10 spelling without exponents,
   trailing fractional zeroes or a decimal point for integers; negative zero is
   `0`. Conversion starts from the decoded finite double's decimal spelling.
   Arrays retain order. UTF-8 bytes are hashed to 64 lowercase hexadecimal digits.

This is the catalogue-v1 canonical form, **not RFC 8785**. Phase 7 must reproduce
the fixture bytes and hashes before accepting cross-platform writes; it must not
assume a Python JSON library's default double formatter is identical. Hashes are
content consistency checks, not signatures or authenticity proofs.

`CatalogueCodec.encode` also sorts the root entity arrays by ID (models by ID then
revision), expands defaults, and emits compact canonical JSON. Point arrays and
reference lists are never reordered. Encode/decode/encode is byte-stable.

`CatalogueSelection.resolve` validates the document and then resolves the exact
model ID, revision, fingerprint and any supplied context assertions. A missing,
unknown or ambiguous revision, mismatched fingerprint or review-only model throws
an explicit error. There is no “latest revision” shortcut. Exactly one optional
curated default may exist per product; `defaultForNewSelection` is an explicit
new-selection operation, never a restore operation. A changed default does not
invalidate or alter a saved model reference. Context-specific defaults are deferred.

## Datasets, fits and validation

`CatalogueValidation.validate` returns deterministic, sorted `(path, code, severity)`
issues. `requireValid`, decoding and encoding reject `error` issues. `review`
issues retain the source for inspection but cannot make it usable by a selectable
model. Structural/reference errors do not become review-only numerical data.

| Source state | Result |
|---|---|
| At least two strictly increasing positive force/strain points | `monotonic_candidate`; eligibility for a fit is not experimental validation. |
| Exactly one positive point | `provisional_candidate`; only explicitly provisional linear candidate records are permitted. |
| No usable points, including only an origin | `catalogue_only`; no selected numerical law. |
| Negative, contradictory zero, repeated, reversed, nonmonotonic or conflicting observations | `review_only`; original order/values retained. Any selectable model citing them fails validation. |
| Unparseable source cells | Retain in `raw_payload`; review only, never numerical input. |
| Nonfinite typed point | Hard JSON/quantity error; raw text can still be retained as source evidence. |
| Legacy external reference | `external_legacy`; requires binding to the original repository, not a claim of measured/raw data. |

All product/version/batch/dataset/definition/instance references must exist and
belong to the same product/context. Known dataset version and batch identities
must match the model exactly, including absent identities. A referenced batch's
documented version must agree. Duplicate dataset references, invalid units or
conversion factors, negative/nonfinite mass/strength/length quantities, nonpositive
counts, inverted ranges and unresolved selections are errors.

`fit_point_indices`, when present, is a strictly increasing, nonempty sequence of
valid zero-based indices into the one cited dataset. It allows two PCHIP fit
definitions to cite different subsets of one source without copying its curve.
The entire parent dataset must still be eligible: a subset cannot hide a conflict.
Combining independent series requires future explicit curation; version 1 rejects
automatic multi-dataset pooling for selectable inline models. Review-only model
records may describe unresolved work but cannot be defaults or resolve for use.

For new inline candidates, the supported upper limit cannot exceed the selected
source points. Provisional candidates have only a single source-supported point,
not a measured interval. Measured ranges require measured evidence; supplied or
derived source data cannot be upgraded to measurements. Synthetic origins do not
extend the supported observation interval. Classification distinguishes compression,
measured, supplied, extrapolated (including an imposed-origin interval) and forbidden
regions. New records cannot request the legacy continuation rule. No breaking
strain is synthesized or stored as an observation.

`legacy_bounded` describes the existing law's continuation only. The adapter reads
the exact existing `Webbing.maxStrain`, `suppliedDomain`, continuation and reason;
it does not fit or extrapolate a new curve. The original solver's endpoint/rupture
handling continues to apply. A domain classification is metadata, not a safety
or intact-state decision.

## Android legacy binding and saved rigs

```kotlin
val adapter = repository.versionedCatalogue()
val records = adapter.document
val reference = records.models.first().reference()
val sameExistingWebbing = adapter.resolve(reference)
val portableText = CatalogueCodec.encode(records)
val verifiedRecords = adapter.load(portableText)
```

The adapter is a lazy view over the already loaded repository. It stores external
references instead of point arrays; `points(datasetId)` reads source knots for
inspection, including the existing audited imposed-origin annotation. Resolving
a legacy model returns the same existing `Webbing` instance. The UI, selector,
solver and persistence paths do not start using new defaults.

`CatalogueCodec.decode` alone cannot verify an external resource. Use
`LegacyV1Catalogue.load` to check external hashes, product identity and the exact
derived dataset/model views against the installed numerical authority before
trusting them. An altered domain/specification with an unchanged legacy hash is
rejected at this binding step. `resolve` accepts only the generated, verified view.

One legacy source-record wrapper per model does **not** assert that each fit was
an independent experiment. Raw series identity, original units, conditions and
dates remain unknown when the legacy record does not establish them. Product
names containing MK/generation text are retained but not heuristically split into
new production-version records. Researched batch specifications remain accessible
through `metadata(ref)`; they are never assigned to the historical curve.
Conflicting per-variant mass/MBS remain on their exact models; no average or
product-wide default overwrites them. Full original restrictions, source notes,
termination tests and batch research remain in the referenced original metadata.

Rig-library schemas **1 and 2 are unchanged**, including migration to the current
schema, legacy Uniform semantics, optional fingerprints and generator recipes.
No new rig field is written. Existing model-ID/fingerprint checks and existing
confirmation for recognized pre-2026-10-10 material revisions remain unchanged.
Those confirmations already warn that accepting revised specifications may alter
results; Phase 1 does not silently restore unavailable historical laws or remove
that confirmation. Unknown old fingerprints remain blocked. The new exact resolver
rejects unavailable historical revisions instead of upgrading them.

## Future local overlays and import/export

No overlay store, merger, editor or user-facing importer/exporter is implemented.
Reserve `shipped:` for future curated IDs and `user:<namespace-uuid>:<entity-uuid>`
for locally owned products, versions, batches, datasets, models and definitions.
Create the namespace once per local library and retain it across exports/restores;
record IDs never change with a rename. Export the namespace and a dependency-complete
document. `example:` is test-only. Legacy unprefixed IDs remain reserved.

On future import, validate the entire document and all dependency/legacy bindings
before committing atomically. Identical `(ID, revision, fingerprint)` records may
deduplicate; the same `(ID, revision)` with different content is a hard conflict.
Never overwrite shipped records, automatically merge source series, change an old
revision in place, or select a newer product default on load. An intentional edit
creates a new immutable model revision and fingerprint, preserving every revision
referenced by a rig. Forks into a different namespace require an explicit recorded
ID/reference mapping and new fingerprints. Resolve conflicts before persistence;
unknown future fields/versions must not be rewritten by the v1 codec.

## Portable fixtures and Phase 7 handoff

The [test-only examples](../../app/src/test/resources/catalogue-v1/examples.json)
contain independent series for one product, two fits of one dataset, a documented
synthetic MK2 revision, an explicitly synthetic batch, a sparse provisional candidate,
a catalogue-only product, review-only conflicting data and future bungee/connector
instances. They never enter the production material selector or Android assets.
[Invalid units](../../app/src/test/resources/catalogue-v1/invalid-units.json),
[invalid references](../../app/src/test/resources/catalogue-v1/invalid-reference.json)
and [review-only knots](../../app/src/test/resources/catalogue-v1/review-knots.json)
exercise hard-error versus retained-review behavior. Kotlin fixtures/tests verify
canonical bytes, identities, classification and selection behavior.

Phase 7 must first port the strict codec, validations, ID/default/overlay rules and
these exact fixture bytes/hashes to an independent Python implementation. Preserve
the existing legacy fingerprint algorithm or migrate through an explicit identity
map; do not replace it with Python's default JSON formatting. Reconcile the one
shared numerical authority only after a tested migration. Then implement only the
new laws and static bungee/connection mechanics supported by completed Android
phases, and build common force/tangent/inverse/energy/domain/rig fixtures with
justified tolerances. Compare saved-rig migrations and every supported static case;
document the existing standing-contact differences and unsupported cases. Python
does not run inside Android. No new-feature Python parity or physical validation
is claimed by Phase 1.

# Android webbing catalogue import

Originally implemented in Phase 2. Kotlin/JVM generation and Android evaluation;
Python integration is deferred to Phase 7. The original
[`webbings.json`](../data/materials/webbings.json), legacy runtime JSON,
workbooks and archived audits remain read-only.

From this repository root, using JDK 25:

```sh
./gradlew --max-workers=1 :app:generateWebbingCatalogue
./gradlew --max-workers=1 :app:verifyWebbingCatalogue
./gradlew --max-workers=1 \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
```

Generation explicitly writes the three checked-in output files; ordinary tests
and verification only compare them. No Python, network access, timestamps or
random IDs are used by the importer. `CatalogueImportMain` is a JVM command entry
point on the existing Android unit-test classpath, and calls the production
`WebbingCatalogueImport`. No extra dependencies are required. Gradle's
`syncMaterialDatabase` copies the generated catalogue alongside the unchanged
legacy JSON into the APK. Regenerate and verify before building after a source or
rules change. The app reads the generated records, not the raw import file.

| File | Purpose |
|---|---|
| `reconciliation-v1.json` | Reviewed manufacturer aliases, guarded product mappings, exclusions, batch/termination transcriptions and their reasons. These are decisions about supplied evidence, not replacement measurements. |
| `webbing-catalogue-v1.json` | Portable Phase 1 schema: products, versions, batches, datasets, models and definitions. Legacy numerical arrays are external references only. |
| `inventory-v1.json` | Source/rules hashes, conversions, actual counts and one accounting row for every source record, including raw-data classification, common-force comparisons, model reuse and restrictions. |
| `RECONCILIATION.md` | Generated human-readable inventory, restrictions, exclusions and side-by-side specification comparisons. |

The schema remains [component catalogue v1](../specifications/component-catalogue-v1/README.md).
Every source record is retained as a dataset with its complete `raw_payload` and
source ID. Canonical JSON normalizes key order and equivalent numeric spellings;
the original file preserves its original bytes. Converted points are SI and
keep their original order. No sorting, averaging, smoothing, curve stitching or
fabricated missing specifications occurs.

The source keys `stretch[].kn` and `stretch[].percent` explicitly identify force
in kN and engineering elongation in percent. Source notes for MOTM, Axiom MK2,
2FACES and other products explicitly corroborate mass in g/m, dimensions in mm
and strength in kN. The import performs `kn × 1000 → N`, `percent / 100 → 1` and
`weight × 0.001 → kg/m`. Dimensions remain explicitly named `width_mm` and
`thickness_mm` informational fields; no truncation to integer widths. Introduction
timestamps are preserved and converted from Unix milliseconds to UTC dates;
they do not become measurement dates. Notes, prices, currencies, status, colours,
sellers and unknown fields remain in the raw source payload.

New model IDs are `webbings:model:<source-id>`, revision `1`; source datasets use
`webbings:dataset:<source-id>`. Matched product IDs stay unchanged. Unmatched
products use `webbings:product:<source-id>`. Duplicate source IDs abort import;
unresolved duplicate product names remain separate review datasets. Documented
copied curves may share an existing model. Case, punctuation and spacing
normalization never removes generation, width or colourway tokens. Cross-brand
and nontrivial aliases require explicit evidence in the rules. Ambiguous identities
are not merged or made runnable. Classic/Desert Jelly PRO, for example, cannot be
assigned to the legacy unspecified colourway automatically.

Multiple independent datasets/models may belong to one product. Identical
positive knots for the same product (within `1e-14` strain for decimal-conversion
roundoff, exact force equality) reuse an existing model instead of making another
numerical authority. The inventory retains specification disagreements even when
the curve is reused. No legacy evaluator or specification changes.

New nonlinear models use the existing Kotlin PCHIP and analytical integral;
one positive point uses its two-knot linear special case with an explicitly
imposed origin. The supplied range excludes that origin. A single point has no
measured interval and its approximation is dashed. Every new model stops at its
last supplied point, with no upper continuation, even when MBS is known. Missing
mass blocks rig selection/calculation; missing MBS remains unknown. Curves beyond
the declared source strength, nonmonotonic series and unresolved measurement
contradictions are review-only. Existing legacy continuation is unchanged.

For imported laws only, endpoint force and exact source-knot inverse pairs are
returned directly to avoid polynomial roundoff and loss of inverse precision at
a zero terminal slope. Between knots, the existing PCHIP evaluator, tangent,
integral and bisection are used. `catalogue_max_strain` is included in derived
diagnostic snapshots, so replay cannot accidentally reconstruct legacy upper
continuation. This metadata projection exists only in memory/reports; no second
persisted array database is maintained.

`CatalogueMaterialRepository.document` is the combined schema view;
`LegacyV1Catalogue` accepts the original authority only. A model's exact
ID/revision/content reference resolves explicitly. Existing rigs keep their
original compact-JSON SHA-256 identity. New selections save the Phase 1 catalogue
content fingerprint, which includes ID/revision/domain/dataset/specifications
and excludes preferred-default choice. Changed or missing content blocks loading
without substitution. The existing rig schemas need no migration.

Batch `W26DZ` and batch `2020` have separate `ProductionBatch` records. Their
strengths are in `specifications.information.batch_strength_evidence`, keyed by
batch ID, with explicitly null stretch association. Stretch models remain
batch-unspecified. Model specifications never inherit a batch-only or
termination-only strength. Termination ratings stay separate evidence and never
become a nominal strength sum, strength reduction or rig hardware selection.

This delivery uses the supplied offline evidence and retained local audit; it
does not re-verify manufacturer websites. Active/discontinued status is the source
snapshot, not a statement about current availability. MBS is not WLL. Approximate,
digitised and unspecified-protocol points are not labelled experimentally verified.
The read-only source-details UI shows restrictions and original notes, including
upstream inferred values. No simulator safety certification is implied.

For future catalogue updates, review changed evidence and issue an explicit new
immutable model revision (and retain old bindings where required). A changed
fingerprint must never be silently accepted for an old saved selection. The
Phase 2 generator's revision `1` is the first shipped snapshot, not an automatic
revision-history manager. Custom editing/overlay lifecycle belongs to Phase 3;
Python readers, shared authority reconciliation and new-feature parity belong to
Phase 7.

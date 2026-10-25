package org.linesolver.base.material.catalogue

enum class IssueSeverity { error, review }
data class CatalogueIssue(val path: String, val code: String, val severity: IssueSeverity)
enum class DatasetDisposition { monotonic_candidate, provisional_candidate, catalogue_only, review_only, external_legacy }
data class DatasetAssessment(val disposition: DatasetDisposition, val reasons: List<String> = emptyList())

object CatalogueValidation {
    private val hash = Regex("[0-9a-f]{64}")
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*")

    /** Source order is evidence: do not sort, merge, repair, or manufacture an origin. */
    fun assess(dataset: MeasurementDataset): DatasetAssessment {
        val reasons = mutableListOf<String>()
        if (dataset.conflictFlags.isNotEmpty()) reasons += "conflicting_source"
        if (dataset.rawPayload != null && dataset.points == null) reasons += "unparsed_source"
        val points = dataset.points.orEmpty()
        if (points.any { !it.strain.isFinite() || !it.forceN.isFinite() || it.strain < 0 || it.forceN < 0 })
            reasons += "invalid_physical_point"
        if (points.withIndex().any { (i, p) -> p.origin == PointOrigin.synthetic_origin &&
                (i != 0 || p.strain != 0.0 || p.forceN != 0.0) }) reasons += "invalid_synthetic_origin"
        if (points.any { (it.strain == 0.0) != (it.forceN == 0.0) }) reasons += "contradictory_zero"
        if (points.zipWithNext().any { (a, b) -> b.strain <= a.strain || b.forceN <= a.forceN })
            reasons += "nonmonotonic_points"
        return when {
            reasons.isNotEmpty() -> DatasetAssessment(DatasetDisposition.review_only, reasons)
            dataset.legacyRecord != null -> DatasetAssessment(DatasetDisposition.external_legacy)
            points.count { it.strain > 0 && it.forceN > 0 } == 0 -> DatasetAssessment(DatasetDisposition.catalogue_only)
            points.count { it.strain > 0 && it.forceN > 0 } == 1 -> DatasetAssessment(DatasetDisposition.provisional_candidate)
            else -> DatasetAssessment(DatasetDisposition.monotonic_candidate)
        }
    }

    fun requireValid(document: ComponentCatalogue) {
        val errors = validate(document).filter { it.severity == IssueSeverity.error }
        require(errors.isEmpty()) { errors.joinToString("; ") { "${it.path}: ${it.code}" } }
    }

    fun validate(doc: ComponentCatalogue): List<CatalogueIssue> {
        val issues = mutableListOf<CatalogueIssue>()
        fun issue(path: String, code: String, severity: IssueSeverity = IssueSeverity.error) {
            issues += CatalogueIssue(path, code, severity)
        }
        fun check(ok: Boolean, path: String, code: String) { if (!ok) issue(path, code) }
        fun ids(kind: String, values: List<String>) {
            values.forEach { check(idPattern.matches(it), "$kind/$it", "invalid_id") }
            values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { issue("$kind/$it", "duplicate_id") }
        }
        fun positive(value: Double?, path: String, zero: Boolean = false) {
            if (value != null) check(value.isFinite() && if (zero) value >= 0 else value > 0, path, "invalid_quantity")
        }
        fun provenance(p: Provenance, path: String) {
            check(p.citation.isNotBlank(), path, "missing_provenance")
            check(p.date == null || Regex("\\d{4}(-\\d{2}(-\\d{2})?)?").matches(p.date), path, "invalid_source_date")
        }
        fun specs(s: Specifications, path: String) {
            positive(s.densityKgPerM, "$path/density_kg_per_m", true)
            positive(s.massKg, "$path/mass_kg", true); positive(s.mbsN, "$path/mbs_n")
            s.source?.let { provenance(it, path) }
            s.terminationStrengths.forEachIndexed { i, t ->
                check(t.termination.isNotBlank(), "$path/termination_strengths/$i", "missing_termination")
                positive(t.strengthN, "$path/termination_strengths/$i/strength_n")
                t.source?.let { provenance(it, path) }
            }
        }
        fun legacy(ref: LegacyRecordReference, path: String) {
            check(ref.resource == "webbing_material_models_v1.json", path, "unsupported_legacy_resource")
            check(idPattern.matches(ref.modelId) && hash.matches(ref.fingerprint), path, "invalid_legacy_reference")
        }
        check(doc.schemaVersion == 1, "schema_version", "unsupported_schema_version")
        check(doc.units == CatalogueUnits(), "units", "unsupported_units")
        ids("products", doc.products.map { it.id }); ids("versions", doc.versions.map { it.id })
        ids("batches", doc.batches.map { it.id }); ids("datasets", doc.datasets.map { it.id })
        ids("definitions", doc.definitions.map { it.id }); ids("instances", doc.instances.map { it.id })
        // Model IDs are families of immutable revisions; only the pair must be unique.
        doc.models.forEach { check(idPattern.matches(it.id) && idPattern.matches(it.revision), "models/${it.id}", "invalid_id_or_revision") }
        doc.models.groupBy { it.id to it.revision }.filterValues { it.size > 1 }.forEach { (key, _) ->
            issue("models/${key.first}/${key.second}", "duplicate_model_revision")
        }
        val products = doc.products.associateBy { it.id }
        val versions = doc.versions.associateBy { it.id }
        val batches = doc.batches.associateBy { it.id }
        val datasets = doc.datasets.associateBy { it.id }
        val definitions = doc.definitions.associateBy { it.id }
        fun refs(product: String, version: String?, batch: String?, path: String) {
            check(product in products, path, "unknown_product")
            if (version != null) check(versions[version]?.productId == product, path, "unknown_or_foreign_version")
            if (batch != null) {
                check(batches[batch]?.productId == product, path, "unknown_or_foreign_batch")
                batches[batch]?.versionId?.let { check(it == version, path, "batch_version_mismatch") }
            }
        }
        doc.products.forEach { p ->
            val path = "products/${p.id}"
            check(p.name.isNotBlank() && (p.manufacturer == null || p.manufacturer.isNotBlank()), path, "invalid_product_name")
            p.provenance.forEach { provenance(it, path) }; specs(p.specifications, path)
            check(p.legacyRecords.distinct().size == p.legacyRecords.size, path, "duplicate_legacy_reference")
            p.legacyRecords.forEach { legacy(it, path) }
        }
        doc.versions.forEach { v ->
            val path = "versions/${v.id}"; refs(v.productId, null, null, path)
            check(v.label.isNotBlank(), path, "missing_revision_label"); provenance(v.provenance, path)
        }
        doc.batches.forEach { b ->
            val path = "batches/${b.id}"; refs(b.productId, b.versionId, null, path)
            check(b.batchCode.isNotBlank(), path, "missing_batch_code"); provenance(b.provenance, path)
        }
        doc.datasets.forEach { d ->
            val path = "datasets/${d.id}"; refs(d.productId, d.versionId, d.batchId, path); provenance(d.source, path)
            check(d.points == null || d.legacyRecord == null, path, "duplicate_numerical_authority")
            d.legacyRecord?.let { legacy(it, path) }
            d.originalUnits?.let { u ->
                check(u.forceToN.isFinite() && mapOf("N" to 1.0, "kN" to 1000.0)[u.force] == u.forceToN &&
                    u.strainToFraction.isFinite() && mapOf("1" to 1.0, "%" to .01)[u.strain] == u.strainToFraction,
                    path, "invalid_source_units_or_conversion")
            }
            // JSON cannot carry NaN/Infinity as numbers. Preserve malformed text only in raw_payload.
            check(d.points.orEmpty().all { it.strain.isFinite() && it.forceN.isFinite() }, path, "nonfinite_point")
            assess(d).reasons.forEach { issue(path, it, IssueSeverity.review) }
        }
        doc.models.filter { it.curatedDefault }.groupBy { it.productId }.filterValues { it.size > 1 }.keys.forEach {
            issue("products/$it", "ambiguous_default")
        }
        doc.models.forEach { m ->
            val path = "models/${m.id}/${m.revision}"; refs(m.productId, m.versionId, m.batchId, path)
            check(hash.matches(m.contentFingerprint), path, "invalid_fingerprint"); specs(m.specifications, path)
            check(m.datasetIds.isNotEmpty(), path, "missing_dataset_reference")
            check(m.datasetIds.distinct().size == m.datasetIds.size, path, "duplicate_dataset_reference")
            val cited = m.datasetIds.mapNotNull { datasets[it] }
            m.datasetIds.forEach { id -> check(id in datasets, path, "unknown_dataset:$id") }
            cited.forEach { d ->
                check(d.productId == m.productId, path, "foreign_dataset")
                check(d.versionId == m.versionId && d.batchId == m.batchId, path, "dataset_context_mismatch")
            }
            positive(m.domain.supportedMaxStrain, "$path/supported_max_strain")
            check(m.domain.compression == "tension_only_zero" && m.domain.aboveMaximum == "forbidden", path, "unsupported_domain_rule")
            m.domain.suppliedRange?.let { range ->
                positive(range.lower, "$path/range/lower", true); positive(range.upper, "$path/range/upper", true)
                check(range.lower <= range.upper, path, "invalid_range")
            }
            check(!m.curatedDefault || m.sourceQuality != SourceQuality.review_only, path, "review_only_default")
            if (m.legacyRecord != null) {
                legacy(m.legacyRecord, path)
                check(m.id == m.legacyRecord.modelId && m.contentFingerprint == m.legacyRecord.fingerprint &&
                    m.revision == "legacy-sha256-${m.contentFingerprint}", path, "legacy_identity_mismatch")
                check(cited.size == 1 && cited.single().legacyRecord == m.legacyRecord, path, "legacy_dataset_mismatch")
            } else {
                check(cited.none { it.legacyRecord != null }, path, "external_dataset_requires_legacy_binding")
                check(m.domain.continuation == ContinuationRule.none, path, "unsupported_new_continuation")
                if (cited.size == m.datasetIds.size && cited.map { it.id }.distinct().size == cited.size) {
                    val expected = runCatching { CatalogueIdentity.fingerprint(m, cited) }.getOrNull()
                    check(m.contentFingerprint == expected, path, "content_fingerprint_mismatch")
                }
                if (m.sourceQuality != SourceQuality.review_only) {
                    // Combining independent series requires future explicit curation, never silent pooling.
                    check(cited.size == 1, path, "unresolved_dataset_combination")
                    cited.forEach { d ->
                        val allPoints = d.points.orEmpty()
                        val indices = m.fitPointIndices
                        check(indices == null || (indices.isNotEmpty() && indices.all { it in allPoints.indices } &&
                            indices.zipWithNext().all { (a, b) -> b > a }), path, "invalid_fit_point_indices")
                        val fitPoints = indices?.mapNotNull { allPoints.getOrNull(it) } ?: allPoints
                        // A subset cannot conceal a contradictory parent series.
                        check(assess(d).disposition !in listOf(DatasetDisposition.review_only, DatasetDisposition.catalogue_only), path, "unusable_parent_dataset")
                        val assessment = assess(d.copy(points = fitPoints))
                        val wanted = if (m.type == LawType.pchip) DatasetDisposition.monotonic_candidate else DatasetDisposition.provisional_candidate
                        check(assessment.disposition == wanted, path, "dataset_not_eligible_for_law")
                        val positivePoints = fitPoints.filter { it.strain > 0 && it.forceN > 0 }
                        if (positivePoints.isNotEmpty()) {
                            m.specifications.mbsN?.let { mbs ->
                                check(positivePoints.none { it.forceN > mbs }, path, "source_curve_exceeds_mbs")
                            }
                            check(m.domain.supportedMaxStrain <= positivePoints.maxOf { it.strain }, path, "unsupported_extension")
                            m.domain.suppliedRange?.let { r ->
                                val evidence = fitPoints.filter { it.origin != PointOrigin.synthetic_origin }
                                val lower = if (m.type == LawType.provisional_linear) positivePoints.minOf { it.strain } else evidence.minOfOrNull { it.strain } ?: Double.NaN
                                check(r.lower >= lower && r.upper <= positivePoints.maxOf { it.strain }, path, "unsupported_supplied_range")
                                if (m.domain.rangeEvidence == RangeEvidence.measured)
                                    check(evidence.all { it.origin == PointOrigin.measured }, path, "unmeasured_range")
                            }
                        }
                    }
                    if (m.type == LawType.provisional_linear) check(m.sourceQuality == SourceQuality.provisional, path, "missing_provisional_quality")
                }
            }
        }
        doc.definitions.forEach { d ->
            refs(d.productId, null, null, "definitions/${d.id}")
            check(d.name.isNotBlank(), "definitions/${d.id}", "missing_name")
        }
        doc.instances.forEach { instance ->
            val path = "instances/${instance.id}"; val definition = definitions[instance.definitionId]
            check(definition != null, path, "unknown_definition")
            if (definition != null) {
                refs(definition.productId, instance.versionId, instance.batchId, path)
                val category = products[definition.productId]?.category
                check(instance.bungee == null || category == ProductCategory.bungee, path, "wrong_bungee_category")
                check(instance.connector == null || category == ProductCategory.connector, path, "wrong_connector_category")
                instance.model?.let { ref ->
                    val model = runCatching { CatalogueSelection.checkReference(doc, ref) }.getOrElse {
                        issue(path, "unresolved_selection:${it.message}"); null
                    }
                    if (model != null) check(model.productId == definition.productId && model.versionId == instance.versionId &&
                        model.batchId == instance.batchId, path, "instance_model_context_mismatch")
                }
            }
            instance.bungee?.let { b ->
                positive(b.restLengthM, "$path/rest_length_m"); positive(b.limiterLengthM, "$path/limiter_length_m")
                check(b.foldedLegs == null || b.foldedLegs > 0, path, "invalid_folded_legs")
                check(b.rubberLayerCount == null || b.rubberLayerCount > 0, path, "invalid_rubber_layer_count")
                check(b.restLengthM == null || b.limiterLengthM == null || b.limiterLengthM >= b.restLengthM, path, "limiter_shorter_than_rest")
            }
        }
        return issues.distinct().sortedWith(compareBy({ it.path }, { it.code }, { it.severity.name }))
    }
}

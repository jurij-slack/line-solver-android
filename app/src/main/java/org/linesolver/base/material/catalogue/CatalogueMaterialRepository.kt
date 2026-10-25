package org.linesolver.base.material.catalogue

import kotlinx.serialization.json.*
import org.linesolver.base.material.*

/** One catalogue, two bindings: untouched legacy evaluators and bounded imported laws. */
class CatalogueMaterialRepository(private val legacy: MaterialRepository, text: String,
    private val retired: Set<String> = emptySet()) : MaterialRepository {
    val document = LegacyV1Catalogue(legacy).load(text)
    private val records = document.models.associateBy { it.id }.also {
        require(it.size == document.models.size) { "Installed repository requires one immutable revision per model ID" }
        require(document.models.filter { it.legacyRecord != null }.map { it.id }.toSet() == legacy.all().map { it.id }.toSet()) {
            "Catalogue must preserve every installed legacy model"
        }
    }
    private val products = document.products.associateBy { it.id }
    private val datasets = document.datasets.associateBy { it.id }
    private val evaluators = document.models.filter { it.sourceQuality != SourceQuality.review_only }.associate { model ->
        model.id to if (model.legacyRecord != null) legacy.get(model.id) else instantiate(model)
    }

    private fun instantiate(m: MaterialModel): Webbing {
        require(m.domain.continuation == ContinuationRule.none)
        val p = products.getValue(m.productId)
        val d = datasets.getValue(m.datasetIds.single())
        val snapshot = if(m.id.startsWith("user:") && p.id.startsWith("user:"))
            d.rawPayload?.get("editor")?.let { CatalogueCodec.json.decodeFromJsonElement<MaterialEdit>(it) } else null
        val points = d.points.orEmpty().let { ps -> m.fitPointIndices?.map { ps[it] } ?: ps }
        val imposed = points.first().strain != 0.0
        val knots = if (imposed) listOf(MeasurementPoint(0.0, 0.0, PointOrigin.synthetic_origin)) + points else points
        val metadata = buildJsonObject {
            put("model_id", m.id); put("webbing_id", m.productId)
            put("manufacturer", if(snapshot!=null)snapshot.brand.takeIf { it.isNotBlank() } ?: "Unspecified brand" else p.manufacturer ?: "Unspecified brand")
            put("product", snapshot?.name?.takeIf { it.isNotBlank() } ?: p.name)
            if (m.id.startsWith("user:")) {
                put("user_owned", true)
                put("production_version", document.versions.firstOrNull { it.id == m.versionId }?.label.orEmpty())
                put("production_batch", document.batches.firstOrNull { it.id == m.batchId }?.let { "${it.batchCode} (${if(it.syntheticExample)"synthetic example" else "user-declared"})" } ?: "Unspecified batch")
            }
            put("interpolation", if (m.type == LawType.provisional_linear) "LINEAR" else "PCHIP")
            if (m.type == LawType.provisional_linear) put("model_type", "provisional_linear_one_point")
            put("model_origin", "imported"); put("catalogue_revision", m.revision); put("catalogue_fingerprint", m.contentFingerprint)
            put("source_citation", d.source.citation); put("dataset_id", d.id)
            put("catalogue_max_strain", m.domain.supportedMaxStrain)
            put("independent_variable", "strain_fraction"); put("tension_unit", "N")
            put("strain_fraction", JsonArray(knots.map { JsonPrimitive(it.strain) }))
            put("tension_N", JsonArray(knots.map { JsonPrimitive(it.forceN) }))
            put("mass_per_m", m.specifications.densityKgPerM?.let(::JsonPrimitive) ?: JsonNull)
            put("breaking_strength_N", m.specifications.mbsN?.let(::JsonPrimitive) ?: JsonNull)
            put("default_for_webbing", m.curatedDefault); put("imposed_origin", imposed)
            m.domain.suppliedRange?.let { put("valid_strain_range", JsonArray(listOf(JsonPrimitive(it.lower), JsonPrimitive(it.upper)))) }
            put("source_method", if (m.id.startsWith("user:")) "User-entered loading points; provenance retained" else if (m.type == LawType.provisional_linear) "Imported one-point provisional approximation" else "Imported supplied force–elongation points (PCHIP)")
            put("source_url", d.source.url?.let(::JsonPrimitive) ?: JsonNull)
            put("source_note", d.source.note.orEmpty())
            put("strength_basis", m.specifications.strengthBasis.orEmpty())
            put("quality", m.sourceQuality.name)
            put("status", d.rawPayload?.get("active")?.let { "Source active=$it; not a validation status" } ?: "Unknown source status")
            put("extrapolation_policy", "forbidden above supplied maximum")
            put("strength_tests", buildJsonArray {
                m.specifications.terminationStrengths.forEach { strength -> add(buildJsonObject {
                    put("termination", strength.termination); put("MBS_N", strength.strengthN?.let(::JsonPrimitive) ?: JsonNull)
                    put("compatible", strength.compatible?.let(::JsonPrimitive) ?: JsonNull)
                    put("note", strength.note.orEmpty())
                }) }
            })
        }
        return Webbing(m.id, metadata)
    }

    fun resolve(reference: ModelReference): Webbing = get(CatalogueSelection.resolve(document, reference).id)
    fun bungeeModels() = document.models.filter { m -> products.getValue(m.productId).category == ProductCategory.bungee &&
        m.id !in retired && m.sourceQuality != SourceQuality.review_only && m.bungeeLaw() != null }
    override fun get(id: String) = requireNotNull(evaluators[id]) { "No supported material law for $id" }
    override fun all() = evaluators.values.sortedWith(compareBy({ it.name.lowercase(java.util.Locale.ROOT) }, { it.id }))
    override fun previousRevisions(id: String) = if (records[id]?.legacyRecord != null) legacy.previousRevisions(id) else emptyList()
    private fun selectable() = all().filter { it.id !in retired && it.density != null && products.getValue(records.getValue(it.id).productId).category == ProductCategory.webbing }
    private fun productChoices() = selectable().groupBy { records.getValue(it.id).productId }.values.map { models ->
        models.firstOrNull { records.getValue(it.id).curatedDefault } ?: models.maxBy { records.getValue(it.id).revision.toIntOrNull() ?: 0 }
    }
    override fun manufacturers() = productChoices().map { it.manufacturer }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    override fun webbings(manufacturer: String) = productChoices().filter { it.manufacturer == manufacturer }
    override fun alternatives(id: String) = super.alternatives(id).filter { it.density != null && (it.id !in retired || it.id == id) }
    override fun limitations(id: String): List<String> {
        val m = records.getValue(id); val product = products.getValue(m.productId)
        val localRestrictions = if(id.startsWith("user:")) datasets.getValue(m.datasetIds.single()).rawPayload?.get("editor")
            ?.let { CatalogueCodec.json.decodeFromJsonElement<MaterialEdit>(it).restrictions.lines().filter { line -> line.isNotBlank() } }.orEmpty() else emptyList()
        return (super.limitations(id) + product.restrictions + localRestrictions +
            if (m.legacyRecord == null) datasets.getValue(m.datasetIds.single()).uncertaintyNotes else emptyList()).distinct()
    }

    /** Review evidence is visible even if that product also has an unrelated curated runnable law. */
    val excludedDatasets get() = document.datasets.filter { it.legacyRecord == null &&
        CatalogueValidation.assess(it).disposition in listOf(DatasetDisposition.review_only, DatasetDisposition.catalogue_only) }
    fun product(dataset: MeasurementDataset) = products.getValue(dataset.productId)
    fun sourceEvidence(modelId: String) = document.datasets.filter {
        it.legacyRecord == null && it.productId == records.getValue(modelId).productId
    }
}

fun Webbing.modelLabel(): String = when {
    id.startsWith("user:") && provisionalLinear -> "User provisional linear"
    id.startsWith("user:") -> "User nonlinear"
    provisionalLinear && metadata["model_origin"]?.jsonPrimitive?.contentOrNull == "imported" -> "Imported provisional linear"
    provisionalLinear -> "Curated provisional linear"
    metadata["model_origin"]?.jsonPrimitive?.contentOrNull == "imported" -> "Imported nonlinear"
    else -> "Curated nonlinear"
}

fun Webbing.modelChoiceLabel(): String = if (id.startsWith("user:"))
    "My model · revision ${metadata["catalogue_revision"]?.jsonPrimitive?.content} · " +
        listOfNotNull(metadata["production_version"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            metadata["production_batch"]?.jsonPrimitive?.content,
            metadata["quality"]?.jsonPrimitive?.content,
            "#${id.takeLast(8)}").joinToString(" · ")
    else modelLabel() + " · " +
    if (metadata["model_origin"]?.jsonPrimitive?.contentOrNull == "imported") "catalogue #${id.substringAfterLast(':')}"
    else metadata["source_method"]?.jsonPrimitive?.contentOrNull.orEmpty()

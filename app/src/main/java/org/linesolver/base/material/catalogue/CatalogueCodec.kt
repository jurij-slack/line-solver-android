package org.linesolver.base.material.catalogue

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import org.linesolver.base.model.materialFingerprint
import java.math.BigDecimal

/** Strict version boundary: unknown fields are rejected rather than lost on re-export. */
@OptIn(ExperimentalSerializationApi::class)
object CatalogueCodec {
    internal val json = Json { encodeDefaults = true; namingStrategy = JsonNamingStrategy.SnakeCase }

    fun decode(text: String): ComponentCatalogue {
        val document = json.decodeFromString<ComponentCatalogue>(text)
        CatalogueValidation.requireValid(document)
        return document
    }

    fun encode(document: ComponentCatalogue): String {
        CatalogueValidation.requireValid(document)
        val ordered = document.copy(
            products = document.products.sortedBy { it.id }, versions = document.versions.sortedBy { it.id },
            batches = document.batches.sortedBy { it.id }, datasets = document.datasets.sortedBy { it.id },
            models = document.models.sortedWith(compareBy({ it.id }, { it.revision })),
            definitions = document.definitions.sortedBy { it.id }, instances = document.instances.sortedBy { it.id }
        )
        return canonical(json.encodeToJsonElement(ordered))
    }

    // Plain decimal numbers, no insignificant zeroes/exponents; UTF-16 key ordering; list order retained.
    // This is the catalogue-v1 canonical form, not a claim of RFC 8785 conformance.
    internal fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") {
            JsonPrimitive(it.key).toString() + ":" + canonical(it.value)
        }
        is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonical)
        is JsonPrimitive -> when {
            value.isString || value is JsonNull || value.booleanOrNull != null -> value.toString()
            else -> BigDecimal(value.content).stripTrailingZeros().toPlainString()
        }
    }
}

object CatalogueIdentity {
    /** New model revisions hash their full record and cited datasets, excluding hash/default choice.
     * Legacy fingerprints instead remain the exact existing metadata.toString() SHA-256. */
    fun fingerprint(model: MaterialModel, datasets: List<MeasurementDataset>): String {
        require(model.legacyRecord == null) { "Legacy identity is owned by the original material repository" }
        val record = CatalogueCodec.json.encodeToJsonElement(model).jsonObject
        val payload = buildJsonObject {
            put("model", JsonObject(record - "content_fingerprint" - "curated_default"))
            put("datasets", JsonArray(model.datasetIds.map { id ->
                CatalogueCodec.json.encodeToJsonElement(datasets.single { it.id == id })
            }))
        }
        return materialFingerprint(CatalogueCodec.canonical(payload))
    }

    fun seal(model: MaterialModel, datasets: List<MeasurementDataset>) =
        model.copy(contentFingerprint = fingerprint(model, datasets))
}

/** Exact selection only. No product-default fallback, including when only one revision exists. */
object CatalogueSelection {
    fun resolve(document: ComponentCatalogue, reference: ModelReference): MaterialModel {
        CatalogueValidation.requireValid(document)
        return checkReference(document, reference)
    }

    internal fun checkReference(document: ComponentCatalogue, reference: ModelReference): MaterialModel {
        require(!reference.revision.isNullOrBlank()) { "missing_revision: ${reference.modelId}" }
        require(!reference.contentFingerprint.isNullOrBlank()) { "missing_fingerprint: ${reference.modelId}" }
        val matches = document.models.filter { it.id == reference.modelId && it.revision == reference.revision }
        require(matches.size == 1) { "unresolved_or_ambiguous_revision: ${reference.modelId}/${reference.revision}" }
        val model = matches.single()
        require(model.contentFingerprint == reference.contentFingerprint) { "fingerprint_mismatch: ${model.id}" }
        require(reference.versionId == null || reference.versionId == model.versionId) { "version_mismatch: ${model.id}" }
        require(reference.batchId == null || reference.batchId == model.batchId) { "batch_mismatch: ${model.id}" }
        require(reference.datasetIds == null || reference.datasetIds == model.datasetIds) { "dataset_mismatch: ${model.id}" }
        require(model.sourceQuality != SourceQuality.review_only) { "review_only_model: ${model.id}" }
        return model
    }

    /** For a future explicit *new* selection action, never for restoring a saved reference. */
    fun defaultForNewSelection(document: ComponentCatalogue, productId: String): ModelReference {
        val defaults = document.models.filter { it.productId == productId && it.curatedDefault }
        require(defaults.size == 1) { "unresolved_or_ambiguous_default: $productId" }
        return defaults.single().reference().also { resolve(document, it) }
    }
}

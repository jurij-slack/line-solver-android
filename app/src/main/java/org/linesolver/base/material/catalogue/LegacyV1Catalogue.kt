package org.linesolver.base.material.catalogue

import kotlinx.serialization.json.*
import org.linesolver.base.material.MaterialRepository
import org.linesolver.base.material.Webbing
import org.linesolver.base.material.suppliedDomain
import org.linesolver.base.model.materialFingerprint

/** Additive view over already loaded Kotlin laws. No second file of numerical curves. */
fun MaterialRepository.versionedCatalogue() = LegacyV1Catalogue(this)

class LegacyV1Catalogue(private val repository: MaterialRepository) {
    private fun Webbing.reference() = LegacyRecordReference(id, materialFingerprint(metadata.toString()))
    private fun Webbing.productId() = metadata.getValue("webbing_id").jsonPrimitive.content
    private fun Webbing.source() = Provenance(
        citation = metadata.getValue("source_method").jsonPrimitive.content,
        url = metadata["source_url"]?.jsonPrimitive?.contentOrNull,
        note = metadata["source_note"]?.jsonPrimitive?.contentOrNull
    )
    private fun Webbing.specifications(): Specifications {
        val terminations = metadata["strength_tests"]?.jsonArray.orEmpty().map { value ->
            val item = value.jsonObject
            StrengthSpecification(
                termination = item.getValue("termination").jsonPrimitive.content,
                strengthN = item["MBS_N"]?.jsonPrimitive?.doubleOrNull,
                compatible = item["compatible"]?.jsonPrimitive?.booleanOrNull,
                source = item["source"]?.jsonPrimitive?.contentOrNull?.let { Provenance(it, url = it) },
                note = item["statistical_method"]?.jsonPrimitive?.contentOrNull
            )
        }
        return Specifications(densityKgPerM = density, mbsN = mbs,
            strengthBasis = metadata["strength_basis"]?.jsonPrimitive?.contentOrNull,
            terminationStrengths = terminations,
            information = JsonObject(metadata.filterKeys { it in setOf("material", "composition", "width_mm", "thickness_mm") }))
    }

    val document: ComponentCatalogue by lazy {
        val all = repository.all().sortedBy { it.id }
        require(all.none { it.metadata["model_origin"]?.jsonPrimitive?.contentOrNull == "imported" }) {
            "Legacy adapter accepts only the legacy authority; use CatalogueMaterialRepository.document for the combined catalogue"
        }
        val products = all.groupBy { it.productId() }.map { (id, variants) ->
            val first = variants.first()
            Product(id, ProductCategory.webbing, first.manufacturer, first.product,
                provenance = variants.map { it.source() }.distinct(),
                // Preserve exact documented configuration restrictions; full source metadata stays addressable.
                restrictions = variants.flatMap { m -> m.specifications().terminationStrengths
                    .filter { it.compatible == false }.map { "Incompatible termination: ${it.termination}" } }.distinct(),
                legacyRecords = variants.map { it.reference() })
        }
        val datasets = all.map { m ->
            MeasurementDataset("legacy-source:${m.id}", m.productId(), m.source(),
                procedure = m.metadata.getValue("source_method").jsonPrimitive.content,
                uncertaintyNotes = listOf("Legacy supplied/model knots; raw measurement-series identity and original units are not fully established."),
                legacyRecord = m.reference())
        }
        val models = all.map { m ->
            val ref = m.reference()
            MaterialModel(m.id, "legacy-sha256-${ref.fingerprint}", ref.fingerprint, m.productId(),
                if (m.provisionalLinear) LawType.provisional_linear else LawType.pchip,
                listOf("legacy-source:${m.id}"),
                ModelDomain(m.maxStrain, m.suppliedDomain()?.let { StrainRange(it.lower, it.upper) },
                    continuation = if (m.continuation == null) ContinuationRule.none else ContinuationRule.legacy_bounded,
                    continuationNote = m.extrapolationReason),
                sourceQuality = if (m.provisionalLinear) SourceQuality.provisional else SourceQuality.supplied,
                curatedDefault = m.isDefault, specifications = m.specifications(), legacyRecord = ref)
        }
        ComponentCatalogue(1, products = products, datasets = datasets, models = models,
            definitions = products.map { ComponentDefinition("legacy-component:${it.id}", it.id, it.name) })
            .also(CatalogueValidation::requireValid)
    }

    /** Metadata is returned unchanged, including batch research, source restrictions and termination tests.
     * A researched batch is deliberately NOT assigned to an unidentified historical curve. */
    fun metadata(ref: LegacyRecordReference): JsonObject {
        require(ref.resource == "webbing_material_models_v1.json") { "unsupported_legacy_resource" }
        val material = repository.get(ref.modelId)
        require(materialFingerprint(material.metadata.toString()) == ref.fingerprint) { "unavailable_legacy_revision: ${ref.modelId}" }
        return material.metadata
    }

    /** Inspection only; returned knots are read from the single authority, never persisted by the adapter. */
    fun points(datasetId: String): List<MeasurementPoint> {
        val dataset = document.datasets.single { it.id == datasetId }
        val record = requireNotNull(dataset.legacyRecord)
        val source = metadata(record)
        val material = repository.get(record.modelId)
        val strains = source.getValue("strain_fraction").jsonArray
        val forces = source.getValue("tension_N").jsonArray
        return strains.indices.map { index ->
            MeasurementPoint(strains[index].jsonPrimitive.double, forces[index].jsonPrimitive.double,
                if (index == 0 && material.hasImposedOrigin) PointOrigin.synthetic_origin else PointOrigin.supplied)
        }
    }

    /** Binds a portable document's external references to this installation and verifies the derived view.
     * Codec decoding alone validates structure; it cannot establish external numerical authority. */
    fun load(text: String): ComponentCatalogue = CatalogueCodec.decode(text).also { loaded ->
        loaded.products.forEach { product -> product.legacyRecords.forEach { ref ->
            require(metadata(ref).getValue("webbing_id").jsonPrimitive.content == product.id) { "legacy_product_mismatch: ${product.id}" }
        } }
        loaded.datasets.filter { it.legacyRecord != null }.forEach { dataset ->
            metadata(requireNotNull(dataset.legacyRecord))
            require(document.datasets.singleOrNull { it.id == dataset.id } == dataset) { "legacy_dataset_view_mismatch: ${dataset.id}" }
        }
        loaded.models.filter { it.legacyRecord != null }.forEach { model ->
            metadata(requireNotNull(model.legacyRecord))
            val expected = document.models.singleOrNull { it.id == model.id && it.revision == model.revision }
            require(expected != null && model.copy(curatedDefault = expected.curatedDefault) == expected) { "legacy_model_view_mismatch: ${model.id}" }
        }
    }

    fun resolve(reference: ModelReference): Webbing {
        val model = CatalogueSelection.resolve(document, reference)
        metadata(requireNotNull(model.legacyRecord))
        // Exact object identity: force/tangent/inverse/energy and all solver behavior are owned by Webbing.
        return repository.get(model.id)
    }
}

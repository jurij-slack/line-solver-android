package org.linesolver.base.material.catalogue

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Portable metadata only. None of these records constructs a numerical law or a rig topology. */
@Serializable data class ComponentCatalogue(
    val schemaVersion: Int,
    val units: CatalogueUnits = CatalogueUnits(),
    val products: List<Product> = emptyList(),
    val versions: List<ProductionVersion> = emptyList(),
    val batches: List<ProductionBatch> = emptyList(),
    val datasets: List<MeasurementDataset> = emptyList(),
    val models: List<MaterialModel> = emptyList(),
    val definitions: List<ComponentDefinition> = emptyList(),
    val instances: List<ComponentInstance> = emptyList()
)

@Serializable data class CatalogueUnits(
    val force: String = "N", val length: String = "m", val linearDensity: String = "kg/m",
    val mass: String = "kg", val energy: String = "J", val strain: String = "1"
)
@Serializable enum class ProductCategory { webbing, bungee, connector }
@Serializable data class Provenance(
    val citation: String, val url: String? = null, val date: String? = null, val note: String? = null
)
@Serializable data class StrengthSpecification(
    val termination: String, val strengthN: Double? = null, val compatible: Boolean? = null,
    val source: Provenance? = null, val note: String? = null
)
@Serializable data class Specifications(
    val densityKgPerM: Double? = null, val mbsN: Double? = null, val massKg: Double? = null,
    val source: Provenance? = null, val strengthBasis: String? = null,
    val terminationStrengths: List<StrengthSpecification> = emptyList(),
    val information: JsonObject = JsonObject(emptyMap())
)
@Serializable data class Product(
    val id: String, val category: ProductCategory, val manufacturer: String?, val name: String,
    val aliases: List<String> = emptyList(), val provenance: List<Provenance> = emptyList(),
    val restrictions: List<String> = emptyList(), val specifications: Specifications = Specifications(),
    val legacyRecords: List<LegacyRecordReference> = emptyList()
)
@Serializable data class ProductionVersion(
    val id: String, val productId: String, val label: String, val provenance: Provenance
)
@Serializable data class ProductionBatch(
    val id: String, val productId: String, val batchCode: String, val provenance: Provenance,
    val versionId: String? = null, val syntheticExample: Boolean = false
)

/** Values in points are SI; these factors preserve the documented source conversion, if known. */
@Serializable data class SourceUnits(
    val force: String, val forceToN: Double, val strain: String, val strainToFraction: Double
)
@Serializable enum class PointOrigin { measured, supplied, derived, synthetic_origin }
@Serializable data class MeasurementPoint(val strain: Double, val forceN: Double, val origin: PointOrigin)
@Serializable enum class RawDataStatus { raw, derived, unknown }
@Serializable data class MeasurementDataset(
    val id: String, val productId: String, val source: Provenance,
    val versionId: String? = null, val batchId: String? = null,
    val originalUnits: SourceUnits? = null, val procedure: String? = null, val conditions: String? = null,
    val rawDataStatus: RawDataStatus = RawDataStatus.unknown,
    val uncertaintyNotes: List<String> = emptyList(), val conflictFlags: List<String> = emptyList(),
    val points: List<MeasurementPoint>? = null,
    val legacyRecord: LegacyRecordReference? = null,
    // Unparseable source cells may be retained here, never interpreted as numerical input.
    val rawPayload: JsonObject? = null
)

/** Pins a record in the one existing numerical database; never embeds another curve. */
@Serializable data class LegacyRecordReference(
    val modelId: String, val fingerprint: String,
    val resource: String = "webbing_material_models_v1.json"
)
@Serializable enum class LawType { pchip, provisional_linear }
@Serializable enum class SourceQuality { supplied, provisional, review_only }
@Serializable enum class ContinuationRule { none, legacy_bounded }
@Serializable enum class RangeEvidence { measured, supplied }
@Serializable data class StrainRange(val lower: Double, val upper: Double)
@Serializable data class ModelDomain(
    val supportedMaxStrain: Double,
    val suppliedRange: StrainRange? = null,
    val rangeEvidence: RangeEvidence = RangeEvidence.supplied,
    val compression: String = "tension_only_zero",
    val aboveMaximum: String = "forbidden",
    val continuation: ContinuationRule = ContinuationRule.none,
    val continuationNote: String? = null
) {
    fun region(strain: Double): DomainRegion = when {
        !strain.isFinite() || strain > supportedMaxStrain -> DomainRegion.forbidden
        strain < 0 -> DomainRegion.compression
        suppliedRange?.let { strain >= it.lower && strain <= it.upper } == true ->
            if (rangeEvidence == RangeEvidence.measured) DomainRegion.measured else DomainRegion.supplied
        else -> DomainRegion.extrapolated
    }
}
enum class DomainRegion { compression, measured, supplied, extrapolated, forbidden }
@Serializable data class MaterialModel(
    val id: String, val revision: String, val contentFingerprint: String,
    val productId: String, val type: LawType, val datasetIds: List<String>, val domain: ModelDomain,
    val versionId: String? = null, val batchId: String? = null,
    val sourceQuality: SourceQuality = SourceQuality.supplied,
    val curatedDefault: Boolean = false, val fitDescription: String? = null,
    val fitPointIndices: List<Int>? = null,
    val specifications: Specifications = Specifications(), val legacyRecord: LegacyRecordReference? = null
) {
    fun reference() = ModelReference(id, revision, contentFingerprint, versionId, batchId, datasetIds)
}
@Serializable data class ModelReference(
    val modelId: String, val revision: String? = null, val contentFingerprint: String? = null,
    val versionId: String? = null, val batchId: String? = null, val datasetIds: List<String>? = null
)
@Serializable data class ComponentDefinition(val id: String, val productId: String, val name: String)
/** Configuration hooks only: layer count is NOT a force multiplier. */
@Serializable data class BungeeConfiguration(
    val restLengthM: Double? = null, val foldedLegs: Int? = null,
    val rubberLayerCount: Int? = null, val limiterLengthM: Double? = null
)
@Serializable data class ConnectorMetadata(
    val configuration: String? = null, val loadingDirection: String? = null, val note: String? = null
)
@Serializable data class ComponentInstance(
    val id: String, val definitionId: String, val model: ModelReference? = null,
    val versionId: String? = null, val batchId: String? = null,
    val bungee: BungeeConfiguration? = null, val connector: ConnectorMetadata? = null
)

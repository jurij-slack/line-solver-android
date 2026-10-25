package org.linesolver.base

import kotlinx.serialization.json.*
import org.linesolver.base.material.catalogue.*

/** Entirely synthetic examples. Never included in app assets or offered by MaterialRepository. */
internal object CatalogueFixtures {
    val source = Provenance("Synthetic protocol example; not a real product or test", date = "2026-10-12")
    fun example(): ComponentCatalogue {
        val product = Product("example:webbing", ProductCategory.webbing, "Example only", "Example tape",
            aliases = listOf("Example alias"), provenance = listOf(source),
            restrictions = listOf("Synthetic documentation fixture; not for physical use"),
            specifications = Specifications(densityKgPerM = .05, source = source,
                terminationStrengths = listOf(StrengthSpecification("example termination", compatible = false, source = source))))
        val version = ProductionVersion("example:mk2", product.id, "MK2 (documented synthetic example)", source)
        val batch = ProductionBatch("example:batch", product.id, "SYNTHETIC-001", source, version.id, syntheticExample = true)
        val units = SourceUnits("kN", 1000.0, "%", .01)
        fun point(e: Double, f: Double) = MeasurementPoint(e, f, PointOrigin.measured)
        val a = MeasurementDataset("example:series-a", product.id, source, version.id, batch.id, units,
            procedure = "Synthetic monotonic loading example", conditions = null, rawDataStatus = RawDataStatus.raw,
            uncertaintyNotes = listOf("Example only; uncertainty magnitude unknown"), points = listOf(
                MeasurementPoint(0.0, 0.0, PointOrigin.synthetic_origin), point(.01, 1000.0), point(.025, 2000.0), point(.045, 3000.0)))
        val b = a.copy(id = "example:series-b", batchId = null,
            source = source.copy(citation = "Independent synthetic series B; no batch evidence"),
            points = listOf(point(.012, 1000.0), point(.029, 2000.0)))
        val sparse = b.copy(id = "example:sparse", points = listOf(point(.02, 1000.0)))
        val missing = MeasurementDataset("example:missing", "example:catalogue-only", source)
        val review = b.copy(id = "example:review", points = listOf(point(.02, 2000.0), point(.01, 1000.0)),
            conflictFlags = listOf("Contradictory source retained without sorting"))
        val datasets = listOf(a, b, sparse, missing, review)
        fun model(id: String, dataset: MeasurementDataset, max: Double, min: Double) = MaterialModel(id, "r1", "",
            product.id, LawType.pchip, listOf(dataset.id), ModelDomain(max, StrainRange(min, max), RangeEvidence.measured),
            dataset.versionId, dataset.batchId, fitDescription = "PCHIP through selected source points")
        val models = listOf(
            model("example:full-fit", a, .045, .01).copy(curatedDefault = true),
            model("example:reduced-fit", a, .045, .01).copy(fitPointIndices = listOf(0, 1, 3)),
            model("example:independent-fit", b, .029, .012),
            model("example:provisional", sparse, .02, .02).copy(type = LawType.provisional_linear,
                sourceQuality = SourceQuality.provisional, fitDescription = "Provisional origin-to-point candidate; no measured interval")
        ).map { CatalogueIdentity.seal(it, datasets) }
        val otherProducts = listOf(
            Product("example:catalogue-only", ProductCategory.webbing, null, "No measurements", provenance = listOf(source)),
            Product("example:bungee", ProductCategory.bungee, null, "Future bungee placeholder", provenance = listOf(source)),
            Product("example:connector", ProductCategory.connector, null, "Future connector placeholder", provenance = listOf(source)))
        val products = listOf(product) + otherProducts
        val definitions = products.map { ComponentDefinition("${it.id}/definition", it.id, it.name) }
        return ComponentCatalogue(1, products = products, versions = listOf(version), batches = listOf(batch),
            datasets = datasets, models = models, definitions = definitions,
            instances = listOf(
                ComponentInstance("example:installed-tape", "${product.id}/definition", models.first().reference(), version.id, batch.id),
                ComponentInstance("example:installed-bungee", "example:bungee/definition",
                    bungee = BungeeConfiguration(restLengthM = 1.8, foldedLegs = 2, rubberLayerCount = 4, limiterLengthM = 4.0)),
                ComponentInstance("example:installed-connector", "example:connector/definition",
                    connector = ConnectorMetadata("Placeholder only", "Unknown", "No mass/strength or load-sharing assumption"))))
    }
}

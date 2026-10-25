package org.linesolver.base

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.material.JsonMaterialRepository
import org.linesolver.base.material.catalogue.*
import org.linesolver.base.model.materialFingerprint

class ComponentCatalogueTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val repo by lazy { JsonMaterialRepository(resource("webbing_material_models_v1.json")) }
    private fun example() = CatalogueFixtures.example()
    private fun codes(doc: ComponentCatalogue) = CatalogueValidation.validate(doc)
        .filter { it.severity == IssueSeverity.error }.map { it.code }
    private fun bad(doc: ComponentCatalogue, code: String) {
        assertTrue("Expected $code in ${codes(doc)}", code in codes(doc))
        assertThrows(IllegalArgumentException::class.java) { CatalogueCodec.encode(doc) }
    }
    private fun withModel(model: MaterialModel): ComponentCatalogue {
        val doc = example()
        return doc.copy(models = listOf(CatalogueIdentity.seal(model, doc.datasets)), instances = emptyList())
    }

    @Test fun portableFixtureRoundtripsAndHasAllRequestedRelationships() {
        val doc = example()
        val text = CatalogueCodec.encode(doc)
        assertEquals(resource("catalogue-v1/examples.json").trim(), text)
        assertEquals(text, CatalogueCodec.encode(CatalogueCodec.decode(text)))
        assertEquals(text, CatalogueCodec.encode(doc.copy(products = doc.products.reversed(), datasets = doc.datasets.reversed(), models = doc.models.reversed())))
        assertEquals(4, doc.datasets.count { it.productId == "example:webbing" })
        assertEquals(doc.models[0].datasetIds, doc.models[1].datasetIds)
        assertNotEquals(doc.models[0].fitPointIndices, doc.models[1].fitPointIndices)
        assertTrue(doc.batches.single().syntheticExample)
        assertNull(doc.datasets.first { it.id == "example:series-b" }.batchId)
        assertEquals("MK2 (documented synthetic example)", doc.versions.single().label)
        assertNull(doc.products.first { it.id == "example:catalogue-only" }.specifications.mbsN)
        assertEquals(2, doc.instances.count { it.model == null })
        assertFalse(text.contains("org.linesolver"))
    }

    @Test fun everyLegacyModelIsTheSameNumericalObjectWithTheExactFingerprint() {
        val adapter = repo.versionedCatalogue(); val doc = adapter.document
        assertEquals(63, doc.models.size); assertEquals(58, doc.products.size)
        assertEquals(repo.all().map { it.id }.toSet(), doc.models.map { it.id }.toSet())
        assertTrue(doc.versions.isEmpty()); assertTrue(doc.batches.isEmpty()); assertTrue(doc.instances.isEmpty())
        for (m in doc.models) {
            val old = repo.get(m.id)
            assertSame(old, adapter.resolve(m.reference()))
            assertSame(old.metadata, adapter.metadata(m.legacyRecord!!))
            assertEquals(materialFingerprint(old.metadata.toString()), m.contentFingerprint)
            assertEquals(old.density, m.specifications.densityKgPerM); assertEquals(old.mbs, m.specifications.mbsN)
            assertEquals(old.maxStrain, m.domain.supportedMaxStrain, 0.0)
            assertEquals(old.isDefault, m.curatedDefault)
            assertEquals(PointOrigin.synthetic_origin, adapter.points(m.datasetIds.single()).first().origin)
            assertNull(doc.datasets.single { it.id == m.datasetIds.single() }.points)
        }
        val encoded = CatalogueCodec.encode(doc)
        assertFalse(encoded.contains("strain_fraction")); assertFalse(encoded.contains("tension_N"))
        assertEquals(encoded, CatalogueCodec.encode(adapter.load(encoded)))
        val spider = doc.models.single { repo.get(it.id).product == "Spider Silk MK5" }
        assertNull(spider.specifications.mbsN); assertNull(spider.batchId)
        assertNotNull(adapter.metadata(spider.legacyRecord!!)["researched_batch_specifications"])
        val jumanji = doc.models.single { repo.get(it.id).product == "Jumanji" }
        assertEquals(2, jumanji.specifications.terminationStrengths.count { it.compatible == false })
    }

    @Test fun legacyBindingsCannotReplaceCurvesOrTrustAlteredDerivedMetadata() {
        val adapter = repo.versionedCatalogue(); val doc = adapter.document; val first = doc.models.first()
        val changed = doc.copy(models = listOf(first.copy(domain = first.domain.copy(supportedMaxStrain = .001))) + doc.models.drop(1))
        assertThrows(IllegalArgumentException::class.java) { adapter.load(CatalogueCodec.encode(changed)) }
        assertThrows(IllegalArgumentException::class.java) { adapter.resolve(first.reference().copy(contentFingerprint = "0".repeat(64))) }
        assertThrows(IllegalArgumentException::class.java) { adapter.resolve(first.reference().copy(revision = "absent")) }
        val old = repo.all().first { repo.previousRevisions(it.id).isNotEmpty() }
        val previousHash = materialFingerprint(repo.previousRevisions(old.id).first().toString())
        assertThrows(IllegalArgumentException::class.java) {
            adapter.resolve(ModelReference(old.id, "legacy-sha256-$previousHash", previousHash))
        }
    }

    @Test fun sourceDataClassificationNeverRepairsOrInventsData() {
        val base = example().datasets.first()
        assertEquals(DatasetDisposition.monotonic_candidate, CatalogueValidation.assess(base).disposition)
        assertEquals(DatasetDisposition.provisional_candidate, CatalogueValidation.assess(base.copy(points = listOf(base.points!!.last()))).disposition)
        for (points in listOf(null, emptyList(), listOf(base.points!!.first())))
            assertEquals(DatasetDisposition.catalogue_only, CatalogueValidation.assess(base.copy(points = points)).disposition)
        val invalid = listOf(
            base.points!!.reversed(), base.points + base.points.last(),
            listOf(MeasurementPoint(.01, -1.0, PointOrigin.measured)),
            listOf(MeasurementPoint(.01, 0.0, PointOrigin.measured)),
            listOf(MeasurementPoint(.01, 1000.0, PointOrigin.synthetic_origin)))
        for (points in invalid) {
            val dataset = base.copy(points = points)
            assertEquals(DatasetDisposition.review_only, CatalogueValidation.assess(dataset).disposition)
            val reviewDoc = example().copy(datasets = listOf(dataset), models = emptyList(), instances = emptyList())
            assertEquals(dataset.points, CatalogueCodec.decode(CatalogueCodec.encode(reviewDoc)).datasets.single().points)
        }
        val malformed = base.copy(points = null, rawPayload = buildJsonObject { put("strain_cell", "not a number") })
        assertEquals(DatasetDisposition.review_only, CatalogueValidation.assess(malformed).disposition)
        assertEquals(DatasetDisposition.review_only, CatalogueValidation.assess(base.copy(conflictFlags = listOf("Different gauge references"))).disposition)
        val noOrigin = base.copy(points = base.points.drop(1))
        assertEquals(3, noOrigin.points!!.size)
        assertTrue(noOrigin.points.none { it.origin == PointOrigin.synthetic_origin })
    }

    @Test fun badUnitsPhysicalQuantitiesAndSchemaVersionsFailExplicitly() {
        val doc = example()
        bad(doc.copy(schemaVersion = 2), "unsupported_schema_version")
        bad(doc.copy(units = doc.units.copy(force = "kN")), "unsupported_units")
        for (units in listOf(SourceUnits("lbf", 1.0, "1", 1.0), SourceUnits("kN", 1.0, "%", .01), SourceUnits("N", 1.0, "mm", 1.0)))
            bad(doc.copy(datasets = doc.datasets.map { it.copy(originalUnits = units) }), "invalid_source_units_or_conversion")
        for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            bad(doc.copy(products = doc.products.map { it.copy(specifications = Specifications(densityKgPerM = value)) }), "invalid_quantity")
            bad(doc.copy(products = doc.products.map { it.copy(specifications = Specifications(mbsN = value)) }), "invalid_quantity")
        }
        bad(doc.copy(datasets = listOf(doc.datasets.first().copy(points = listOf(MeasurementPoint(Double.NaN, 1.0, PointOrigin.measured))))), "nonfinite_point")
        val encoded = CatalogueCodec.encode(doc)
        for (text in listOf(encoded.replace("\"schema_version\":1", "\"schema_version\":99"), encoded.replace("\"schema_version\":1,", ""), "{\"unknown_field\":true," + encoded.drop(1)))
            assertThrows(IllegalArgumentException::class.java) { CatalogueCodec.decode(text) }
        assertThrows(IllegalArgumentException::class.java) { CatalogueCodec.decode(encoded.replace("\"force_n\":1000", "\"force_n\":NaN")) }
    }

    @Test fun duplicateUnknownAndCrossProductReferencesFailDeterministically() {
        val doc = example(); val model = doc.models.first(); val dataset = doc.datasets.first()
        bad(doc.copy(products = doc.products + doc.products.first()), "duplicate_id")
        bad(doc.copy(datasets = doc.datasets + dataset), "duplicate_id")
        bad(doc.copy(models = doc.models + model), "duplicate_model_revision")
        bad(doc.copy(models = listOf(model.copy(datasetIds = listOf("absent")))), "unknown_dataset:absent")
        bad(doc.copy(models = listOf(model.copy(datasetIds = listOf(dataset.id, dataset.id)))), "duplicate_dataset_reference")
        bad(doc.copy(datasets = listOf(dataset.copy(productId = "absent"))), "unknown_product")
        bad(doc.copy(versions = emptyList()), "unknown_or_foreign_version")
        bad(doc.copy(batches = emptyList()), "unknown_or_foreign_batch")
        bad(doc.copy(batches = doc.batches.map { it.copy(versionId = "other") }), "batch_version_mismatch")
        bad(doc.copy(datasets = doc.datasets.map { if (it.id == dataset.id) it.copy(productId = "example:catalogue-only") else it }), "foreign_dataset")
        bad(doc.copy(definitions = emptyList()), "unknown_definition")
        val issues = CatalogueValidation.validate(doc.copy(products = emptyList(), versions = emptyList()))
        assertEquals(issues, CatalogueValidation.validate(doc.copy(products = emptyList(), versions = emptyList())))
        assertEquals(issues.sortedWith(compareBy({ it.path }, { it.code }, { it.severity.name })), issues)
    }

    @Test fun singlePointAndReviewOnlySourcesCannotMasqueradeAsPchip() {
        val doc = example(); val base = doc.models.first()
        val sparse = doc.datasets.first { it.id == "example:sparse" }
        val sparsePchip = base.copy(datasetIds = listOf(sparse.id), batchId = null, domain = ModelDomain(.02, StrainRange(.02, .02)))
        bad(withModel(sparsePchip), "dataset_not_eligible_for_law")
        val provisional = sparsePchip.copy(type = LawType.provisional_linear, sourceQuality = SourceQuality.provisional)
        CatalogueValidation.requireValid(withModel(provisional))
        bad(withModel(provisional.copy(sourceQuality = SourceQuality.supplied)), "missing_provisional_quality")
        bad(withModel(base.copy(datasetIds = listOf("example:review"), batchId = null)), "unusable_parent_dataset")
        bad(withModel(base.copy(fitPointIndices = listOf(3, 1))), "invalid_fit_point_indices")
        bad(withModel(base.copy(fitPointIndices = listOf(0, 99))), "invalid_fit_point_indices")
        bad(withModel(base.copy(domain = base.domain.copy(supportedMaxStrain = .1))), "unsupported_extension")
        bad(withModel(base.copy(domain = base.domain.copy(suppliedRange = StrainRange(0.0, .045)))), "unsupported_supplied_range")
        bad(withModel(base.copy(domain = base.domain.copy(continuation = ContinuationRule.legacy_bounded))), "unsupported_new_continuation")
    }

    @Test fun contentIdentityAndDefaultsCannotSilentlyChangePinnedSelections() {
        val doc = example(); val original = doc.models.first(); val ref = original.reference()
        assertEquals(original, CatalogueSelection.resolve(doc, ref))
        for (reference in listOf(ref.copy(revision = null), ref.copy(contentFingerprint = null), ref.copy(revision = "r99"),
            ref.copy(contentFingerprint = "0".repeat(64)), ref.copy(modelId = "absent"), ref.copy(batchId = "other"),
            ref.copy(versionId = "other"), ref.copy(datasetIds = listOf("other"))))
            assertThrows(IllegalArgumentException::class.java) { CatalogueSelection.resolve(doc, reference) }
        val newRevision = CatalogueIdentity.seal(original.copy(revision = "r2", curatedDefault = false), doc.datasets)
        val revisions = doc.copy(models = doc.models + newRevision)
        assertEquals(original, CatalogueSelection.resolve(revisions, ref))
        val changedDefault = doc.copy(models = doc.models.map { it.copy(curatedDefault = it.id == "example:reduced-fit") })
        assertEquals(original.id, CatalogueSelection.resolve(changedDefault, ref).id)
        assertEquals("example:reduced-fit", CatalogueSelection.defaultForNewSelection(changedDefault, original.productId).modelId)
        bad(doc.copy(models = doc.models.map { it.copy(curatedDefault = true) }), "ambiguous_default")
        assertThrows(IllegalArgumentException::class.java) { CatalogueSelection.defaultForNewSelection(doc, "example:catalogue-only") }
        bad(doc.copy(models = listOf(original.copy(fitDescription = "Changed without a new hash"))), "content_fingerprint_mismatch")
        val review = CatalogueIdentity.seal(original.copy(sourceQuality = SourceQuality.review_only, curatedDefault = false), doc.datasets)
        val reviewDoc = doc.copy(models = listOf(review), instances = emptyList())
        CatalogueValidation.requireValid(reviewDoc)
        assertThrows(IllegalArgumentException::class.java) { CatalogueSelection.resolve(reviewDoc, review.reference()) }
    }

    @Test fun instanceHooksRemainTypedMetadataWithNoSolverOrTopology() {
        val doc = example(); val bungee = doc.instances.first { it.bungee != null }
        bad(doc.copy(instances = listOf(bungee.copy(definitionId = "example:webbing/definition"))), "wrong_bungee_category")
        bad(doc.copy(instances = listOf(bungee.copy(bungee = BungeeConfiguration(-1.0, 0, -1, .5)))), "invalid_quantity")
        bad(doc.copy(instances = listOf(bungee.copy(bungee = BungeeConfiguration(2.0, 2, 4, 1.0)))), "limiter_shorter_than_rest")
        val tape = doc.instances.first()
        bad(doc.copy(instances = listOf(tape.copy(model = tape.model!!.copy(revision = null)))), "unresolved_selection:missing_revision: example:full-fit")
        assertEquals(63, repo.all().size)
        assertTrue(repo.all().none { it.id.startsWith("example:") })
    }

    @Test fun portableInvalidFixturesHaveExplicitErrorsOrReviewDisposition() {
        for ((file, code) in listOf("invalid-units.json" to "unsupported_units", "invalid-reference.json" to "unknown_product")) {
            val failure = assertThrows(IllegalArgumentException::class.java) { CatalogueCodec.decode(resource("catalogue-v1/$file")) }
            assertTrue(failure.message.orEmpty().contains(code))
        }
        val review = CatalogueCodec.decode(resource("catalogue-v1/review-knots.json"))
        assertTrue(CatalogueValidation.validate(review).all { it.severity == IssueSeverity.review })
        assertEquals(listOf(.02, .01), review.datasets.single().points!!.map { it.strain })
        assertEquals(DatasetDisposition.review_only, CatalogueValidation.assess(review.datasets.single()).disposition)
        assertTrue(review.models.isEmpty())
    }

    @Test fun numericSpellingsAreCanonicalAndDatasetChangesInvalidateIdentity() {
        val doc = example(); val text = CatalogueCodec.encode(doc)
        assertEquals(text, CatalogueCodec.encode(CatalogueCodec.decode(text.replace("0.045", "4.500e-2"))))
        val first = doc.datasets.first()
        val changed = first.copy(points = first.points!!.mapIndexed { i, p -> if (i == 2) p.copy(forceN = 2100.0) else p })
        bad(doc.copy(datasets = listOf(changed) + doc.datasets.drop(1)), "content_fingerprint_mismatch")
        bad(withModel(doc.models.first().copy(domain = ModelDomain(.045, StrainRange(.04, .01)))), "invalid_range")
        val supplied = first.copy(points = first.points.map { if (it.origin == PointOrigin.measured) it.copy(origin = PointOrigin.supplied) else it })
        val datasets = listOf(supplied) + doc.datasets.drop(1)
        val model = CatalogueIdentity.seal(doc.models.first(), datasets)
        bad(doc.copy(datasets = datasets, models = listOf(model), instances = emptyList()), "unmeasured_range")
        // An interval between supplied knots still depends on supplied endpoints, even if no knot lies inside it.
        val interior = CatalogueIdentity.seal(model.copy(domain = model.domain.copy(suppliedRange = StrainRange(.011, .012))), datasets)
        bad(doc.copy(datasets = datasets, models = listOf(interior), instances = emptyList()), "unmeasured_range")
    }

    @Test fun domainEvidenceSeparatesMeasuredSuppliedExtrapolatedAndForbidden() {
        val domain = example().models.first().domain
        assertEquals(DomainRegion.compression, domain.region(-.01))
        assertEquals(DomainRegion.extrapolated, domain.region(0.0))
        assertEquals(DomainRegion.measured, domain.region(.01))
        assertEquals(DomainRegion.measured, domain.region(.045))
        assertEquals(DomainRegion.forbidden, domain.region(.0450001))
        assertEquals(DomainRegion.forbidden, domain.region(Double.NaN))
        assertEquals(DomainRegion.supplied, domain.copy(rangeEvidence = RangeEvidence.supplied).region(.02))
        val provisional = example().models.single { it.type == LawType.provisional_linear }.domain
        assertEquals(DomainRegion.measured, provisional.region(.02))
        assertEquals(DomainRegion.extrapolated, provisional.region(.019))
        assertEquals(DomainRegion.forbidden, provisional.region(.021))
        val doc = example(); val first = doc.datasets.first()
        val measuredZero = first.copy(points = first.points!!.mapIndexed { i, p -> if (i == 0) p.copy(origin = PointOrigin.measured) else p })
        val datasets = listOf(measuredZero) + doc.datasets.drop(1)
        val model = CatalogueIdentity.seal(doc.models.first().copy(domain = domain.copy(suppliedRange = StrainRange(0.0, .045))), datasets)
        CatalogueValidation.requireValid(doc.copy(datasets = datasets, models = listOf(model), instances = emptyList()))
        assertEquals(DomainRegion.measured, model.domain.region(0.0))
    }

    @Test fun invalidSourceCannotBeHiddenBehindSyntheticPointsOrANewFit() {
        val doc = example(); val first = doc.datasets.first()
        val invalid = first.copy(points = listOf(MeasurementPoint(.01, 1000.0, PointOrigin.synthetic_origin),
            MeasurementPoint(.045, 3000.0, PointOrigin.synthetic_origin)))
        val datasets = listOf(invalid) + doc.datasets.drop(1)
        val model = CatalogueIdentity.seal(doc.models.first(), datasets)
        bad(doc.copy(datasets = datasets, models = listOf(model), instances = emptyList()), "unusable_parent_dataset")
        val adapter = repo.versionedCatalogue(); val legacy = adapter.document
        val changedProduct = legacy.products.first().copy(legacyRecords = legacy.products.last().legacyRecords)
        val altered = legacy.copy(products = listOf(changedProduct) + legacy.products.drop(1))
        assertThrows(IllegalArgumentException::class.java) { adapter.load(CatalogueCodec.encode(altered)) }
        val duplicateAuthority = legacy.datasets.first().copy(points = first.points)
        bad(legacy.copy(datasets = listOf(duplicateAuthority) + legacy.datasets.drop(1)), "duplicate_numerical_authority")
    }
}

package org.linesolver.base

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.diagnostics.DiagnosticRunner
import org.linesolver.base.material.*
import org.linesolver.base.material.catalogue.*
import org.linesolver.base.model.*
import java.io.File
import kotlin.math.abs
import kotlin.math.max

class WebbingCatalogueImportTest {
    companion object {
        private val root = File(requireNotNull(System.getProperty("catalogueProjectRoot")))
        private fun read(path: String) = File(root, path).readText()
        private val source = read("data/materials/webbings.json")
        private val rules = read("catalogue/reconciliation-v1.json")
        private val legacy = JsonMaterialRepository(read("data/materials/webbing_material_models_v1.json"))
        private val importer = WebbingCatalogueImport(legacy, rules)
        private val result by lazy { importer.import(source) }
        private val doc get() = result.catalogue
        private val repo by lazy { CatalogueMaterialRepository(legacy, CatalogueCodec.encode(doc)) }
        private val sourceRows = Json.parseToJsonElement(source).jsonArray
        private val imported get() = doc.models.filter { it.legacyRecord == null }
        private fun dataset(id: Int) = doc.datasets.single { it.id == "webbings:dataset:$id" }
        private fun inventory(id: Int) = result.inventory.getValue("records").jsonArray.map { it.jsonObject }
            .single { it.getValue("source_id").jsonPrimitive.int == id }
        private fun close(expected: Double, actual: Double, relative: Double = 1e-8) =
            assertEquals(expected, actual, relative * max(1.0, abs(expected)))
        private fun fails(block: () -> Unit) { assertTrue("Expected clear rejection", runCatching(block).isFailure) }
        private fun synthetic(stretch: String, extra: String = "", id: Int = 9000) =
            """{"id":$id,"name":"Synthetic test only","brand":"Test","weight":50,"breakingStrength":30,"stretch":$stretch$extra}"""
        private fun importRows(vararg rows: String) = importer.import(rows.joinToString(",", "[", "]"))
        private fun onlyNew(r: WebbingCatalogueImport.Result) = r.catalogue.datasets.single { it.legacyRecord == null }
    }

    @Test fun completeInventoryAndRawSourcePreservation() {
        assertEquals(261, sourceRows.size)
        val actual = doc.datasets.filter { it.legacyRecord == null }.associateBy { it.rawPayload!!.getValue("id").jsonPrimitive.int }
        assertEquals(sourceRows.size, actual.size)
        for (row in sourceRows) assertEquals(row, actual.getValue(row.jsonObject.getValue("id").jsonPrimitive.int).rawPayload)
        val counts = result.inventory.getValue("counts").jsonObject
        assertEquals(261, listOf("monotonic_candidate", "provisional_candidate", "catalogue_only", "review_only")
            .sumOf { counts.getValue(it).jsonPrimitive.int })
        assertEquals(97, counts.getValue("monotonic_candidate").jsonPrimitive.int)
        assertEquals(113, counts.getValue("provisional_candidate").jsonPrimitive.int)
        assertEquals(33, counts.getValue("catalogue_only").jsonPrimitive.int)
        assertEquals(18, counts.getValue("review_only").jsonPrimitive.int)
        assertTrue(CatalogueValidation.validate(doc).none { it.severity == IssueSeverity.error })
    }

    @Test fun deterministicRepeatImportAndCheckedInArtifacts() {
        val again = importer.import(source)
        assertEquals(CatalogueCodec.encode(doc), CatalogueCodec.encode(again.catalogue))
        assertEquals(result.inventoryText(), again.inventoryText())
        assertEquals(read("catalogue/webbing-catalogue-v1.json"), CatalogueCodec.encode(doc) + "\n")
        assertEquals(read("catalogue/inventory-v1.json"), result.inventoryText() + "\n")
        assertEquals(read("catalogue/RECONCILIATION.md"), result.report())
        assertEquals(CatalogueCodec.encode(doc), CatalogueCodec.encode(importer.import(JsonArray(sourceRows.reversed()).toString()).catalogue))
        // Canonical v1 sorts record arrays and normalizes raw JSON numeric spellings (40.0 -> 40).
        assertEquals(CatalogueCodec.encode(doc), CatalogueCodec.encode(CatalogueCodec.decode(CatalogueCodec.encode(doc))))
    }

    @Test fun uniqueProductsDatasetsAndImmutableModelIds() {
        assertEquals(doc.products.size, doc.products.map { it.id }.distinct().size)
        assertEquals(doc.datasets.size, doc.datasets.map { it.id }.distinct().size)
        assertEquals(doc.models.size, doc.models.map { it.id }.distinct().size)
        assertEquals(265, doc.products.size)
        assertEquals(251, doc.models.size)
        for (m in imported) {
            assertEquals(m.contentFingerprint, CatalogueIdentity.fingerprint(m, doc.datasets))
            assertEquals(m.id, repo.resolve(m.reference()).id)
        }
    }

    @Test fun aliasesRespectManufacturerVersionAndAmbiguity() {
        assertEquals("raed__motm", importer.matches("Raed Slacklines", "MOTM").single().id)
        assertEquals("balance_community__type18_mkii", importer.matches("BalanceCommunity", "Type 18 MKII").single().id)
        assertTrue(importer.matches("BalanceCommunity", "Spider Silk MK3").isEmpty())
        assertTrue(importer.matches("Another manufacturer", "MOTM").isEmpty())
        val p = doc.products.first()
        assertEquals(2, importer.matches(p.manufacturer!!, p.name, listOf(p, p.copy(id = "ambiguous:2"))).size)
        for (id in listOf(119, 215, 230)) {
            assertEquals("ambiguous_unmerged", inventory(id).getValue("match").jsonPrimitive.content)
            assertEquals(DatasetDisposition.review_only, CatalogueValidation.assess(dataset(id)).disposition)
            assertTrue(imported.none { dataset(id).id in it.datasetIds })
        }
        assertNotEquals(dataset(213).productId, dataset(261).productId)
        assertNotEquals(dataset(190).productId, dataset(217).productId)
    }

    @Test fun duplicateIdsRejectAndDocumentedCopiesReuseModels() {
        val s = synthetic("[{\"kn\":5,\"percent\":10}]")
        fails { importRows(s, s) }
        val duplicateNames = importRows(s, synthetic("[{\"kn\":5,\"percent\":10}]", id = 9001))
        assertTrue(duplicateNames.catalogue.datasets.filter { it.legacyRecord == null }.all {
            CatalogueValidation.assess(it).disposition == DatasetDisposition.review_only
        })
        assertEquals(dataset(1).productId, dataset(263).productId)
        assertEquals(RawDataStatus.derived, dataset(263).rawDataStatus)
        assertEquals(inventory(1).getValue("model_ids"), inventory(263).getValue("model_ids"))
        assertTrue(imported.none { it.id == "webbings:model:263" })
        assertTrue(inventory(213).getValue("reused_curve").jsonPrimitive.boolean)
        assertTrue(imported.none { it.id == "webbings:model:213" })
    }

    @Test fun malformedIncompleteNonmonotonicAndContradictoryDataNeverRepaired() {
        for (s in listOf(
            "[{\"kn\":5,\"percent\":\"NaN\"}]", "[{\"kn\":\"5\",\"percent\":10}]",
            "[{\"kn\":5,\"percent\":-1}]", "[{\"kn\":5,\"percent\":0}]",
            "[{\"kn\":5,\"percent\":10},{\"kn\":10,\"percent\":10}]",
            "[{\"kn\":10,\"percent\":20},{\"kn\":5,\"percent\":10}]",
            "[{\"kn\":5,\"percent\":10},{\"percent\":20}]", "{}", "[null]"
        )) {
            val r = importRows(synthetic(s)); val d = onlyNew(r)
            assertEquals(s, DatasetDisposition.review_only, CatalogueValidation.assess(d).disposition)
            assertEquals(Json.parseToJsonElement(s), d.rawPayload!!.getValue("stretch"))
            assertTrue(r.catalogue.models.none { it.legacyRecord == null })
        }
        for (s in listOf("null", "[]", "[{\"percent\":8}]"))
            assertEquals(DatasetDisposition.catalogue_only, CatalogueValidation.assess(onlyNew(importRows(synthetic(s)))).disposition)
        for (id in listOf(67, 69, 190, 205, 226, 228, 247))
            assertTrue(CatalogueValidation.assess(dataset(id)).reasons.contains("nonmonotonic_points"))
    }

    @Test fun unitConversionsAndSourceMetadataRemainExplicit() {
        val d = dataset(1); val m = doc.models.single { it.id == "webbings:model:1" }
        assertEquals(SourceUnits("kN", 1000.0, "%", .01), d.originalUnits)
        assertEquals(4000.0, d.points!!.first().forceN, 0.0)
        // Decimal 1.1/100 differs from the literal .011 by one binary64 ULP.
        assertEquals(.011, d.points.first().strain, 2e-18)
        assertEquals(.0715, m.specifications.densityKgPerM!!, 1e-16)
        assertEquals(44000.0, m.specifications.mbsN!!, 0.0)
        val axiom = doc.products.single { it.id == dataset(261).productId }.specifications
        assertEquals(18.4, axiom.information.getValue("width_mm").jsonPrimitive.double, 0.0)
        assertEquals("2026-09-17", axiom.information.getValue("date_introduced_utc").jsonPrimitive.content)
        assertNull(dataset(1).source.date) // Introduction date is not a measurement date.
        val invalid = doc.copy(datasets = doc.datasets.map { if (it.id == d.id) it.copy(originalUnits = SourceUnits("lbf", 1.0, "%", .01)) else it })
        fails { CatalogueCodec.encode(invalid) }
    }

    @Test fun malformedMassStrengthAndMissingInputsAreNotInvented() {
        val row = Json.parseToJsonElement(synthetic("[{\"kn\":5,\"percent\":10}]")).jsonObject
        for (field in listOf("weight", "breakingStrength")) {
            for (bad in listOf(JsonPrimitive("unknown"), JsonPrimitive(-1), JsonPrimitive(0))) {
                val imported = importer.import(JsonArray(listOf(JsonObject(row + (field to bad)))).toString())
                assertEquals(DatasetDisposition.review_only, CatalogueValidation.assess(onlyNew(imported)).disposition)
                assertTrue(imported.catalogue.models.none { it.legacyRecord == null })
            }
        }
        val missing = importer.import(JsonArray(listOf(JsonObject(row - "weight" - "breakingStrength"))).toString())
        val m = missing.catalogue.models.single { it.legacyRecord == null }
        assertNull(m.specifications.densityKgPerM); assertNull(m.specifications.mbsN)
        val runtime = CatalogueMaterialRepository(legacy, CatalogueCodec.encode(missing.catalogue)).get(m.id)
        assertNull(runtime.density); assertNull(runtime.mbs); assertNull(runtime.breakingStrain)
        assertEquals(.1, runtime.maxStrain, 0.0)
    }

    @Test fun allNonlinearKnotsInverseMonotonicityAndEnergyDerivatives() {
        for (m in imported.filter { it.type == LawType.pchip }) {
            val material = repo.get(m.id)
            val points = doc.datasets.single { it.id == m.datasetIds.single() }.points!!
            for (point in points) {
                close(point.forceN, material.tension(point.strain), 1e-12)
                assertEquals(point.strain, material.strainAt(point.forceN), 1e-12)
            }
            var previous = -1.0
            for (i in 1..99) {
                val e = material.maxStrain * i / 100
                val force = material.tension(e)
                assertTrue("${m.id}: monotonic/invertible", force > previous)
                assertTrue(material.tangent(e) >= 0)
                assertEquals(e, material.strainAt(force), 1e-12)
                val h = material.maxStrain * 1e-7
                close(force, (material.energy(e + h) - material.energy(e - h)) / (2 * h), 2e-6)
                close(material.tangent(e), (material.tension(e + h) - material.tension(e - h)) / (2 * h), 2e-5)
                previous = force
            }
            assertEquals(0.0, material.tension(-.01), 0.0)
            assertEquals(0.0, material.energy(-.01), 0.0)
            assertEquals(0.0, material.tangent(-.01), 0.0)
        }
    }

    @Test fun allProvisionalLinearModelsAreBoundedApproximations() {
        for (m in imported.filter { it.type == LawType.provisional_linear }) {
            val material = repo.get(m.id)
            val p = doc.datasets.single { it.id == m.datasetIds.single() }.points!!.single { it.forceN > 0 }
            val ea = p.forceN / p.strain
            for (ratio in listOf(.0, .01, .2, .8, 1.0)) {
                val e = p.strain * ratio
                close(ea * e, material.tension(e), 1e-12)
                close(.5 * ea * e * e, material.energy(e), 1e-12)
                close(ea, material.tangent(e), 1e-12)
                assertEquals(e, material.strainAt(p.forceN * ratio), 1e-12)
            }
            assertEquals(p.strain, material.maxStrain, 0.0)
            assertNull(material.continuation)
            assertNotNull(material.modelWarning)
            assertEquals(StrainRange(p.strain, p.strain), m.domain.suppliedRange)
            val plot = material.curvePlot(0.0, material.maxStrain * 100)
            assertNull(plot.error)
            assertEquals(1, plot.samples.size)
            assertTrue(plot.segments.all { it.region == CurveRegion.EXTRAPOLATED })
            fails { material.tension(Math.nextUp(material.maxStrain)) }
        }
    }

    @Test fun noImportedContinuationOrSyntheticRuptureAndAllLimitsEnforced() {
        for (m in imported) {
            val material = repo.get(m.id)
            assertNull(material.continuation)
            if (material.mbs == null || material.mbs!! > material.tension(material.maxStrain)) assertNull(material.breakingStrain)
            for (call in listOf(material::tension, material::tangent, material::energy)) {
                fails { call(Math.nextUp(material.maxStrain)) }; fails { call(Double.NaN) }
            }
            fails { material.strainAt(material.tension(material.maxStrain) + .01) }
        }
        val m = imported.first()
        val unsupported = m.copy(domain = m.domain.copy(continuation = ContinuationRule.legacy_bounded))
        fails { CatalogueCodec.encode(doc.copy(models = doc.models.map { if (it.id == m.id) CatalogueIdentity.seal(unsupported, doc.datasets) else it })) }
        val beyondStrength = importRows(synthetic("[{\"kn\":31,\"percent\":10}]"))
        assertTrue(onlyNew(beyondStrength).conflictFlags.contains("source_curve_exceeds_declared_strength"))
        assertTrue(beyondStrength.catalogue.models.none { it.legacyRecord == null })
    }

    @Test fun batchesNominalAndTerminationStrengthsStaySeparate() {
        val a = dataset(261); val f = dataset(257)
        assertNotNull(a.versionId); assertNull(a.batchId); assertNull(f.batchId)
        assertEquals(setOf("W26DZ", "2020"), doc.batches.map { it.batchCode }.toSet())
        assertTrue(doc.models.filter { it.legacyRecord == null }.all { it.batchId == null })
        val axiom = doc.products.single { it.id == a.productId }.specifications
        assertEquals(39000.0, axiom.mbsN!!, 0.0)
        assertEquals(39930.0, axiom.information.getValue("batch_strength_evidence").jsonObject.getValue("ratings").jsonObject.getValue("mbs_n").jsonPrimitive.double, 0.0)
        assertTrue(axiom.terminationStrengths.any { it.strengthN == 46000.0 })
        assertNull(repo.get("webbings:model:257").mbs)
        assertEquals(.072, repo.get("webbings:model:257").maxStrain, 1e-16)
        val source = doc.products.single { it.id == dataset(70).productId }.specifications.information
            .getValue("source_evidence_70").jsonObject.getValue("termination_strengths").jsonArray
        assertTrue(source.any { it.jsonObject.getValue("termination").jsonPrimitive.content == "weblock" && it.jsonObject.getValue("strength_n").jsonPrimitive.double == 30300.0 })
        for (id in listOf(253, 259, 262)) assertNull(doc.products.single { it.id == dataset(id).productId }.specifications.mbsN)
    }

    @Test fun namedReconciliationCasesDoNotOverwriteOrInventBatches() {
        for (id in listOf(24, 58, 190, 205)) assertEquals(DatasetDisposition.review_only, CatalogueValidation.assess(dataset(id)).disposition)
        assertEquals(20, dataset(70).points!!.size)
        assertNull(dataset(70).batchId)
        assertTrue(dataset(70).uncertaintyNotes.any { it.contains("second") || it.contains("separate higher-load test") })
        assertSame(legacy.get("raed__motm__refined_raed_chart_digitization_0_25_kn"), repo.get("raed__motm__refined_raed_chart_digitization_0_25_kn"))
        assertTrue(repo.limitations("slack_mountain__cobalt__manufacturer_product_specification").any { it.contains("NOT FOR HIGHLINE") })
        assertFalse(repo.limitations("webbings:model:257").any { it.contains("Manufacturer restriction") })
    }

    @Test fun missingMassCannotBeSelectedForGravityAndExcludedDatasetsHaveNoEvaluator() {
        val missing = repo.get("webbings:model:251")
        assertNull(missing.density); assertNull(missing.mbs); assertNull(missing.breakingStrain)
        assertFalse(repo.webbings(missing.manufacturer).any { it.id == missing.id })
        val selection = Selection(missing.manufacturer, missing.id)
        val draft = rig(selection)
        assertTrue(draft.validate(repo).errors.any { it.contains("density is missing") })
        assertEquals(51, repo.excludedDatasets.size)
        for (d in repo.excludedDatasets) fails { repo.get("webbings:model:${d.id.substringAfterLast(':')}") }
    }

    @Test fun legacyObjectIdentitiesDefaultsFingerprintsAndAllNumericalSamplesPreserved() {
        val baseline = Json.parseToJsonElement(read("app/src/test/resources/phase01-baseline.json")).jsonObject
        for (sample in baseline.getValue("materials").jsonArray) {
            val r = sample.jsonObject; val id = r.getValue("id").jsonPrimitive.content
            val m = repo.get(id)
            assertSame(legacy.get(id), m)
            assertEquals(r.getValue("fingerprint").jsonPrimitive.content, materialFingerprint(m.metadata.toString()))
            assertEquals(legacy.previousRevisions(id), repo.previousRevisions(id))
            for (value in r.getValue("values").jsonArray) {
                val v = value.jsonObject; val e = v.getValue("strain").jsonPrimitive.double
                assertEquals(v.getValue("force_N").jsonPrimitive.double, m.tension(e), 0.0)
                assertEquals(v.getValue("tangent_N").jsonPrimitive.double, m.tangent(e), 0.0)
                assertEquals(v.getValue("energy_J_per_m").jsonPrimitive.double, m.energy(e), 0.0)
                assertEquals(v.getValue("inverse_strain").jsonPrimitive.double, m.strainAt(m.tension(e)), 0.0)
            }
        }
        val old = legacy.versionedCatalogue().document
        assertEquals(old.models, doc.models.filter { it.legacyRecord != null })
        for (m in old.models.filter { it.curatedDefault })
            assertEquals(m.reference(), CatalogueSelection.defaultForNewSelection(doc, m.productId))
    }

    @Test fun savedRigSchemasOneAndTwoKeepExactSelectionsAndFingerprints() {
        val baseline = Json.parseToJsonElement(read("app/src/test/resources/phase01-baseline.json")).jsonObject
        for (sample in baseline.getValue("libraries").jsonArray) {
            val r = sample.jsonObject
            val library = RigLibraryCodec.decode(r.getValue("input").jsonPrimitive.content)
            assertEquals(r.getValue("reserialized").jsonPrimitive.content, RigLibraryCodec.encode(library))
            library.rigs.forEach { assertTrue(it.materialProblems(repo).isEmpty()) }
        }
        val id = "webbings:model:10" // Existing product, independently selectable imported curve.
        val material = repo.get(id)
        assertTrue(repo.alternatives(id).any { it.id != id && it.isDefault })
        val config = RigConfiguration.from(rig(Selection(material.manufacturer, id)))
        val saved = SavedRig("imported", "Explicit imported curve", 1, config, config.fingerprints(repo))
        val restored = RigLibraryCodec.decode(RigLibraryCodec.encode(RigLibraryDocument(rigs = listOf(saved)))).rigs.single()
        assertEquals(saved, restored); assertTrue(restored.materialProblems(repo).isEmpty())
        val changedDefault = doc.copy(models = doc.models.map { m ->
            if (m.productId == imported.single { it.id == id }.productId) m.copy(curatedDefault = m.id == id) else m
        })
        val changedRepo = CatalogueMaterialRepository(legacy, CatalogueCodec.encode(changedDefault))
        assertTrue(restored.materialProblems(changedRepo).isEmpty())
        assertEquals(config.fingerprints(repo), config.fingerprints(changedRepo))
        val tampered = restored.copy(materialFingerprints = mapOf(id to "0".repeat(64)))
        assertTrue(tampered.materialProblems(repo).isNotEmpty())
        val model = imported.single { it.id == id }
        fails { repo.resolve(model.reference().copy(revision = "missing")) }
        fails { repo.resolve(model.reference().copy(contentFingerprint = "0".repeat(64))) }
    }

    @Test fun representativeLegacyEquilibriumIsExactlyUnchangedThroughExpandedRepository() {
        fun select(name: String) = legacy.webbings("RAED").first { it.product == name }.let { Selection(it.manufacturer, it.id) }
        val s = SectionDraft("17.5", select("Parsec"), select("MOTM"), "5")
        val draft = Draft(span = "35", leftHeight = "0", rightHeight = "0", mass = "80", position = "12", pretensionKn = "1.5",
            kind = SetupKind.MIXED, sections = listOf(s, s), cutEnabled = true, cutPosition = "26", walkerHeight = "1.8", leash = "1.5")
        val actual = DiagnosticRunner.run(draft, repo)
        assertNull(actual.failure)
        val baseline = Json.parseToJsonElement(read("app/src/test/resources/phase01-baseline.json")).jsonObject
        assertEquals(baseline.getValue("rig_states"), actual.report.document.getValue("states"))
        assertEquals(baseline.getValue("rig_energy_ledger"), actual.report.document.getValue("energy_ledger"))
    }

    @Test fun importedDiagnosticsReplayExactBoundedLawAndEquilibrium() {
        val material = repo.get("webbings:model:1")
        val run = DiagnosticRunner.run(rig(Selection(material.manufacturer, material.id)), repo)
        assertNull(run.failure)
        val replay = DiagnosticRunner.replay(run.report.text())
        assertNull(replay.failure)
        assertEquals(run.report.document.getValue("states"), replay.report.document.getValue("states"))
        val snapshots = JsonMaterialRepository(run.report.document.getValue("material_data").toString())
        assertEquals(material.maxStrain, snapshots.get(material.id).maxStrain, 0.0)
        assertNull(snapshots.get(material.id).continuation)
        fails { Webbing(material.id, JsonObject(material.metadata - "catalogue_max_strain")) }
    }

    private fun rig(selection: Selection) = Draft(span = "35", leftHeight = "0", rightHeight = "0", mass = "80", position = "12",
        pretensionKn = "1.5", sections = listOf(SectionDraft("35", selection, selection, "5")))
}

package org.linesolver.base

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.material.*
import org.linesolver.base.material.catalogue.*
import org.linesolver.base.model.*
import java.io.File

class MaterialEditorTest {
    companion object {
        private val root=File(requireNotNull(System.getProperty("catalogueProjectRoot")))
        private val legacy=JsonMaterialRepository(File(root,"data/materials/webbing_material_models_v1.json").readText())
        private val shipped=CatalogueMaterialRepository(legacy,File(root,"catalogue/webbing-catalogue-v1.json").readText()).document
        private fun sample()=MaterialEdit(name="Synthetic editor tape (test only)",brand="Fixture",mass="0.05",mbs="30000",
            aliases="Duplicate name\nFixture alias",source="Synthetic test fixture; no real measurement",license="Test data only",
            origin="own_measured",table="T/kN,epsilon/%\n1,1\n2,2.5\n3,4.5",protocol="Synthetic protocol",uncertainty="Uncertainty unknown")
        private fun repo(p:MaterialPackage)=CatalogueMaterialRepository(legacy,CatalogueCodec.encode(MaterialPackages.combine(shipped,p)),p.retiredModelIds.toSet())
        private fun fails(block:()->Unit) { assertTrue("Expected rejection",runCatching(block).isFailure) }
    }
    private class Disk {
        var text:String?=null;var fail=false
        fun store()=MaterialOverlayStore(shipped,{text}) { if(fail)error("Synthetic interrupted write");text=it }
    }
    @Test fun createReopenRevisionBackupRestoreKeepsRawDataAndIdentity() {
        val disk=Disk();val store=disk.store();val edit=sample().copy(version="Synthetic V2",batch="SYNTHETIC-BATCH-ONLY",syntheticBatch=true)
        MaterialEditing.save(store,edit)
        val first=store.value;assertEquals(1,first.catalogue.models.size)
        val model=first.catalogue.models.single();val dataset=first.catalogue.datasets.single()
        assertEquals(PointOrigin.measured,dataset.points!!.first().origin)
        assertEquals(edit.table,dataset.rawPayload!!["editor"]!!.jsonObject["table"]!!.jsonPrimitive.content)
        assertEquals("user-declared; not independently verified",dataset.rawPayload!!["batch_provenance"]!!.jsonPrimitive.content)
        assertTrue(first.catalogue.batches.single().provenance.note!!.contains("User-declared"))
        assertTrue(first.catalogue.batches.single().syntheticExample)
        assertNotNull(model.versionId);assertNotNull(model.batchId)
        assertEquals(model.reference(),disk.store().value.catalogue.models.single().reference())
        val loaded=MaterialEditing.load(MaterialPackages.combine(shipped,first),dataset,model.id)
        MaterialEditing.save(store,loaded.copy(table="T/kN,epsilon/%\n1,1.1\n2,2.7\n3,4.7"))
        assertEquals(model,store.value.catalogue.models.first());assertEquals(dataset,store.value.catalogue.datasets.first())
        assertEquals("2",store.value.catalogue.models.last().revision)
        assertNotEquals(model.contentFingerprint,store.value.catalogue.models.last().contentFingerprint)
        val backup=store.export();val restored=Disk().store();restored.import(backup)
        assertEquals(backup,restored.export());assertEquals(store.value.namespace,restored.value.namespace)
        assertEquals(store.value.catalogue.models.toSet(),restored.value.catalogue.models.toSet())
        assertEquals(store.value.catalogue.datasets.toSet(),restored.value.catalogue.datasets.toSet())
        assertEquals(store.value.catalogue.products.toSet(),restored.value.catalogue.products.toSet())
        assertEquals(store.value.catalogue.versions.toSet(),restored.value.catalogue.versions.toSet())
        assertEquals(store.value.catalogue.batches.toSet(),restored.value.catalogue.batches.toSet())
        assertEquals(repo(first).get(model.id).tension(.025),repo(restored.value).get(model.id).tension(.025),0.0)
    }
    @Test fun multipleFitsReuseDatasetAndShippedOverlayNeverMutatesBundledRecords() {
        val store=Disk().store();val product=shipped.products.first()
        val baseline=CatalogueCodec.encode(shipped)
        MaterialEditing.save(store,sample().copy(productId=product.id,name="Overlay name"))
        val m=store.value.catalogue.models.single();val d=store.value.catalogue.datasets.single()
        assertTrue(store.value.catalogue.products.isEmpty())
        MaterialEditing.save(store,MaterialEditing.load(MaterialPackages.combine(shipped,store.value),d,m.id))
        assertEquals(1,store.value.catalogue.datasets.size);assertEquals(2,store.value.catalogue.models.size)
        assertEquals(m.datasetIds,store.value.catalogue.models.last().datasetIds)
        assertEquals(baseline,CatalogueCodec.encode(shipped))
        assertTrue(repo(store.value).alternatives(m.id).any { it.id==m.id })
        val bundled=shipped.models.first { it.productId==product.id && it.legacyRecord!=null }
        assertSame(legacy.get(bundled.id),repo(store.value).get(bundled.id))
    }
    @Test fun ambiguousAliasesDoNotMergeAndVersionsBatchesStaySeparate() {
        val store=Disk().store()
        MaterialEditing.save(store,sample())
        MaterialEditing.save(store,sample())
        assertEquals(2,store.value.catalogue.products.size)
        val first=store.value.catalogue.products.first()
        MaterialEditing.save(store,sample().copy(productId=first.id,version="Synthetic generation",batch="SYNTHETIC-ONLY"))
        MaterialEditing.save(store,sample().copy(productId=first.id,versionId=store.value.catalogue.versions.single().id,version="Synthetic generation"))
        val models=store.value.catalogue.models.filter { it.productId==first.id }
        assertNull(models.first().batchId);assertNotNull(models[1].batchId);assertNull(models.last().batchId)
        assertEquals(3,repo(store.value).alternatives(models.first().id).size)
        assertEquals(2,repo(store.value).webbings("Fixture").size)
    }
    @Test fun csvUnitsLengthDecimalCommaAndScientificNotation() {
        val expected=MeasurementCsv.parse(sample()).points
        val n=sample().copy(forceUnit="N",strainUnit="1",table="1000,0.01\n2000,0.025\n3000,0.045")
        assertEquals(expected,MeasurementCsv.parse(n).points)
        val comma=n.copy(table="1000;0,01\n2000;0,025\n3000;0,045")
        assertEquals(expected,MeasurementCsv.parse(comma).points)
        assertEquals(expected,MeasurementCsv.parse(n.copy(table="1e3,1e-2\n2e3,2.5e-2\n3e3,4.5e-2")).points)
        val extension=sample().copy(strainUnit="mm",gaugeM="2",table="1,20\n2,50\n3,90")
        assertEquals(expected,MeasurementCsv.parse(extension).points)
        assertTrue(MeasurementCsv.parse(extension.copy(gaugeM="0")).errors.isNotEmpty())
        assertEquals(expected,MeasurementCsv.parse(extension.copy(strainUnit="m",table="1,0.02\n2,0.05\n3,0.09")).points)
        val swapped=sample().copy(forceColumn=1,strainColumn=0,table="epsilon/%,T/kN\n1,1\n2.5,2\n4.5,3")
        assertEquals(expected,MeasurementCsv.parse(swapped).points)
        assertEquals("kN",MeasurementCsv.detect(swapped.table)!!.first)
        assertNull(MeasurementCsv.detect("load;stretch\n1;1"))
        assertTrue(MeasurementCsv.parse(sample().copy(table="load;stretch\n1;1")).errors.isNotEmpty())
        assertEquals(1,MeasurementCsv.parse(sample().copy(table="load;stretch\n1;1",csvHasHeader=true)).points.size)
    }
    @Test fun invalidCsvPreservedAsReviewAndNeverPromoted() {
        for(text in listOf("NaN,1","Infinity,1","1,NaN","1,","1,2\n2,1","1,1\n2,1","-1,1","1,0","\"unclosed,1","\"1.234,56\",1")) {
            val store=Disk().store();val edit=sample().copy(table=text)
            MaterialEditing.save(store,edit)
            assertTrue("Must not promote $text",store.value.catalogue.models.isEmpty())
            assertEquals(text,MaterialEditing.load(MaterialPackages.combine(shipped,store.value),store.value.catalogue.datasets.single()).table)
            assertEquals(store.export(),MaterialPackages.encode(MaterialPackages.decode(store.export(),shipped),shipped))
        }
    }
    @Test fun pchipUsesNativeForceInverseTangentAndAnalyticEnergy() {
        val store=Disk().store();MaterialEditing.save(store,sample());val m=repo(store.value).get(store.value.catalogue.models.single().id)
        assertTrue(m.hasImposedOrigin)
        val observations=store.value.catalogue.datasets.single().points!!
        assertEquals(3,observations.size);assertFalse(observations.any { it.origin==PointOrigin.synthetic_origin })
        observations.forEach { p -> assertEquals(p.forceN,m.tension(p.strain),1e-9);assertEquals(p.strain,m.strainAt(p.forceN),1e-10) }
        for(e in listOf(.005,.015,.035)) {
            val h=1e-7
            assertEquals(m.tangent(e),(m.tension(e+h)-m.tension(e-h))/(2*h),.01)
            assertEquals(m.tension(e),(m.energy(e+h)-m.energy(e-h))/(2*h),1e-5)
        }
        assertEquals(CurveRegion.EXTRAPOLATED,m.curveRegion(.005));assertEquals(CurveRegion.SUPPLIED,m.curveRegion(.02))
        fails { m.tension(m.maxStrain+1e-5) }
    }
    @Test fun provisionalBoundsUnknownMassAndMbsReviewBranches() {
        val store=Disk().store();MaterialEditing.save(store,sample().copy(mass="",mbs="",table="1,2"))
        val m=repo(store.value).get(store.value.catalogue.models.single().id)
        assertTrue(m.provisionalLinear);assertNull(m.density);assertNull(m.mbs)
        assertEquals(500.0,m.tension(.01),1e-9);assertEquals(2.5,m.energy(.01),1e-9)
        fails { m.tension(.020001) };assertFalse(repo(store.value).manufacturers().contains("Fixture"))
        for(edit in listOf(sample().copy(branch="unloading"),sample().copy(origin="estimated"),sample().copy(mbs="1000"))) {
            val review=Disk().store();MaterialEditing.save(review,edit);assertTrue(review.value.catalogue.models.isEmpty())
        }
        fails { MaterialEditing.save(Disk().store(),sample().copy(origin="verified_by_magic")) }
    }
    @Test fun measuredOriginRemainsMeasuredAndInvalidOriginCannotBecomeKnot() {
        val store=Disk().store();MaterialEditing.save(store,sample().copy(table="0,0\n1,1\n2,2.5"))
        val m=repo(store.value).get(store.value.catalogue.models.single().id)
        assertFalse(m.hasImposedOrigin);assertEquals(PointOrigin.measured,store.value.catalogue.datasets.single().points!!.first().origin)
        val p=store.value;val d=p.catalogue.datasets.single()
        val invalid=d.copy(points=d.points!!.mapIndexed { i,point -> if(i==1)point.copy(origin=PointOrigin.synthetic_origin)else point })
        fails { MaterialPackages.combine(shipped,p.copy(catalogue=p.catalogue.copy(datasets=listOf(invalid)))) }
    }
    @Test fun importsIdempotentConflictsForkAndInvalidReferencesRollback() {
        val disk=Disk();val store=disk.store();MaterialEditing.save(store,sample());val backup=store.export()
        store.import(backup);assertEquals(backup,store.export())
        val p=store.value;val product=p.catalogue.products.single()
        val changed=p.copy(catalogue=p.catalogue.copy(products=listOf(product.copy(name="Conflicting metadata"))))
        fails { store.import(MaterialPackages.encode(changed,shipped)) };assertEquals(backup,store.export())
        store.import(MaterialPackages.encode(changed,shipped),ImportPolicy.import_as_distinct)
        assertEquals(2,store.value.catalogue.models.size);assertEquals(2,store.value.catalogue.products.size)
        assertNotEquals(p.catalogue.models.single().contentFingerprint,store.value.catalogue.models.last().contentFingerprint)
        assertNotNull(store.value.catalogue.datasets.last().rawPayload!!["import_id_mapping"])
        val before=store.export()
        for(bad in listOf(backup.replace("\"package_version\":1","\"package_version\":2"),backup.replace("\"schema_version\":1","\"schema_version\":99"),"{",backup.replace(product.id,"missing:product"))) {
            fails { store.import(bad) };assertEquals(before,store.export())
        }
        disk.fail=true;fails { MaterialEditing.save(store,sample()) };assertEquals(before,store.export());assertEquals(before,disk.text)
    }
    @Test fun tamperingHashesUnitsAndShippedIdsIsRejected() {
        val store=Disk().store();MaterialEditing.save(store,sample());val p=store.value;val m=p.catalogue.models.single()
        fails { MaterialPackages.combine(shipped,p.copy(catalogue=p.catalogue.copy(models=listOf(m.copy(contentFingerprint="0".repeat(64)))))) }
        fails { MaterialPackages.combine(shipped,p.copy(catalogue=p.catalogue.copy(models=listOf(m.copy(id=shipped.models.first().id))))) }
        fails { MaterialPackages.combine(shipped,p.copy(catalogue=p.catalogue.copy(products=p.catalogue.products+p.catalogue.products))) }
        val d=p.catalogue.datasets.single();val corrupt=d.copy(points=d.points!!.map { it.copy(forceN=it.forceN+1) })
        val resealed=CatalogueIdentity.seal(m,listOf(corrupt))
        fails { MaterialPackages.combine(shipped,p.copy(catalogue=p.catalogue.copy(datasets=listOf(corrupt),models=listOf(resealed)))) }
        val unsupported=CatalogueIdentity.seal(m.copy(domain=m.domain.copy(continuation=ContinuationRule.legacy_bounded)),listOf(d))
        fails { MaterialPackages.combine(shipped,p.copy(catalogue=p.catalogue.copy(models=listOf(unsupported)))) }
    }
    @Test fun savedRigAndIndependentSelectionsSurviveEditsRetirementRestartAndModeConversion() {
        val disk=Disk();val store=disk.store();MaterialEditing.save(store,sample());val first=store.value.catalogue.models.single()
        MaterialEditing.save(store,sample().copy(productId=first.productId,name="Renamed display",brand="Renamed brand",table="1,2\n2,4\n3,6"));val second=store.value.catalogue.models.last()
        val materials=repo(store.value)
        val uniform=Draft(span="35").withUniform(UniformGenerator("2",Selection("Fixture",first.id),Selection("Renamed brand",second.id),"5"))
        val mixed=uniform.toMixed();assertEquals(first.id,mixed.sections.first().main.materialId);assertEquals(second.id,mixed.sections.last().backup.materialId)
        val config=RigConfiguration.from(uniform);val saved=SavedRig("test","Synthetic rig",0,config,config.fingerprints(materials))
        store.retire(first.id)
        val restored=repo(disk.store().value)
        assertTrue(saved.materialProblems(restored).isEmpty());assertEquals(first.contentFingerprint,saved.materialFingerprints[first.id])
        assertEquals(materials.get(first.id).tension(.025),restored.get(first.id).tension(.025),0.0)
        assertEquals(second.id,restored.webbings("Renamed brand").single().id)
        assertEquals("Fixture",restored.get(first.id).manufacturer)
        assertEquals(first.id,RigLibraryCodec.decode(RigLibraryCodec.encode(RigLibraryDocument(rigs=listOf(saved)))).rigs.single().configuration.uniform!!.main.materialId)
        fails { store.deleteDataset(first.datasetIds.single()) }
    }
    @Test fun unreadableStoreBlocksWritesAndAllowsExplicitRecovery() {
        val disk=Disk();disk.text="broken";val broken=disk.store();assertNotNull(broken.readError)
        fails { MaterialEditing.save(broken,sample()) };assertEquals("broken",disk.text)
        val good=Disk().store();MaterialEditing.save(good,sample());broken.restoreUnreadable(good.export())
        assertNull(broken.readError);assertEquals(good.value,broken.value)
    }
    @Test fun csvExportPreservesLexemesAndLengthReferenceInJson() {
        val store=Disk().store();val input=sample().copy(strainUnit="mm",gaugeM="2",table="T/kN;extension/mm\n1,00;20,0\n2,00;50,0",importedCsvText="T/kN;extension/mm\r\n1,00;20,0\r\n2,00;50,0")
        MaterialEditing.save(store,input);val d=store.value.catalogue.datasets.single()
        val csv=MeasurementCsv.export(d)
        assertTrue(csv.contains("\"1,00\",\"20,0\""))
        assertEquals(d.points,MeasurementCsv.parse(input.copy(table=csv)).points)
        assertEquals("2",MaterialEditing.load(MaterialPackages.combine(shipped,store.value),d).gaugeM)
        assertEquals(input.table,MaterialEditing.load(MaterialPackages.combine(shipped,store.value),d).table)
        val restored=MaterialPackages.decode(store.export(),shipped)
        assertEquals(input.importedCsvText,MaterialEditing.load(MaterialPackages.combine(shipped,restored),restored.catalogue.datasets.single()).importedCsvText)
    }
    @Test fun productDeletionChecksDependenciesAndReviewCorrectionRetainsEvidence() {
        val store=Disk().store();MaterialEditing.save(store,sample().copy(table="1,1\n2,1"))
        val product=store.value.catalogue.products.single();val review=store.value.catalogue.datasets.single()
        fails { store.deleteProduct(product.id) }
        val repaired=MaterialEditing.load(MaterialPackages.combine(shipped,store.value),review).copy(table="1,1\n2,2")
        MaterialEditing.save(store,repaired)
        assertEquals(review,store.value.catalogue.datasets.first())
        assertEquals(1,store.value.catalogue.models.size)
        store.deleteDataset(review.id);assertEquals(1,store.value.catalogue.datasets.size)
        fails { store.deleteProduct(product.id) }
        val empty=Disk().store();MaterialEditing.save(empty,sample().copy(table=""))
        empty.deleteDataset(empty.value.catalogue.datasets.single().id);empty.deleteProduct(empty.value.catalogue.products.single().id)
        assertTrue(empty.value.catalogue.products.isEmpty())
    }
    @Test fun conflictingJsonCanCreateNewRevisionsWithoutChangingOldRigOrProduct() {
        val store=Disk().store();MaterialEditing.save(store,sample())
        val original=store.value;val old=original.catalogue.models.single();val dataset=original.catalogue.datasets.single()
        val revised=MaterialEditing.preview(sample().copy(productId=old.productId,brand="Corrected source brand",table="1,2\n2,4\n3,6"),
            old.productId,dataset.id,old.id,old.revision)
        val incoming=original.copy(catalogue=original.catalogue.copy(datasets=listOf(revised.dataset),models=listOf(revised.model!!),
            products=original.catalogue.products.map { it.copy(name="Imported conflicting name") }))
        val text=MaterialPackages.encode(incoming,shipped)
        fails { store.import(text) }
        store.import(text,ImportPolicy.create_new_revisions)
        assertEquals(original.catalogue.products,store.value.catalogue.products)
        assertEquals(old,store.value.catalogue.models.first());assertEquals(dataset,store.value.catalogue.datasets.first())
        assertEquals(2,store.value.catalogue.models.size)
        val next=store.value.catalogue.models.last();assertEquals(old.productId,next.productId);assertEquals("2",next.revision)
        assertNotEquals(old.id,next.id);assertNotEquals(old.contentFingerprint,next.contentFingerprint)
        assertEquals(2000.0,repo(store.value).get(old.id).tension(.025),1e-9)
        assertTrue(repo(store.value).get(next.id).tension(.025)<2000)
        val once=store.export();store.import(text,ImportPolicy.create_new_revisions);assertEquals(once,store.export())
        val restored=Disk().store();restored.import(once);assertEquals(once,restored.export())
        val review=Disk().store();MaterialEditing.save(review,sample().copy(table="1,1\n2,1"))
        val r=review.value;val rd=r.catalogue.datasets.single()
        val corrected=MaterialEditing.preview(sample().copy(productId=rd.productId,table="1,1\n3,1"),rd.productId,rd.id).dataset
        val reviewText=MaterialPackages.encode(r.copy(catalogue=r.catalogue.copy(datasets=listOf(corrected))),shipped)
        review.import(reviewText,ImportPolicy.create_new_revisions)
        assertEquals(2,review.value.catalogue.datasets.size);assertTrue(review.value.catalogue.models.isEmpty())
        val reviewOnce=review.export();review.import(reviewText,ImportPolicy.create_new_revisions);assertEquals(reviewOnce,review.export())
    }
}

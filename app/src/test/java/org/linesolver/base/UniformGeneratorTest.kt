package org.linesolver.base

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.model.*
import org.linesolver.base.material.JsonMaterialRepository
import org.linesolver.base.diagnostics.DiagnosticRunner

class UniformGeneratorTest {
    private val materials=JsonMaterialRepository(javaClass.classLoader!!.getResource("webbing_material_models_v1.json")!!.readText())
    private fun rig(n:Int=5):Draft {
        fun select(product:String)=Selection("RAED",materials.webbings("RAED").first{it.product==product}.id)
        return Draft(span="35",leftHeight="0",rightHeight="0",mass="80",position="12",pretensionKn="1.5",
            walkerHeight="1.8",leash="1.5").withUniform(UniformGenerator(n.toString(),select("Parsec"),select("MOTM"),"5"))
    }
    @Test fun countsLengthsMaterialsAndSplitLocations() {
        for(n in listOf(1,2,5,7,12)) {
            val d=rig(n).copy(span="100");val sections=d.activeSections
            assertEquals(n,sections.size);assertEquals(100.0,sections.sumOf{it.length.number()!!},1e-12)
            sections.forEach{assertEquals(100.0/n,it.length.number()!!,0.0);assertEquals(d.uniform!!.main,it.main);assertEquals(d.uniform.backup,it.backup)}
            assertEquals(n-1,d.schematicSplitFractions().size)
            d.schematicSplitFractions().forEachIndexed{i,f->assertEquals((i+1).toDouble()/n,f,1e-15)}
            assertEquals(n-1,d.validate(materials).setup!!.contacts.connections.size)
            assertTrue(d.sections.isEmpty())
        }
    }
    @Test fun totalMetresAndPercentHaveSameLocalRatios() {
        val d=rig().copy(span="100");val metres=d.withUniform(d.uniform!!.withExtraUnit(ExtraUnit.METRES,d.span))
        assertEquals("5.0",metres.uniform!!.extra)
        assertEquals(d.validate(materials).setup,metres.validate(materials).setup)
        assertEquals(5.0,metres.activeSections.sumOf{it.extra.number()!!},0.0)
        assertEquals(d.validate(materials).setup,metres.withUniform(metres.uniform.withExtraUnit(ExtraUnit.PERCENT,d.span)).validate(materials).setup)
    }
    @Test fun updatesHaveNoStaleGeneratedCopy() {
        val d=rig().copy(span="100");val changed=d.withUniform(d.uniform!!.copy(count="2",extra="8",extraUnit=ExtraUnit.METRES,main=d.uniform.backup)).copy(span="120")
        assertEquals(listOf("60.0","60.0"),changed.activeSections.map{it.length})
        assertEquals(listOf("4.0","4.0"),changed.activeSections.map{it.extra})
        assertTrue(changed.activeSections.all{it.main==d.uniform.backup});assertTrue(changed.sections.isEmpty())
    }
    @Test fun conversionPreservesMechanicalInputsAndNumericalStates() {
        val d=rig().copy(cutEnabled=true,cutPosition="26");val mixed=d.toMixed()
        assertNull(mixed.uniform);assertEquals(d.activeSections,mixed.sections)
        assertEquals(d.validate(materials).setup,mixed.validate(materials).setup)
        val before=DiagnosticRunner.run(d,materials);val after=DiagnosticRunner.run(mixed,materials)
        assertNull(before.failure);assertNull(after.failure)
        assertEquals(before.report.document.getValue("states"),after.report.document.getValue("states"))
        assertEquals(before.report.document.getValue("energy_ledger"),after.report.document.getValue("energy_ledger"))
        assertEquals(4,before.result!!.standing.network.structuralConnections.size)
        val replay=DiagnosticRunner.replay(before.report.text())
        assertNull(replay.failure);assertEquals(before.report.document.getValue("states"),replay.report.document.getValue("states"))
    }
    @Test fun individualEditsAndReplacementGuard() {
        val d=rig().toMixed();assertFalse(d.uniformReplacementNeeded())
        val changed=d.copy(sections=d.sections.mapIndexed{i,s->if(i==2)s.copy(extra="7") else s})
        assertTrue(changed.uniformReplacementNeeded());assertEquals(d.sections[0],changed.sections[0]);assertNotEquals(d.validate(materials).setup,changed.validate(materials).setup)
        assertTrue(d.copy(sections=d.sections.mapIndexed{i,s->if(i==1)s.copy(length="8") else s}).uniformReplacementNeeded())
        assertTrue(d.copy(sections=d.sections.mapIndexed{i,s->if(i==1)s.copy(main=s.backup) else s}).uniformReplacementNeeded())
    }
    @Test fun replacementProposalRetainsFirstLocalRatioAndConnections() {
        val d=rig().toMixed().copy(span="100",sections=listOf(rig().activeSections.first().copy(length="30",extra="3",extraUnit=ExtraUnit.METRES,connection=org.linesolver.base.contact.ConnectionBehavior.PASSABLE)))
        val proposal=d.withUniform(d.uniformProposal())
        assertEquals("10.0",proposal.uniform!!.extra)
        assertEquals(0.1,proposal.validate(materials).setup!!.sections.first().extraRatio,0.0)
        assertEquals(d.sections.first().connection,proposal.activeSections.first().connection)
        assertTrue(d.uniformReplacementNeeded())
    }
    @Test fun libraryRestartRestoresRecipeOrExplicitSectionsAndIndependentPreferences() {
        var disk:String?=null;val store=RigLibraryStore({disk},{disk=it})
        for(d in listOf(rig(),rig().toMixed())) {
            val c=RigConfiguration.from(d);val saved=store.save(d.kind.name,c,c.fingerprints(materials))
            val restored=RigLibraryStore({disk},{disk=it}).get(saved.id)
            assertTrue(restored.materialProblems(materials).isEmpty())
            val globals=d.copy(mass="91",position="13",gravity="9.7")
            val loaded=restored.configuration.applyTo(globals)
            assertEquals(c,RigConfiguration.from(loaded));assertEquals(WalkerPreferences.from(globals),WalkerPreferences.from(loaded));assertEquals(ModelPreferences.from(globals),ModelPreferences.from(loaded))
            assertFalse(rigHasChanges(loaded,restored));assertTrue(rigHasChanges(loaded.copy(span="36"),restored))
        }
    }
    @Test fun schemaOneRetainsLegacyPhysicalMeaningAndHiddenSections() {
        val legacy=rig().toMixed().copy(kind=SetupKind.UNIFORM)
        val c=RigConfiguration.from(legacy)
        val encoded=RigLibraryCodec.encode(RigLibraryDocument(schemaVersion=1,rigs=listOf(SavedRig("old","Old",1,c))))
        val doc=RigLibraryCodec.decode(encoded);assertEquals(2,doc.schemaVersion)
        val loaded=doc.rigs.single().configuration.applyTo(legacy)
        assertEquals(legacy,loaded);assertNull(loaded.uniform);assertEquals(1,loaded.activeSections.size);assertEquals(5,loaded.sections.size)
        assertEquals(legacy.validate(materials).setup,loaded.validate(materials).setup);assertTrue(loaded.uniformReplacementNeeded())
    }
    @Test fun invalidGeneratorsAndConflictingRepresentationsAreRejected() {
        val d=rig()
        for(n in listOf("0","101","1.5","NaN",""))assertNull(d.withUniform(d.uniform!!.copy(count=n)).validate(materials).setup)
        for(extra in listOf("-1","NaN","Infinity",""))assertNull(d.withUniform(d.uniform!!.copy(extra=extra)).validate(materials).setup)
        assertNull(d.copy(span="1e309").validate(materials).setup)
        val conflict=d.copy(sections=d.activeSections)
        assertNull(conflict.validate(materials).setup)
        assertThrows(IllegalArgumentException::class.java){RigLibraryCodec.decode(RigLibraryCodec.encode(RigLibraryDocument(rigs=listOf(SavedRig("id","Bad",1,RigConfiguration.from(conflict))))))}
        assertThrows(IllegalArgumentException::class.java){d.withUniform(d.uniform!!.copy(count="0")).toMixed()}
    }
}

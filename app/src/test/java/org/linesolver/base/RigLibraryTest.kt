package org.linesolver.base

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.model.*
import org.linesolver.base.contact.ConnectionBehavior
import org.linesolver.base.material.JsonMaterialRepository
import org.linesolver.base.diagnostics.DiagnosticRunner

class RigLibraryTest {
    private val materials=JsonMaterialRepository(javaClass.classLoader!!.getResource("webbing_material_models_v1.json")!!.readText())
    private fun rig():Draft {
        fun select(product:String)=Selection("RAED",materials.webbings("RAED").first{it.product==product}.id)
        val section=SectionDraft("17.5",select("Parsec"),select("MOTM"),"5")
        return Draft(span="35",leftHeight="0",rightHeight="0",mass="80",position="12",pretensionKn="1.5",
            kind=SetupKind.MIXED,sections=listOf(section,section.copy(extra="0.875",extraUnit=ExtraUnit.METRES)),
            cutEnabled=true,cutPosition="26",walkerHeight="1.8",leash="1.5")
    }
    @Test fun saveRestartLoadReproducesInputsAndNumericalStates() {
        var disk:String?=null
        val input=rig();val config=RigConfiguration.from(input)
        val saved=RigLibraryStore({disk},{disk=it}).save("Two sections",config,config.fingerprints(materials))
        val loaded=RigLibraryStore({disk},{disk=it}).get(saved.id).configuration.applyTo(input.copy(span="10",sections=emptyList()))
        assertEquals(input,loaded);assertEquals(input.validate(materials).setup,loaded.validate(materials).setup)
        val before=DiagnosticRunner.run(input,materials);val after=DiagnosticRunner.run(loaded,materials)
        assertNull(before.failure);assertNull(after.failure)
        assertEquals(before.report.document.getValue("states"),after.report.document.getValue("states"))
        assertEquals(before.report.document.getValue("energy_ledger"),after.report.document.getValue("energy_ledger"))
    }
    @Test fun globalsAndWalkerPositionNeverEnterSavedRecordOrChangeOnLoad() {
        val original=rig();val config=RigConfiguration.from(original)
        val current=original.copy(mass="91",walkerHeight="2",leash="1.8",position="11",gravity="9.7",elements="6",resistance="350")
        val loaded=config.applyTo(current.copy(span="20"))
        assertEquals(WalkerPreferences.from(current),WalkerPreferences.from(loaded))
        assertEquals(ModelPreferences.from(current),ModelPreferences.from(loaded))
        val text=RigLibraryCodec.encode(RigLibraryDocument(rigs=listOf(SavedRig("id","name",1,config))))
        for(field in listOf("mass","walkerHeight","leash","position","gravity","elements","resistance"))assertFalse(text.contains("\"$field\":"))
    }
    @Test fun renameDuplicateUpdateAndDeleteKeepStableIndependentIdentities() {
        var disk:String?=null;var time=100L
        val store=RigLibraryStore({disk},{disk=it},now={time++})
        val config=RigConfiguration.from(rig())
        val first=store.save("First",config,config.fingerprints(materials))
        val renamed=store.rename(first.id,"Renamed")
        assertEquals(first.id,renamed.id);assertEquals(config,renamed.configuration);assertTrue(renamed.modifiedAt>first.modifiedAt)
        val copy=store.duplicate(first.id,"Copy")
        assertNotEquals(first.id,copy.id);assertEquals(first.materialFingerprints,copy.materialFingerprints)
        val changed=config.copy(span="36")
        val updated=store.save("Renamed",changed,first.materialFingerprints,first.id)
        assertEquals(first.id,updated.id);assertEquals(config,store.get(copy.id).configuration)
        val new=store.save("Variation",changed,first.materialFingerprints)
        assertNotEquals(first.id,new.id)
        store.delete(copy.id)
        val restarted=RigLibraryStore({disk},{disk=it})
        assertEquals(2,restarted.document.rigs.size);assertEquals(changed,restarted.get(first.id).configuration)
        assertThrows(IllegalArgumentException::class.java){restarted.get(copy.id)}
    }
    @Test fun nameCollisionsCannotOverwriteRigs() {
        var disk:String?=null;val store=RigLibraryStore({disk},{disk=it});val c=RigConfiguration.from(rig())
        val entry=store.save("  Alpine  ",c,emptyMap());assertEquals("Alpine",entry.name)
        val unchanged=disk
        for(name in listOf("ALPINE"," ","x".repeat(81)))assertThrows(IllegalArgumentException::class.java){store.save(name,c,emptyMap())}
        assertEquals(unchanged,disk);assertEquals(1,store.document.rigs.size)
        assertThrows(IllegalArgumentException::class.java){store.save("New",c,emptyMap(),"missing")}
    }
    @Test fun unsavedChangesTrackOnlyRigInputsAndCanBeReverted() {
        val d=rig();val saved=SavedRig("id","name",1,RigConfiguration.from(d))
        assertFalse(rigHasChanges(d,saved))
        assertFalse(rigHasChanges(d.copy(mass="90",position="10",gravity="9.7",elements="6",walkerHeight="2",leash="2",resistance="300"),saved))
        assertTrue(rigHasChanges(d.copy(pretensionKn="2"),saved))
        assertTrue(rigHasChanges(d.copy(cutPosition="25"),saved))
        assertFalse(rigHasChanges(saved.configuration.applyTo(d.copy(span="20")),saved))
        assertFalse(rigHasChanges(Draft(),null));assertTrue(rigHasChanges(Draft(span="35"),null))
    }
    @Test fun allSectionsConnectionsUnitsAndInactiveUniformDataSurvive() {
        val d=rig().copy(mode=Mode.ADVANCED,sections=rig().sections.mapIndexed{i,s->s.copy(connection=ConnectionBehavior.entries[i%ConnectionBehavior.entries.size])})
        for(kind in SetupKind.entries) {
            val input=d.copy(kind=kind);val config=RigConfiguration.from(input)
            val decoded=RigLibraryCodec.decode(RigLibraryCodec.encode(RigLibraryDocument(rigs=listOf(SavedRig("id","rig",1,config)))))
            assertEquals(input,decoded.rigs.single().configuration.applyTo(input))
            assertEquals(input.sections,decoded.rigs.single().configuration.sections)
        }
    }
    @Test fun invalidFutureOrDamagedDocumentsAreRetainedAndCannotBeOverwritten() {
        val valid=RigLibraryCodec.encode(RigLibraryDocument())
        for(bad in listOf("broken",valid.replace("\"schemaVersion\":2","\"schemaVersion\":99"),"""{"schemaVersion":1,"newMechanicalMeaning":true}""")) {
            var disk=bad;val store=RigLibraryStore({disk},{disk=it})
            assertNotNull(store.readError)
            assertThrows(IllegalStateException::class.java){store.save("New",RigConfiguration.from(rig()),emptyMap())}
            assertEquals(bad,disk)
        }
    }
    @Test fun invalidPhysicalValuesArePreservedAndRemainUnsolvable() {
        var disk:String?=null;val store=RigLibraryStore({disk},{disk=it})
        val invalid=rig().copy(span="NaN",sections=rig().sections.map{it.copy(length="-1")})
        val entry=store.save("Repair later",RigConfiguration.from(invalid),emptyMap())
        val loaded=RigLibraryStore({disk},{disk=it}).get(entry.id).configuration.applyTo(rig())
        assertEquals(invalid,loaded);assertNull(loaded.validate(materials).setup)
    }
    @Test fun missingOrChangedMaterialsAreReportedWithoutReplacement() {
        val c=RigConfiguration.from(rig());val fingerprints=c.fingerprints(materials)
        val good=SavedRig("id","rig",1,c,fingerprints)
        assertTrue(good.materialProblems(materials).isEmpty())
        val missing=good.copy(configuration=c.copy(sections=c.sections.map{it.copy(main=Selection("RAED","removed-id"))}))
        assertTrue(missing.materialProblems(materials).all{it.contains("unavailable")})
        assertEquals("removed-id",missing.configuration.sections[0].main.materialId)
        val changed=good.copy(materialFingerprints=fingerprints.mapValues{"old-hash"})
        assertTrue(changed.materialProblems(materials).all{it.contains("changed")})
        assertEquals(c,changed.configuration)
    }
    @Test fun legacyDraftProjectionAndOptionalDefaultsRemainCompatible() {
        val old=Json.decodeFromString<Draft>(Json.encodeToString(rig()))
        assertEquals(old,RigConfiguration.from(old).applyTo(old))
        val minimal="""{"rigs":[{"id":"old","name":"Older","modifiedAt":1,"configuration":{"span":"35","leftHeight":"0","rightHeight":"0","pretensionKn":"1.5","sections":[]}}]}"""
        val decoded=RigLibraryCodec.decode(minimal).rigs.single()
        assertEquals(SetupKind.UNIFORM,decoded.configuration.kind)
        assertEquals(Mode.SIMPLE,decoded.configuration.mode)
        assertFalse(decoded.configuration.cutEnabled)
        assertTrue(decoded.materialFingerprints.isEmpty())
    }
    @Test fun failedWriteDoesNotPublishMutationOrLosePreviousEntries() {
        var disk:String?=null;var fail=false
        val store=RigLibraryStore({disk},{if(fail)throw java.io.IOException("disk full") else disk=it})
        val original=store.save("Original",RigConfiguration.from(rig()),emptyMap());val before=disk;fail=true
        assertThrows(java.io.IOException::class.java){store.delete(original.id)}
        assertEquals(original,store.get(original.id));assertEquals(before,disk)
    }
}

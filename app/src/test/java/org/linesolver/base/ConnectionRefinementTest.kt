package org.linesolver.base

import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.model.*
import org.linesolver.base.material.*
import org.linesolver.base.material.catalogue.*
import org.linesolver.base.diagnostics.DiagnosticRunner
import org.linesolver.base.energy.*
import org.linesolver.base.ui.walkerPositionInstruction
import kotlinx.serialization.json.*
import java.io.File

class ConnectionRefinementTest {
    private val root=File(requireNotNull(System.getProperty("catalogueProjectRoot")))
    private val materials=CatalogueMaterialRepository(
        JsonMaterialRepository(File(root,"data/materials/webbing_material_models_v1.json").readText()),
        File(root,"catalogue/webbing-catalogue-v1.json").readText())
    private val selection=materials.webbings("RAED").first{it.product=="Parsec"}.let{Selection(it.manufacturer,it.id)}
    private fun rig():Draft {
        val d=Draft(kind=SetupKind.MIXED,span="50",leftHeight="0",rightHeight="0",mass="80",position="12",pretensionKn="1",elements="2",
            sections=listOf(SectionDraft("25",selection,selection,"50",id="left"),SectionDraft("25",selection,selection,"50",id="right")))
        return d.copy(connections=StructuralConnections.reconcile(d))
    }
    private fun edit(d:Draft,change:(StructuralJoint)->StructuralJoint):Draft {
        val id=StructuralConnections.labeled(d).single{it.label=="C1"}.joint.id
        return d.copy(connections=d.connections!!.copy(joints=d.connections.joints.map{if(it.id==id)change(it) else it}))
    }
    private fun seam(d:Draft,state:SeamState)=edit(d){it.copy(details=it.details.copy(typeX=TypeXSeam(state,"Synthetic test assembly","Test fixture",12000.0,true)))}
    @Test fun boundarySeamDoesNotChangeEquilibriumWebbingDomainsOrCatchRest() {
        val d=rig().copy(cutEnabled=true,cutPosition="40",walkerHeight="1.8",leash="1.5")
        val base=DiagnosticRunner.run(d,materials);assertNull(base.failure)
        for(state in SeamState.entries)for(enabled in listOf(false,true)) {
            // Even an exact synthetic seam rating below longitudinal tension must not trigger rupture.
            val annotated=edit(seam(d,state)){j->j.copy(details=j.details.copy(typeX=j.details.typeX!!.copy(strengthN=1.0,lowerBound=false)))}.copy(modelConnections=enabled)
            assertTrue(StructuralConnections.errors(annotated).isEmpty())
            val run=DiagnosticRunner.run(annotated,materials);assertNull(run.failure)
            assertEquals(base.report.document["states"],run.report.document["states"])
            assertTrue(run.result!!.standing.value.tensions.max()>1.0)
            val old=base.result!!.standing.network;val new=run.result!!.standing.network
            assertEquals(old.elements.map{it.material.breakingStrain},new.elements.map{it.material.breakingStrain})
            assertEquals(old.elements.map{it.material.maxStrain},new.elements.map{it.material.maxStrain})
            assertEquals(old.elements,new.elements)
            assertTrue(new.connectorMasses.isEmpty())
            assertEquals(new.splits[1],new.physicalJoints.single{it.joint.details.typeX!=null}.node)
            assertEquals(listOf("25","25"),annotated.activeSections.map{it.length})
        }
    }
    @Test fun longitudinalHardwareRemainsIndependentOfTransverseSeamAndNoStrengthIsSummed() {
        val shackle=ConnectorLibrary.shipped.products.single{it.id=="bc_4mm_soft_single_wrap"}
        val maillon=ConnectorLibrary.shipped.products.single{it.id=="peguet_mrnz06.0"}
        val d=edit(rig()){it.copy(details=it.details.copy(hardware=listOf(
            ConnectorLibrary.item(shackle,"main-1").copy(role=HardwareRole.main),
            ConnectorLibrary.item(shackle,"main-2").copy(role=HardwareRole.main),
            ConnectorLibrary.item(maillon,"backup").copy(role=HardwareRole.backup,
                standingLoad=LoadAssumption(HardwareLoad.unloaded),failedLoad=LoadAssumption(HardwareLoad.loaded)))))}.copy(modelConnections=true)
        val baseline=DiagnosticRunner.run(d,materials);assertNull(baseline.failure)
        for(state in SeamState.entries) {
            val next=seam(d,state);val run=DiagnosticRunner.run(next,materials);assertNull(run.failure)
            assertEquals(baseline.report.document["states"],run.report.document["states"])
            assertEquals(.0128*2+.035,run.result!!.standing.network.connectorMasses.sumOf{it.massKg},1e-15)
            assertTrue(run.result.standing.connectorDiagnostics(next).all{it.individualLoadN==null})
            assertEquals(d.connections!!.joints.flatMap{it.details.hardware},next.connections!!.joints.flatMap{it.details.hardware})
        }
        // A modeling split with continuous webbing installs no implicit splice hardware.
        val continuous=DiagnosticRunner.run(rig().copy(modelConnections=true),materials)
        assertNull(continuous.failure);assertTrue(continuous.result!!.standing.network.connectorMasses.isEmpty())
    }
    @Test fun schemaSixRoundtripRetainsLegacyTiePortsSlotsNotesAndExactRevisions() {
        val d=rig();val id=StructuralConnections.labeled(d).single{it.label=="C1"}.joint.id
        val custom=ConnectorLibrary.custom("Fixture","User",ConnectorCategory.oval_maillon,.01,9000.0,"BL","axial fixture","User declaration","Synthetic only")
        val legacy=edit(StructuralConnections.declareCrossLine(d,id)){it.copy(details=JointDetails("Keep notes",listOf(
            ConnectorLibrary.item(custom,"custom-item").copy(configuration="Keep config",attachmentPorts=it.ends.take(1)),HardwareItem("empty"))))}
        val entry=SavedRig("fixture","Fixture",1,RigConfiguration.from(legacy),RigConfiguration.from(legacy).fingerprints(materials))
        val old=RigLibraryCodec.encode(RigLibraryDocument(rigs=listOf(entry)))
        assertTrue(old.contains("\"schemaVersion\":5"))
        val migrated=RigLibraryCodec.decode(old).rigs.single().configuration.applyTo(legacy)
        assertEquals(legacy,migrated)
        val annotated=seam(migrated,SeamState.broken)
        val text=RigLibraryCodec.encode(RigLibraryDocument(rigs=listOf(entry.copy(configuration=RigConfiguration.from(annotated)))))
        assertTrue(text.contains("\"schemaVersion\":6"))
        assertEquals(annotated,RigLibraryCodec.decode(text).rigs.single().configuration.applyTo(annotated))
        assertEquals(legacy.connections!!.joints.map{it.id},annotated.connections!!.joints.map{it.id})
        assertThrows(IllegalArgumentException::class.java){RigLibraryCodec.decode(text.replace("\"schemaVersion\":6","\"schemaVersion\":5"))}
    }
    @Test fun invalidSeamLocationsAndUnsourcedRatingsFailAndDetachedDataSurvives() {
        val d=rig()
        val anchor=d.connections!!.joints.first{it.kind==JointKind.anchor}
        val invalid=d.copy(connections=d.connections.copy(joints=d.connections.joints.map{if(it.id==anchor.id)it.copy(details=it.details.copy(typeX=TypeXSeam())) else it}))
        assertTrue(StructuralConnections.errors(invalid).any{it.contains("section boundary")})
        val unsourced=edit(d){it.copy(details=it.details.copy(typeX=TypeXSeam(strengthN=12000.0)))}
        assertTrue(StructuralConnections.errors(unsourced).any{it.contains("assembly and source")})
        val negative=edit(d){it.copy(details=it.details.copy(typeX=TypeXSeam(strengthN=-1.0)))}
        assertTrue(StructuralConnections.errors(negative).any{it.contains("positive")})
        val withSeam=seam(d,SeamState.broken)
        val original=withSeam.connections!!.joints.single{it.details.typeX!=null}
        val removed=withSeam.copy(sections=listOf(withSeam.sections.first().copy(length="50")))
        val reconciled=removed.copy(connections=StructuralConnections.reconcile(removed))
        assertEquals(original,reconciled.connections!!.detached.single())
        assertTrue(StructuralConnections.errors(reconciled).isEmpty())
    }
    @Test fun sliderInstructionsRequireExplicitResetAfterFailure() {
        assertTrue(walkerPositionInstruction(false,"Catch").startsWith("Illustrative"))
        assertTrue(walkerPositionInstruction(true,"Standing").startsWith("Standing shown"))
        for(mode in listOf("Catch","Rest")) {
            val text=walkerPositionInstruction(true,mode)
            assertTrue(text.startsWith("$mode shown"));assertTrue(text.contains("Reset Failure"));assertFalse(text.contains("release to reset"))
        }
    }
}

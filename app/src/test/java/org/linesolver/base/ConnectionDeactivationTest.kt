package org.linesolver.base

import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.model.*
import org.linesolver.base.ui.*
import org.linesolver.base.material.*
import org.linesolver.base.material.catalogue.*
import org.linesolver.base.energy.*
import org.linesolver.base.diagnostics.DiagnosticRunner
import java.io.File

class ConnectionDeactivationTest {
    private val root=File(requireNotNull(System.getProperty("catalogueProjectRoot")))
    private val materials=CatalogueMaterialRepository(JsonMaterialRepository(File(root,"data/materials/webbing_material_models_v1.json").readText()),File(root,"catalogue/webbing-catalogue-v1.json").readText())
    private val selection=materials.webbings("RAED").first{it.product=="Parsec"}.let{Selection(it.manufacturer,it.id)}
    private fun rig():Draft {
        val d=Draft(kind=SetupKind.MIXED,span="20",leftHeight="0",rightHeight="0",mass="80",position="6",pretensionKn="1",elements="2",walkerHeight="1.8",leash="1.5",cutEnabled=true,cutPosition="16",
            sections=listOf(SectionDraft("10",selection,selection,"50",id="left"),SectionDraft("10",selection,selection,"50",id="right")))
        return d.copy(connections=StructuralConnections.reconcile(d))
    }
    private fun spec(id:String)=ConnectorLibrary.shipped.products.single{it.id==id}
    private fun install(d:Draft,items:List<HardwareItem>,seam:TypeXSeam?=null):Draft {
        val id=StructuralConnections.labeled(d).single{it.label=="C1"}.joint.id
        return d.copy(modelConnections=true,connections=d.connections!!.copy(joints=d.connections.joints.map{if(it.id==id)it.copy(details=JointDetails("Original notes",items,seam)) else it}))
    }
    @Test fun inactiveSeamsAndTransverseHardwareHaveNoMassStrengthOrEquilibriumEffects() {
        val regular=listOf(ConnectorLibrary.item(spec("bc_4mm_soft_single_wrap"),"main").copy(role=HardwareRole.main),ConnectorLibrary.item(spec("peguet_mrnz06.0"),"backup").copy(role=HardwareRole.backup))
        val base=install(rig(),regular);val expected=DiagnosticRunner.run(base,materials);assertNull(expected.failure)
        for(state in SeamState.entries)for(mass in listOf(null,100.0)) {
            val custom=ConnectorLibrary.custom("Legacy transverse seam","User",ConnectorCategory.sewn_transverse,mass,1.0,"MBS","Legacy assembly","Preserved source","Fixture only")
            val d=install(rig(),regular+ConnectorLibrary.item(custom,"inactive"),TypeXSeam(state,"Original assembly","Original source",1.0))
            assertTrue(d.hasInactiveConnectionMetadata());assertTrue(StructuralConnections.errors(d).isEmpty())
            val run=DiagnosticRunner.run(d,materials);assertNull(run.failure)
            assertEquals(expected.report.document["states"],run.report.document["states"])
            assertEquals(base.connectorMassSignature(),d.connectorMassSignature())
            for(s in listOf(run.result!!.standing,run.result.catch!!,run.result.rest!!)) {
                assertEquals(listOf("main","backup"),s.network.connectorMasses.map{it.itemId})
                assertEquals(listOf("main","backup"),s.connectorDiagnostics(d).map{it.itemId})
                assertTrue(s.connectorDiagnostics(d).all{it.individualLoadN==null})
                assertEquals(s.network.splits[1],s.network.physicalJoints.single{it.joint.details.typeX!=null}.node)
            }
        }
    }
    @Test fun schemaSixAndGenericDeclarationsRemainLosslessAcrossOrdinaryEdits() {
        val d=rig();val id=StructuralConnections.labeled(d).single{it.label=="C1"}.joint.id
        val seam=TypeXSeam(SeamState.broken,"Assembly","Original source",12000.0,true)
        val custom=ConnectorLibrary.custom("Preserved transverse","User",ConnectorCategory.sewn_transverse,null,null,"MBS","Pair and seam","Original source","Unknown")
        val original=StructuralConnections.declareCrossLine(install(d,listOf(ConnectorLibrary.item(custom,"original").copy(configuration="Preserve config",attachmentPorts=d.connections!!.joints.single{it.id==id}.ends.take(1))),seam),id)
        val encoded=RigLibraryCodec.encode(RigLibraryDocument(rigs=listOf(SavedRig("fixture","Legacy",1,RigConfiguration.from(original)))))
        val loaded=RigLibraryCodec.decode(encoded).rigs.single().configuration.applyTo(original)
        assertEquals(original,loaded)
        val before=loaded.connections!!.joints.single{it.id==id}
        val added=ConnectorLibrary.item(spec("bc_4mm_soft_double_wrap"),"new-main").copy(role=HardwareRole.main)
        val edited=loaded.copy(connections=loaded.connections.copy(joints=loaded.connections.joints.map{if(it.id==id)it.copy(details=it.details.copy(hardware=it.details.hardware+added)) else it}))
        val after=StructuralConnections.reconcile(edited).joints.single{it.id==id}
        assertEquals(before.details.typeX,after.details.typeX);assertEquals(before.declaredCrossLine,after.declaredCrossLine)
        assertEquals(before.kind,after.kind);assertEquals(before.details.hardware,after.details.hardware.dropLast(1))
        val exported=RigLibraryCodec.encode(RigLibraryDocument(rigs=listOf(SavedRig("fixture","Legacy",1,RigConfiguration.from(edited)))))
        assertEquals(edited,RigLibraryCodec.decode(exported).rigs.single().configuration.applyTo(edited))
    }
    @Test fun catalogueUsesOnlySupportedVerifiedRecordsAndCorrectSourceTerms() {
        val j=StructuralConnections.labeled(rig()).single{it.label=="C1"}.joint
        val inactive=ConnectorLibrary.custom("Legacy TypeX","User",ConnectorCategory.sewn_transverse,null,12000.0,"MBS","Exact assembly","Source","Unverified")
        val custom=ConnectorLibrary.custom("My connector","User",ConnectorCategory.soft_shackle,.02,8000.0,"MBS","Single wrap","User declaration","Unverified")
        val choices=ConnectorLibrary.choices(j.copy(declaredCrossLine=true),listOf(inactive,custom))
        assertFalse(choices.any{it.category in listOf(ConnectorCategory.sewn_transverse,ConnectorCategory.accessory,ConnectorCategory.rigid_anchor_shackle)})
        assertTrue(choices.contains(custom));assertEquals(ConnectorGroup.custom,connectorGroup(custom))
        assertEquals(listOf("Quicklinks","Soft shackles","Custom"),ConnectorGroup.entries.map{it.title})
        val en=spec("peguet_mrni08_0_en")
        assertEquals("Quicklink 8 mm inox · BL 55 kN",connectorListLabel(en))
        assertEquals("Quicklink 8 mm inox · BL 53.937 kN",connectorListLabel(spec("peguet_mrni08.0")))
        assertTrue(connectorListLabel(spec("bc_4mm_soft_double_wrap")).contains("double wrap · MBS 55 kN"))
        assertTrue(connectorListLabel(spec("bc_4mm_soft_single_wrap")).contains("single wrap · MBS 32 kN"))
        assertTrue(connectorMatches(en,"inox"));assertTrue(connectorMatches(en,"Peguet"));assertTrue(connectorMatches(en,en.article!!))
        for(s in choices) { assertFalse(connectorListLabel(s).contains(s.revision));assertFalse(connectorListLabel(s).contains("major_axis"));assertFalse(ConnectorLibrary.shipped.review.any{it.id==s.id}) }
    }
    @Test fun overviewIsConciseAnchorsAreNotSelectableAndEmptyGroupsAreUnresolved() {
        val d=rig();val labels=StructuralConnections.labeled(d)
        val j=labels.single{it.label=="C1"}
        assertEquals("C1 · Section 1 ↔ Section 2",connectionOverviewLabel(d,j))
        assertEquals(listOf(HardwareRole.main,HardwareRole.backup),unresolvedConnectorGroups(j.joint))
        val one=j.joint.copy(details=JointDetails(hardware=listOf(ConnectorLibrary.item(spec("bc_4mm_soft_single_wrap")).copy(role=HardwareRole.main))))
        assertEquals(listOf(HardwareRole.backup),unresolvedConnectorGroups(one))
        val both=one.copy(details=one.details.copy(hardware=one.details.hardware+ConnectorLibrary.item(spec("peguet_mrnz06.0")).copy(role=HardwareRole.backup)))
        assertTrue(unresolvedConnectorGroups(both).isEmpty())
        assertTrue(labels.filter{it.joint.kind==JointKind.anchor}.all{ConnectorLibrary.choices(it.joint,emptyList()).isEmpty()})
        val run=DiagnosticRunner.run(install(d,emptyList()),materials);assertNull(run.failure)
        val anchor=run.result!!.standing.network.physicalJoints.first{it.joint.kind==JointKind.anchor}
        val hardware=ConnectorLibrary.item(spec("bc_4mm_soft_single_wrap"),"anchor")
        val altered=run.result.standing.copy(network=run.result.standing.network.copy(physicalJoints=listOf(anchor.copy(joint=anchor.joint.copy(details=JointDetails(hardware=listOf(hardware)))))))
        assertTrue(altered.connectorDiagnostics(d.copy(modelConnections=true,connections=null)).isEmpty())
    }
    @Test fun archivedEditorSnapshotsRemainOutsideAndroidSourceSets() {
        val archive=File(root,"deferred/typex/ui-refinement-2026-10-15")
        assertTrue(File(archive,"ConnectionEditor.kt.snapshot").readText().contains("connection-typex"))
        assertTrue(File(archive,"ConnectorFields.kt.snapshot").readText().contains("Transverse sewing"))
        val ui=File(root,"app/src/main/java/org/linesolver/base/ui")
        val text=ui.walkTopDown().filter{it.extension=="kt"}.joinToString("\n"){it.readText()}
        assertFalse(text.contains("connection-typex"));assertFalse(text.contains("seam-published-bound"))
        assertFalse(text.contains("Continuous webbing"));assertFalse(text.contains("Text(\"TypeX\")"))
        assertTrue(File(ui,"BaseScreen.kt").readText().contains("\"Simple\" else \"Dynamics\""))
    }
}

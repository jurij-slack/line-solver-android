package org.linesolver.base

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*
import org.linesolver.base.model.*
import org.linesolver.base.energy.*
import org.linesolver.base.material.*
import org.linesolver.base.material.catalogue.*
import org.linesolver.base.solver.*
import org.linesolver.base.diagnostics.*
import org.linesolver.base.ui.resetWorkingRig
import java.io.File

class StructuralConnectionsTest {
    private val root=File(requireNotNull(System.getProperty("catalogueProjectRoot")))
    private val legacy=JsonMaterialRepository(File(root,"data/materials/webbing_material_models_v1.json").readText())
    private val shipped=CatalogueMaterialRepository(legacy,File(root,"catalogue/webbing-catalogue-v1.json").readText())
    private val selection=shipped.webbings("RAED").first{it.product=="Parsec"}.let{Selection(it.manufacturer,it.id)}
    private fun rig(n:Int=2)=Draft(kind=SetupKind.MIXED,span="20",leftHeight="0",rightHeight="0",mass="80",position="5",pretensionKn="1",elements="2",
        sections=List(n){SectionDraft((20.0/n).toString(),selection,selection,"50",id="s${it+1}")})
    private fun normalized(d:Draft)=d.copy(connections=StructuralConnections.reconcile(d))
    private fun numbered(d:Draft)=StructuralConnections.labeled(d).filter{it.joint.kind!=JointKind.anchor}
    private fun cord(id:String)=InstalledBungee(id,ModelReference("fixture","1","fixture"),2.0,source="Synthetic fixture")
    private fun bank(id:String="bank",placement:BungeePlacement=BungeePlacement.series_before,section:String?="s1",cable:Cable=Cable.MAIN,count:Int=1)=
        BungeeBank(id,placement,section,cable,List(count){cord("$id-$it")})
    private fun annotate(d:Draft,id:String)=d.copy(connections=d.connections!!.copy(joints=d.connections.joints.map{if(it.id==id)it.copy(details=JointDetails("keep this",listOf(HardwareItem("item-1"),HardwareItem("item-2")) )) else it}))
    @Test fun chainInsertionRemovalRenumbersButRetainsUnchangedIdentityAndMultiplicity() {
        val plain=normalized(rig());val original=numbered(plain).single();assertEquals("C1",original.label)
        val annotated=annotate(plain,original.joint.id)
        val inserted=normalized(annotated.copy(bungees=BungeeLayout(banks=listOf(bank()))))
        assertEquals(listOf("C1","C2"),numbered(inserted).map{it.label})
        assertEquals(original.joint.id,numbered(inserted)[1].joint.id)
        assertEquals(2,numbered(inserted)[1].joint.details.hardware.size)
        assertTrue(numbered(inserted)[0].joint.ends.any{it.instanceId=="bank-0"})
        val removed=normalized(inserted.copy(bungees=null));assertEquals(numbered(annotated),numbered(removed))
    }
    @Test fun sectionInsertionDeletionAndReorderNeverTransfersAnnotations() {
        val plain=normalized(rig(4));val retained=numbered(plain).last().joint
        val annotated=annotate(plain,retained.id)
        val inserted=normalized(annotated.copy(sections=listOf(plain.sections[0],plain.sections[0].copy(id="new"))+plain.sections.drop(1)))
        assertEquals(retained.id,numbered(inserted).last().joint.id)
        assertEquals(retained.details.copy(notes="keep this",hardware=listOf(HardwareItem("item-1"),HardwareItem("item-2"))),numbered(inserted).last().joint.details)
        val reordered=normalized(inserted.copy(sections=inserted.sections.take(3).reversed()+inserted.sections.drop(3)))
        assertEquals(retained.id,numbered(reordered).last().joint.id)
        val removed=normalized(reordered.copy(sections=reordered.sections.filterNot{it.id=="s3"}))
        assertTrue(removed.connections!!.detached.any{it.id==retained.id})
        assertTrue(numbered(removed).all{it.joint.details.hardware.isEmpty()})
    }
    @Test fun recreatedInterfaceGetsFreshIdAndOldSetupRemainsDetached() {
        val plain=normalized(rig());val old=numbered(plain).single().joint.id
        val first=annotate(plain,old)
        val replaced=normalized(first.copy(sections=first.sections.mapIndexed{i,s->if(i==0)s.copy(id="replacement") else s}))
        val restored=normalized(replaced.copy(sections=first.sections))
        assertNotEquals(old,numbered(restored).single().joint.id)
        assertEquals(old,restored.connections!!.detached.single().id)
    }
    @Test fun parallelSiblingsAndFoldsShareJunctionsWithoutDuplicates() {
        val d=normalized(rig().copy(bungees=BungeeLayout(banks=listOf(bank(count=2)))))
        assertEquals(2,numbered(d).size)
        assertEquals(2,d.connections!!.joints.count{j->j.ends.any{it.instanceId=="bank-0"}})
        assertTrue(d.connections.joints.filter{j->j.ends.any{it.instanceId=="bank-0"}}.all{j->j.ends.any{it.instanceId=="bank-1"}})
        val expanded=normalized(d.copy(bungees=BungeeLayout(banks=listOf(bank(count=3).copy(assemblies=bank(count=3).assemblies.map{it.copy(equalLegs=4)})))))
        assertEquals(d.connections.joints.map{it.id},expanded.connections!!.joints.map{it.id})
        assertEquals(StructuralConnections.labeled(expanded),StructuralConnections.labeled(expanded.copy(elements="32")))
    }
    @Test fun removalOfAssignedPortDetachesRatherThanReassigningHardware() {
        val d=normalized(rig().copy(bungees=BungeeLayout(banks=listOf(bank(count=2)))))
        val joint=numbered(d).first().joint
        val end=joint.ends.first{it.instanceId=="bank-1"}
        val annotated=d.copy(connections=d.connections!!.copy(joints=d.connections.joints.map{if(it.id==joint.id)it.copy(details=JointDetails(hardware=listOf(HardwareItem("assigned",attachmentPorts=listOf(end))))) else it}))
        val removed=normalized(annotated.copy(bungees=BungeeLayout(banks=listOf(bank()))))
        assertEquals(joint.id,removed.connections!!.detached.single().id)
        assertTrue(StructuralConnections.errors(removed).isEmpty())
        assertTrue(removed.connections.joints.all{it.details.hardware.isEmpty()})
    }
    @Test fun hundredSectionsHaveNinetyNinePhysicalInterfacesAtAnyMeshOrWalkerPosition() {
        val d=normalized(rig(100));assertEquals(99,numbered(d).size)
        for(mesh in listOf("2","8","32")) {
            val changed=d.copy(elements=mesh,position="7.123")
            assertEquals(numbered(d),numbered(changed))
            val setup=changed.validate(shipped).setup!!
            val n=buildNetwork(setup,shipped,setup.sections.map{it.preparedLengthM},true)
            assertEquals(101,n.physicalJoints.size)
            assertEquals(d.connections!!.joints.map{it.id},n.physicalJoints.map{it.joint.id})
            assertEquals(2*100*mesh.toInt(),n.elements.size)
        }
    }
    @Test fun independentCableBranchesHaveDeterministicInstalledOrder() {
        val d=normalized(rig().copy(bungees=BungeeLayout(banks=listOf(bank("backup",cable=Cable.BACKUP),bank("main"),bank("right",BungeePlacement.series_right,null)))))
        assertEquals(listOf("C1","C2","C3","C4"),numbered(d).map{it.label})
        assertTrue(numbered(d).first().joint.ends.any{it.instanceId=="main-0"})
        assertTrue(numbered(d).last().joint.ends.any{it.instanceId=="backup-0"})
        assertEquals(d.connections,StructuralConnections.reconcile(d))
    }
    @Test fun legacySchemasMigrateWithoutChangedInputOrNewConstraints() {
        for(version in 1..3) {
            val original=RigLibraryDocument(version,listOf(SavedRig("rig","Legacy",1,RigConfiguration.from(rig()))))
            val loaded=RigLibraryCodec.decode(RigLibraryCodec.encode(original)).rigs.single().configuration
            assertFalse(loaded.modelConnections);assertNull(loaded.connections)
            val d=loaded.applyTo(rig());val migrated=normalized(d)
            val before=DiagnosticRunner.run(d,shipped);val after=DiagnosticRunner.run(migrated,shipped)
            assertNull(before.failure);assertNull(after.failure)
            assertEquals(before.report.document["states"],after.report.document["states"])
            assertEquals(before.report.document["energy_ledger"],after.report.document["energy_ledger"])
            assertEquals(before.result!!.installedLengths,after.result!!.installedLengths)
        }
    }
    @Test fun settingSnapshotsSlotsAndCrossLineRoundTripAndConversion() {
        val generated=rig().copy(kind=SetupKind.UNIFORM,sections=emptyList(),uniform=UniformGenerator(count="2",main=selection,backup=selection,extra="50",instanceIds=listOf("s1","s2")))
        var d=normalized(generated).copy(modelConnections=true)
        val id=numbered(d).single().joint.id;d=StructuralConnections.declareCrossLine(annotate(d,id),id)
        val serialized=RigLibraryCodec.encode(RigLibraryDocument(rigs=listOf(SavedRig("rig","Fixture",1,RigConfiguration.from(d),RigConfiguration.from(d).fingerprints(shipped)))))
        assertTrue(serialized.contains("\"schemaVersion\":4"))
        val loaded=RigLibraryCodec.decode(serialized).rigs.single().configuration.applyTo(d)
        assertEquals(d,loaded)
        val mixed=normalized(loaded.toMixed());assertEquals(d.connections,mixed.connections)
        val back=normalized(mixed.withUniform(mixed.uniformProposal()));assertEquals(d.connections,back.connections)
        assertEquals(d.connections,normalized(back.copy(modelConnections=false)).connections)
        assertTrue(resetWorkingRig(d).modelConnections)
    }
    @Test fun additiveLibraryInterchangeIsAtomicAndKeepsExactJointAndModelIdentity() {
        val d=normalized(rig()).copy(modelConnections=true)
        val id=numbered(d).single().joint.id
        val configuration=RigConfiguration.from(annotate(d,id))
        var firstDisk:String?=null;val first=RigLibraryStore({firstDisk},{firstDisk=it},now={1},newId={"first"})
        val entry=first.save("Fixture",configuration,configuration.fingerprints(shipped))
        var secondDisk:String?=null;val second=RigLibraryStore({secondDisk},{secondDisk=it},now={2},newId={"second"})
        assertEquals(1,second.importJson(first.exportJson()));assertEquals(entry,second.get("first"))
        val original=secondDisk;assertEquals(0,second.importJson(first.exportJson()));assertEquals(original,secondDisk)
        val conflict=RigLibraryCodec.encode(first.document.copy(rigs=listOf(entry.copy(configuration=configuration.copy(span="25")))))
        assertThrows(IllegalArgumentException::class.java){second.importJson(conflict)};assertEquals(original,secondDisk)
        assertEquals(entry,RigLibraryStore({secondDisk},{error("No read-time writes")}).get("first"))
        assertThrows(IllegalArgumentException::class.java){second.importJson(first.exportJson().replace("\"schemaVersion\":4","\"schemaVersion\":7"))}
    }
    @Test fun explicitTogglePreferenceSurvivesFreshRestoreWithoutOverridingSavedRigChoice() {
        val data=mutableMapOf<String,String>()
        fun prefs()=AppPreferences(data::get){k,v->data[k]=v}
        val saved=normalized(rig()).copy(modelConnections=true)
        prefs().save(saved);assertTrue(prefs().restore(Draft()).modelConnections)
        assertFalse(prefs().restore(saved.copy(modelConnections=false)).modelConnections)
        assertEquals(saved.connections,prefs().restore(saved).connections)
        assertFalse(ModelPreferences().modelConnections)
    }
    @Test fun duplicateForeignPortsInvalidKindsAndFutureSchemasFailClearly() {
        val d=normalized(rig());val layout=d.connections!!;val joint=numbered(d).single().joint
        val invalid=listOf(layout.copy(schemaVersion=2),layout.copy(joints=layout.joints+joint),
            layout.copy(joints=layout.joints.map{if(it.id==joint.id)it.copy(ends=it.ends+it.ends.first()) else it}),
            layout.copy(joints=layout.joints.map{if(it.id==joint.id)it.copy(ends=it.ends.map{e->e.copy(instanceId="missing")}) else it}),
            layout.copy(joints=layout.joints.map{if(it.id==joint.id)it.copy(kind=JointKind.explicit_cross_line) else it}),
            layout.copy(joints=layout.joints.dropLast(1)))
        for(bad in invalid) {
            assertTrue(StructuralConnections.errors(d.copy(connections=bad)).isNotEmpty())
            assertThrows(IllegalArgumentException::class.java){RigLibraryCodec.encode(RigLibraryDocument(rigs=listOf(SavedRig("r","Bad",1,RigConfiguration.from(d.copy(connections=bad))))))}
        }
    }
    @Test fun unknownHardwareReferencesAreNotSubstitutedOrUsedEvenWhenDisabled() {
        val d=normalized(rig());val joint=numbered(d).single().joint
        val bad=d.copy(connections=d.connections!!.copy(joints=d.connections.joints.map{if(it.id==joint.id)it.copy(details=JointDetails(hardware=listOf(HardwareItem("hw","missing","revision")))) else it}))
        assertTrue(StructuralConnections.errors(bad).any{it.contains("unavailable")});assertNull(bad.validate(shipped).setup)
    }
    @Test fun declaredSharedSplitIsStructuralWhenOffAndArbitraryInternalSeamsAreRejected() {
        val d=normalized(rig());val id=numbered(d).single().joint.id
        val declared=StructuralConnections.declareCrossLine(d,id).copy(modelConnections=false)
        val setup=declared.validate(shipped).setup!!;val n=buildNetwork(setup,shipped,setup.sections.map{it.preparedLengthM},true)
        val joint=n.physicalJoints.single{it.joint.id==id};assertEquals(n.splits[1],joint.node)
        assertEquals(4,joint.joint.ends.size);assertEquals(JointKind.explicit_cross_line,joint.joint.kind)
        assertFalse(n.physicalJoints.any{it.node==n.walker})
        assertNotEquals(n.attachment!!.mainNode,n.attachment.backupNode)
        assertThrows(IllegalArgumentException::class.java){StructuralConnections.declareCrossLine(d,d.connections!!.joints.first{it.kind==JointKind.anchor}.id)}
        val seam=joint.joint.copy(ends=listOf(ComponentEnd("s1",Cable.MAIN,EndPort.start),ComponentEnd("s2",Cable.BACKUP,EndPort.end)))
        assertThrows(IllegalArgumentException::class.java){StructuralConnections.node(seam,d,n)}
    }
    private fun bungeeRepository():Pair<CatalogueMaterialRepository,ModelReference> {
        var disk:String?=null;val store=MaterialOverlayStore(shipped.document,{disk},{disk=it})
        MaterialEditing.save(store,MaterialEdit(name="Synthetic connection regression cord",brand="Fixture",category=ProductCategory.bungee,
            bungee=BungeeLaw(basis=BungeeBasis.physical_cord,testedPhysicalLengthM=2.0,source="Synthetic analytical law",idealEqualLegs=true,scalablePerLength=true,scalingEvidence="Synthetic per-length assumption"),
            source="Synthetic fixture only",mass="0.5",table="T/N,epsilon/%\n5000,100\n10000,200",forceUnit="N"))
        return CatalogueMaterialRepository(legacy,CatalogueCodec.encode(MaterialPackages.combine(shipped.document,store.value))) to store.value.catalogue.models.single().reference()
    }
    @Test fun phaseFourBungeeStaticsStopInventoryEnergyAndReactionsAreExactlyUnchangedByToggle() {
        val (repo,model)=bungeeRepository()
        for(placement in BungeePlacement.entries) for(count in listOf(1,2)) {
            val rest=if(placement==BungeePlacement.parallel_section)8.0 else 2.0
            val b=bank(placement=placement,section=if(placement==BungeePlacement.series_right)null else "s2",count=count)
            val original=rig().copy(bungees=BungeeLayout(banks=listOf(b.copy(assemblies=b.assemblies.map{it.copy(model=model,physicalRestM=rest,limiterPhysicalLengthM=rest*1.15)}))))
            val raw=DiagnosticRunner.run(original,repo);assertNull(raw.failure)
            val off=normalized(original)
            val on=off.copy(modelConnections=true)
            for(d in listOf(off,on,normalized(on.copy(modelConnections=false)))) {
                val run=DiagnosticRunner.run(d,repo);assertNull(run.failure)
                assertEquals(raw.report.document["states"],run.report.document["states"])
                assertEquals(raw.report.document["energy_ledger"],run.report.document["energy_ledger"])
                assertEquals(raw.result!!.installedLengths,run.result!!.installedLengths)
                assertArrayEquals(raw.result.standing.totalGradient(),run.result.standing.totalGradient(),0.0)
                assertEquals(raw.result.standing.bungeeResults(),run.result.standing.bungeeResults())
                assertEquals(StructuralConnections.labeled(d).size,run.result.standing.network.physicalJoints.size)
                if(placement==BungeePlacement.series_right) {
                    val termination=run.result.standing.network.physicalJoints.single{it.joint.kind==JointKind.weblock_termination}
                    assertNotEquals(run.result.standing.network.splits.last(),termination.node)
                    assertEquals(count+1,termination.joint.ends.size)
                }
            }
        }
    }
    @Test fun standingCatchRestAndRingContactRemainExactlyUnchangedWithInertMultipleSlots() {
        val original=rig().copy(position="6",cutEnabled=true,cutPosition="16",walkerHeight="1.8",leash="1.5")
        val normalized=normalized(original);val id=numbered(normalized).single().joint.id
        val annotated=StructuralConnections.declareCrossLine(annotate(normalized,id),id)
        val before=DiagnosticRunner.run(original,shipped);assertNull(before.failure);assertNotNull(before.result!!.rest)
        for(d in listOf(annotated,annotated.copy(modelConnections=true),annotated.copy(elements="2",modelConnections=false))) {
            val after=DiagnosticRunner.run(d,shipped);assertNull(after.failure)
            assertEquals(before.report.document["states"],after.report.document["states"])
            assertEquals(before.report.document["energy_ledger"],after.report.document["energy_ledger"])
            assertEquals(RigConfiguration.from(original).fingerprints(shipped),RigConfiguration.from(d).fingerprints(shipped))
        }
    }
}

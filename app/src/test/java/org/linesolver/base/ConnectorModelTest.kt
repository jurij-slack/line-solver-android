package org.linesolver.base

import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.model.*
import org.linesolver.base.energy.*
import org.linesolver.base.material.*
import org.linesolver.base.material.catalogue.*
import org.linesolver.base.solver.*
import org.linesolver.base.diagnostics.*
import org.linesolver.base.ui.resetWorkingRig
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

class ConnectorModelTest {
    private val root=File(requireNotNull(System.getProperty("catalogueProjectRoot")))
    private val legacy=JsonMaterialRepository(File(root,"data/materials/webbing_material_models_v1.json").readText())
    private val shipped=CatalogueMaterialRepository(legacy,File(root,"catalogue/webbing-catalogue-v1.json").readText())
    private val selection=shipped.webbings("RAED").first{it.product=="Parsec"}.let{Selection(it.manufacturer,it.id)}
    private fun rig()=Draft(kind=SetupKind.MIXED,span="20",leftHeight="0",rightHeight="0",mass="80",position="5",pretensionKn="1",elements="2",
        sections=listOf(SectionDraft("10",selection,selection,"50",id="s1"),SectionDraft("10",selection,selection,"50",id="s2")))
    private fun normalize(d:Draft)=d.copy(connections=StructuralConnections.reconcile(d))
    private fun spec(id:String)=ConnectorLibrary.shipped.products.single{it.id==id}
    private fun installed(items:List<HardwareItem>,enabled:Boolean=false):Draft {
        val d=normalize(rig());val joint=StructuralConnections.labeled(d).single{it.label=="C1"}.joint.id
        return d.copy(modelConnections=enabled,connections=d.connections!!.copy(joints=d.connections.joints.map{if(it.id==joint)it.copy(details=JointDetails(hardware=items)) else it}))
    }
    private fun network(d:Draft,repo:MaterialRepository=shipped):Network {
        val validation=d.validate(repo);assertEquals(validation.errors.toString(),emptyList<String>(),validation.errors)
        val setup=validation.setup!!;return buildNetwork(setup,repo,setup.sections.map{it.preparedLengthM},true)
    }
    @Test fun researchIdentityTermsConversionsAndReviewArePreserved() {
        val research=File(root,"data/materials/phase6_connector_research/connector_specs_research.json").readBytes()
        val hash=MessageDigest.getInstance("SHA-256").digest(research).joinToString(""){"%02x".format(it)}
        assertEquals(hash,ConnectorLibrary.shipped.researchHash)
        assertEquals(18,ConnectorLibrary.shipped.products.size)
        assertEquals(13,ConnectorLibrary.shipped.review.size)
        for(d in listOf("06.0","07.0","08.0"))for(prefix in listOf("peguet_mrnz","peguet_mrni")) {
            val s=spec(prefix+d);assertNull(s.mbsN);assertNotNull(s.blN);assertNotNull(s.wllN);assertTrue(s.originalUnits.contains("kgf"));assertNotNull(s.article)
        }
        assertEquals(2000*9.80665,spec("peguet_mrnz06.0").blN!!,1e-12)
        assertNull(spec("peguet_mrni07_0_en").wllN)
        val steel=spec("slackinov_lqci8");assertEquals(38000.0,steel.mbsN!!,0.0);assertEquals(25000.0,steel.certifiedClaimN!!,0.0);assertEquals("LQCI8",steel.article)
        val seam=ConnectorLibrary.shipped.review.single{it.id=="slacktivity_typex_pinktube_lsd"}
        assertTrue(seam.candidateJson.contains("minimum_breaking_strength_lower_bound_N"));assertFalse(ConnectorLibrary.shipped.products.any{it.category==ConnectorCategory.sewn_transverse})
        assertTrue(ConnectorLibrary.shipped.review.any{it.id=="bc_segment_2x5mm"})
        assertEquals(spec("slackinov_hmpe_3mm_single").massKg,spec("slackinov_hmpe_3mm_double").massKg)
    }
    @Test fun sharedNodeAndPortForcesAreIndependentOfAllAnnotationsAndVisibility() {
        val base=installed(listOf(ConnectorLibrary.item(spec("bc_4mm_soft_single_wrap"),"backup").copy(role=HardwareRole.backup)))
        for(status in HardwareLoad.entries)for(enabled in listOf(false,true))for(visible in listOf(false,true)) {
            val d=base.copy(modelConnections=enabled,showBungeeOptions=visible,connections=base.connections!!.copy(joints=base.connections.joints.map{j->j.copy(details=j.details.copy(hardware=j.details.hardware.map{it.copy(standingLoad=LoadAssumption(status),failedLoad=LoadAssumption(HardwareLoad.unknown))}))}))
            val n=network(d);val joint=n.physicalJoints.single{it.joint.details.hardware.isNotEmpty()}
            assertEquals(4,joint.joint.ends.size);assertEquals(n.splits[1],joint.node)
            joint.joint.ends.forEach{end->assertEquals(joint.node,StructuralConnections.node(StructuralJoint("two",listOf(end,end.copy(cable=if(end.cable==Cable.MAIN)Cable.BACKUP else Cable.MAIN)),JointKind.branch),d,n))}
            val comparison=network(base.copy(modelConnections=enabled)).evaluate(n.coordinates)
            assertArrayEquals(comparison.tensions,n.evaluate(n.coordinates).tensions,0.0)
            assertEquals(if(enabled)1 else 0,n.connectorMasses.size)
        }
    }
    @Test fun singleDoubleWrapTwoPiecesAndExactKitMassNeverDuplicateLegsOrCapacity() {
        val one=spec("bc_4mm_soft_double_wrap")
        val single=network(installed(listOf(ConnectorLibrary.item(one,"one")),true))
        assertEquals(.0128,single.connectorMasses.sumOf{it.massKg},0.0)
        val two=network(installed(listOf(ConnectorLibrary.item(one,"one"),ConnectorLibrary.item(one,"two")),true))
        assertEquals(.0256,two.connectorMasses.sumOf{it.massKg},0.0)
        val kit=network(installed(listOf(ConnectorLibrary.item(one,"one"),ConnectorLibrary.item(one,"two"),ConnectorLibrary.item(spec("bc_2x4_protection_pack"),"pack")),true))
        assertEquals(.036,kit.connectorMasses.sumOf{it.massKg},1e-15);assertEquals(3,kit.connectorMasses.size)
        assertNull(spec("bc_2x4_protection_pack").mbsN)
        val invalid=installed(listOf(ConnectorLibrary.item(spec("bc_2x4_protection_pack"))),true)
        assertTrue(StructuralConnections.errors(invalid).isNotEmpty())
    }
    @Test fun concentratedMassPotentialGradientAndGlobalGravityBalanceAreAnalytical() {
        val mass=listOf(ConcentratedMass("a",1,3.0),ConcentratedMass("b",1,4.0))
        val n=Network(doubleArrayOf(0.0,0.0,0.0,1.2,0.0,3.0),listOf(
            Element(0,1,1.0,0.0,LinearMaterial(100.0,0.0),0,Cable.MAIN,0.0,1.0),
            Element(1,2,1.0,0.0,LinearMaterial(200.0,0.0),1,Cable.MAIN,0.0,1.0)),listOf(0,2),null,0.0,2.0,3.0,connectorMasses=mass)
        val s=Equilibrium().solve(n,fixedExtra=mapOf(2 to 0.0))
        assertEquals(1.62,s.r[3],1e-9);assertEquals(62.0,s.value.tensions[0],1e-7);assertEquals(76.0,s.value.tensions[1],1e-7)
        assertEquals(14*s.r[3],s.value.connectorGravitational,1e-12)
        assertEquals(14.0,s.totalGradient()[1]+s.totalGradient()[5],1e-6)
        val h=1e-6;val a=s.r.copyOf();val b=s.r.copyOf();a[3]+=h;b[3]-=h
        val derivative=(n.evaluate(a).total-n.evaluate(b).total)/(2*h)
        assertEquals(s.value.gradient[3],derivative,1e-7)
        val noMass=n.copy(connectorMasses=emptyList()).evaluate(s.r)
        assertArrayEquals(noMass.tensions,s.value.tensions,0.0);assertEquals(noMass.total+14*s.r[3],s.value.total,1e-12)
    }
    @Test fun massTogglingChangesOnlyExplicitMassPhysicsAndOffMatchesLegacyExactly() {
        val d=installed(listOf(ConnectorLibrary.item(spec("peguet_mrnz06.0"),"maillon")))
        val bare=DiagnosticRunner.run(rig(),shipped);val off=DiagnosticRunner.run(d,shipped)
        assertNull(bare.failure);assertNull(off.failure);assertEquals(bare.report.document["states"],off.report.document["states"])
        val on=DiagnosticRunner.run(d.copy(modelConnections=true),shipped);assertNull(on.failure)
        assertEquals(.035,on.result!!.standing.network.connectorMasses.single().massKg,0.0)
        assertNotEquals(off.result!!.standing.value.total,on.result.standing.value.total)
        val hidden=DiagnosticRunner.run(d.copy(modelConnections=true,showBungeeOptions=true),shipped)
        assertEquals(on.report.document["states"],hidden.report.document["states"])
        assertEquals(d.connections,d.copy(modelConnections=true).connections)
        val after=DiagnosticRunner.run(d.copy(modelConnections=false),shipped);assertEquals(off.report.document["states"],after.report.document["states"])
    }
    @Test fun failedStateMassAndConnectorEnergyRemainInCatchAndRestWithoutBreakingCommonSplits() {
        val d=installed(listOf(ConnectorLibrary.item(spec("bc_4mm_soft_single_wrap"),"main").copy(role=HardwareRole.main,standingLoad=LoadAssumption(HardwareLoad.loaded),failedLoad=LoadAssumption(HardwareLoad.unknown)),
            ConnectorLibrary.item(spec("bc_4mm_soft_single_wrap"),"backup").copy(role=HardwareRole.backup,standingLoad=LoadAssumption(HardwareLoad.unloaded),failedLoad=LoadAssumption(HardwareLoad.loaded))),true)
            .copy(position="6",cutEnabled=true,cutPosition="16",walkerHeight="1.8",leash="1.5")
        val run=DiagnosticRunner.run(d,shipped);assertNull(run.failure);val result=run.result!!
        for(s in listOf(result.standing,result.catch!!,result.rest!!)) {
            assertEquals(.0256,s.network.connectorMasses.sumOf{it.massKg},0.0)
            assertEquals(s.network.splits[1],s.network.physicalJoints.single{it.joint.details.hardware.isNotEmpty()}.node)
            assertEquals(s.value.elastic+s.value.webbingGravitational+s.value.walkerGravitational+s.value.connectorGravitational,s.value.total,1e-8)
        }
        val ledger=result.fallEnergy!!;assertEquals(result.standing.value.total,ledger.initial.totalJ,1e-8);assertEquals(result.catch.value.total,ledger.turning.totalJ,1e-8)
        assertTrue(abs(ledger.residualJ)<1e-3)
        assertEquals(HardwareLoad.unloaded,result.standing.connectorDiagnostics(d).single{it.itemId=="backup"}.assumedLoad.status)
        assertEquals(HardwareLoad.loaded,result.catch.connectorDiagnostics(d).single{it.itemId=="backup"}.assumedLoad.status)
        assertTrue(result.standing.connectorDiagnostics(d).all{it.individualLoadN==null})
    }
    @Test fun loadedUnloadedUnknownAndInconsistencyNeverInventIndividualSharing() {
        val d=installed(listOf(ConnectorLibrary.item(spec("bc_4mm_soft_single_wrap"),"backup").copy(role=HardwareRole.backup,standingLoad=LoadAssumption(HardwareLoad.unloaded)),
            ConnectorLibrary.item(spec("bc_4mm_soft_double_wrap"),"other").copy(role=HardwareRole.shared)),true)
        val n=network(d);val state=Equilibrium().solve(n)
        assertTrue(state.connectorDiagnostics(d).all{it.individualLoadN==null})
        assertTrue(state.connectorDiagnostics(d).all{!it.comparison.contains("capacity 87")})
        // A fixed, deliberately stretched fixture provides appreciable BACKUP force without claiming solved sharing.
        val r=n.coordinates.copyOf();r[2*n.splits[1]+1]-=20.0
        val fixture=state.copy(r=r,value=n.evaluate(r,trial=true))
        assertTrue(fixture.connectorDiagnostics(d).single{it.itemId=="backup"}.warning.contains("appreciable"))
    }
    @Test fun qualifiedSingleAxialLimitUsesMbsInsteadOfCeAndDoesNotRenameBl() {
        val s=spec("slackinov_lqci8");val h=ConnectorLibrary.item(s,"only").copy(ratedInstallationConfirmed=true)
        val ends=listOf(ComponentEnd("s1",Cable.MAIN,EndPort.end),ComponentEnd("s2",Cable.MAIN,EndPort.start))
        val j=StructuralJoint("j",ends,JointKind.serial,JointDetails(hardware=listOf(h)))
        val n=Network(doubleArrayOf(-2.0,0.0,0.0,0.0,2.0,0.0),listOf(Element(0,1,1.0,0.0,LinearMaterial(30000.0,0.0),0,Cable.MAIN,0.0,1.0),Element(1,2,1.0,0.0,LinearMaterial(30000.0,0.0),1,Cable.MAIN,0.0,1.0)),listOf(0,2),null,0.0,0.0,4.0,physicalJoints=listOf(NetworkJoint(j,1)))
        val state=Equilibrium().solve(n);val d=rig().copy(connections=null)
        val diag=state.connectorDiagnostics(d.copy(modelConnections=true)).single()
        assertEquals(30000.0,diag.individualLoadN!!,1e-8);assertTrue(diag.comparison.contains("MBS 38.0"));assertFalse(diag.comparison.contains("25.0"))
        val bl=ConnectorLibrary.item(spec("peguet_mrnz06.0"),"bl").copy(ratedInstallationConfirmed=true)
        val stateBl=state.copy(network=n.copy(physicalJoints=listOf(NetworkJoint(j.copy(details=JointDetails(hardware=listOf(bl))),1))))
        assertTrue(stateBl.connectorDiagnostics(d.copy(modelConnections=true)).single().comparison.contains("BL (published breaking load; not statistical MBS)"))
        val off=state.connectorDiagnostics(d);assertTrue(off.isEmpty())
    }
    @Test fun unknownMassReferenceTamperingAndIllegalSewingFailWithoutTopologyRepair() {
        val custom=ConnectorLibrary.custom("Unknown mass","User",ConnectorCategory.soft_shackle,null,5000.0,"MBS","single wrap","User assumption","Unverified; axial only")
        val d=installed(listOf(ConnectorLibrary.item(custom)))
        assertNotNull(d.validate(shipped).setup);assertNull(d.copy(modelConnections=true).validate(shipped).setup)
        val h=ConnectorLibrary.item(spec("bc_4mm_soft_single_wrap"))
        assertThrows(IllegalArgumentException::class.java){ConnectorLibrary.resolve(h.copy(specification=h.specification!!.copy(mbsN=1.0)))}
        val seam=ConnectorLibrary.custom("Specific seam","User",ConnectorCategory.sewn_transverse,0.0,null,"MBS","specified webbing pair T-stitch","User declaration; thread mass included","Exact pair only; strength unknown")
        assertTrue(StructuralConnections.errors(installed(listOf(ConnectorLibrary.item(seam)))).any{it.contains("transverse")})
        val named=StructuralConnections.declareCrossLine(installed(listOf(ConnectorLibrary.item(seam))),StructuralConnections.labeled(installed(listOf(ConnectorLibrary.item(seam)))).single{it.label=="C1"}.joint.id)
        assertTrue(StructuralConnections.errors(named).isEmpty())
    }
    @Test fun immutableCustomRevisionStoreAndSchemaFiveRoundTripPreserveIndependentPieces() {
        var disk:String?=null;val store=CustomConnectorStore({disk},{disk=it})
        val a=ConnectorLibrary.custom("Custom fixture","User",ConnectorCategory.soft_shackle,.01,8000.0,"BL","user single wrap","User-declared fixture","Not verified")
        val b=ConnectorLibrary.custom(a.name,a.manufacturer,a.category,.02,9000.0,"MBS",a.ratedConfiguration,a.sourceUrl,a.restrictions,a.id)
        store.add(a);store.add(b);assertEquals(listOf(a,b),CustomConnectorStore({disk},{error("No read-time writes")}).value.products)
        val d=installed(listOf(ConnectorLibrary.item(a,"first"),ConnectorLibrary.item(b,"second").copy(role=HardwareRole.backup,failedLoad=LoadAssumption(HardwareLoad.loaded,"After cut","user assumption"))),true).copy(showBungeeOptions=true)
        val encoded=RigLibraryCodec.encode(RigLibraryDocument(rigs=listOf(SavedRig("fixture","Fixture",1,RigConfiguration.from(d)))))
        assertTrue(encoded.contains("\"schemaVersion\":5"));val loaded=RigLibraryCodec.decode(encoded).rigs.single().configuration.applyTo(d)
        assertEquals(d,loaded);assertTrue(resetWorkingRig(d).showBungeeOptions)
        assertThrows(IllegalArgumentException::class.java){RigLibraryCodec.decode(encoded.replace("\"schemaVersion\":5","\"schemaVersion\":4"))}
        val source=d.connections!!.joints.flatMap{it.details.hardware};assertEquals(listOf(.01,.02),source.map{ConnectorLibrary.resolve(it)!!.massKg})
        val bad=CustomConnectorStore({"corrupt"},{error("Must not overwrite")});assertNotNull(bad.readError)
        assertThrows(IllegalStateException::class.java){bad.add(a)}
    }
    @Test fun bungeeVisibilityPreferenceDoesNotChangeRigOwnershipOrDefaultPhysics() {
        assertFalse(Draft().showBungeeOptions)
        val d=normalize(rig()).copy(showBungeeOptions=true)
        val values=mutableMapOf<String,String>();val prefs=AppPreferences(values::get){k,v->values[k]=v};prefs.save(d)
        assertTrue(prefs.restore(Draft()).showBungeeOptions)
        assertFalse(prefs.restore(d.copy(showBungeeOptions=false)).showBungeeOptions)
        val generated=d.withUniform(UniformGenerator("2",selection,selection,"50",instanceIds=listOf("s1","s2")))
        assertEquals(generated.connections,normalize(generated.toMixed()).connections)
        assertTrue(generated.toMixed().showBungeeOptions)
        assertEquals(ModelPreferences(),ModelPreferences.from(ModelPreferences().applyTo(d)))
    }
    @Test fun foldedParallelBanksStayStoppedAndActiveWithEditingHidden() {
        var disk:String?=null;val store=MaterialOverlayStore(shipped.document,{disk},{disk=it})
        MaterialEditing.save(store,MaterialEdit(name="Synthetic cord fixture",brand="Fixture",category=ProductCategory.bungee,
            bungee=BungeeLaw(basis=BungeeBasis.physical_cord,testedPhysicalLengthM=2.0,source="Synthetic source",idealEqualLegs=true),source="Synthetic fixture only",mass="0.5",table="T/N,epsilon/%\n100,100\n300,200",forceUnit="N"))
        val repo=CatalogueMaterialRepository(legacy,CatalogueCodec.encode(MaterialPackages.combine(shipped.document,store.value)))
        val a=InstalledBungee("a",store.value.catalogue.models.single().reference(),2.0,2,limiterPhysicalLengthM=4.0,source="Synthetic fixture")
        for(count in listOf(1,2)) {
            var d=rig().copy(bungees=BungeeLayout(banks=listOf(BungeeBank("bank",BungeePlacement.series_right,assemblies=List(count){a.copy(id="a$it")}))))
            d=normalize(d)
            val hidden=network(d.copy(showBungeeOptions=false),repo);val visible=network(d.copy(showBungeeOptions=true),repo)
            assertEquals(count,hidden.bungeePaths.size)
            assertArrayEquals(visible.evaluate(visible.coordinates,trial=true).tensions,hidden.evaluate(hidden.coordinates,trial=true).tensions,0.0)
            assertEquals(visible.evaluate(visible.coordinates,trial=true).total,hidden.evaluate(hidden.coordinates,trial=true).total,0.0)
            assertEquals(visible.bungeePaths.map{it.limitM},hidden.bungeePaths.map{it.limitM})
            val stop=hidden.physicalJoints.single{it.joint.kind==JointKind.weblock_termination}
            assertEquals(count+1,stop.joint.ends.size);assertNotEquals(hidden.splits.last(),stop.node)
            assertEquals(visible.physicalJoints,hidden.physicalJoints)
        }
    }
}

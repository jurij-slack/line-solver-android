package org.linesolver.base

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.contact.ContactOptions
import org.linesolver.base.energy.*
import org.linesolver.base.material.JsonMaterialRepository
import org.linesolver.base.model.*
import org.linesolver.base.solver.*

class AttachmentEnergyTest {
    private val materials by lazy {
        JsonMaterialRepository(javaClass.classLoader!!.getResource("webbing_material_models_v1.json")!!.readText())
    }
    private fun setup(sections:Int=1,height:Double=1.8,leash:Double=1.5):LineSetup {
        val main=materials.webbings("RAED").first{it.product=="Parsec"}.id
        val backup=materials.webbings("RAED").first{it.product=="MOTM"}.id
        return LineSetup(Geometry(35.0*sections,0.0,0.0),List(sections){Section(35.0,main,backup,.05)},
            1500.0,Walker(80.0,12.0),Cut(26.0,height,leash),ContactOptions(),9.81,8)
    }

    @Test fun interiorLeashIsNotAStructuralConnectionAndBothWebbingsStayContinuous() {
        val rig=setup();val n=buildNetwork(rig,materials,listOf(35.0),true)
        val ring=n.walker!!
        assertFalse(ring in n.splits)
        assertEquals(2,n.splits.size) // anchors only, no new physical split
        for(cable in Cable.entries) {
            val node=if(cable==Cable.MAIN)n.attachment!!.mainNode else n.attachment!!.backupNode
            val incoming=n.elements.single{it.cable==cable && it.b==node}
            val outgoing=n.elements.single{it.cable==cable && it.a==node}
            assertEquals(incoming.section,outgoing.section)
            assertSame(incoming.material,outgoing.material)
            assertEquals(incoming.to,outgoing.from,0.0)
            assertEquals(if(cable==Cable.MAIN)35.0 else 36.75,n.elements.filter{it.cable==cable}.sumOf{it.rest},1e-12)
        }
    }

    @Test fun actualSectionConnectionRemainsASharedFreeStructuralNode() {
        val rig=setup(2);val n=buildNetwork(rig,materials,listOf(35.0,35.0),true)
        assertEquals(3,n.splits.size)
        val split=n.splits[1]
        assertNotEquals(n.walker,split)
        for(cable in Cable.entries) {
            assertEquals(0,n.elements.single{it.cable==cable && it.b==split}.section)
            assertEquals(1,n.elements.single{it.cable==cable && it.a==split}.section)
        }
        val state=Equilibrium().solve(n)
        assertTrue(state.residualN<2e-6)
        assertTrue(state.r[2*split+1]<0.0) // physical split is free to sag
    }

    @Test fun reportedStandingEnergyIsTheActualInitialCatchEnergy() {
        val result=SimpleSolver(materials).solve(setup())
        assertEquals("Standing state must include the same COM energy used to start the catch",
            result.initialEnergyJ!!,result.standing.value.total,1e-7)
    }

    @Test fun attachmentMetadataAndInventoriesSurviveThePrescribedCut() {
        val rig=setup(2);val result=SimpleSolver(materials).solve(rig)
        val before=result.standing.network;val after=result.catch!!.network
        val attachment=before.attachment!!
        assertEquals(attachment.contacts.copy(main=attachment.contacts.main.copy(threaded=false)),after.attachment!!.contacts)
        assertEquals(Cable.MAIN,attachment.support);assertEquals(Cable.BACKUP,after.attachment!!.support)
        assertEquals(before.splits,after.splits)
        assertEquals(0,attachment.contacts.main.section)
        assertEquals(12.0,attachment.contacts.main.materialCoordinateM,0.0)
        assertEquals(12.6,attachment.contacts.backup.materialCoordinateM,1e-12)
        for(cable in Cable.entries) {
            val a=before.elements.filter{it.cable==cable};val b=after.elements.filter{it.cable==cable}
            assertEquals(a.sumOf{it.rest},b.sumOf{it.rest},1e-10)
            assertEquals(a.sumOf{it.mass},b.sumOf{it.mass},1e-10)
            val node=if(cable==Cable.MAIN)attachment.mainNode else attachment.backupNode
            assertEquals(1,b.count{it.a==node});assertEquals(1,b.count{it.b==node})
        }
        assertTrue(after.elements.all{it.rest>0 && it.a!=it.b})
        assertTrue(result.catch!!.r.all{it.isFinite()})
        assertEquals(WalkerPosture.STANDING,before.posture)
        assertEquals(WalkerPosture.LEASH_SUPPORTED,after.posture)
    }

    @Test fun standingHeightChangesTheBudgetWithoutCountingTheComOffsetTwice() {
        val low=setup(height=1.6);val high=setup(height=1.9)
        val a=SimpleSolver(materials).solve(low);val b=SimpleSolver(materials).solve(high)
        val mg=low.walker.massKg*low.gravity
        assertArrayEquals(a.standing.r,b.standing.r,2e-7)
        assertEquals(mg*.55*(1.9-1.6),b.initialEnergyJ!!-a.initialEnergyJ!!,1e-5)
        // Growing body height also raises hanging COM .05*H above the harness:
        // at the same ring geometry the extra net drop is .5*dH, not .55*dH.
        val ga=low.cut!!.walkerGeometry;val gb=high.cut!!.walkerGeometry
        fun drop(g:WalkerGeometry)=g.atAttachment(0.0,WalkerPosture.STANDING).comM-g.atAttachment(-3.0,WalkerPosture.LEASH_SUPPORTED).comM
        assertEquals(.5*(1.9-1.6),drop(gb)-drop(ga),1e-12)
        assertTrue(b.catch!!.attachmentPosition!!.y<a.catch!!.attachmentPosition!!.y)
        assertEquals(0.0,b.fallEnergy!!.residualJ,1e-5)
    }

    @Test fun physicalLeashLengthChangesEngagementAndFinalCatch() {
        val short=setup(leash=1.2);val long=setup(leash=1.8)
        val a=SimpleSolver(materials).solve(short);val b=SimpleSolver(materials).solve(long)
        assertEquals(a.initialEnergyJ!!,b.initialEnergyJ!!,1e-7)
        assertEquals(.6,long.cut!!.walkerGeometry.engagementDropM-short.cut!!.walkerGeometry.engagementDropM,1e-12)
        val pose=b.catch!!.walkerElevations!!
        assertEquals(1.8,pose.attachmentM-pose.harnessM,1e-12)
        assertEquals(.09,pose.comM-pose.harnessM,1e-12)
        assertTrue(b.catch!!.attachmentPosition!!.y<a.catch!!.attachmentPosition!!.y)
        assertTrue(b.fallEnergy!!.walkerGravityReleasedJ>a.fallEnergy!!.walkerGravityReleasedJ)
        assertEquals(0.0,b.fallEnergy!!.residualJ,1e-5)
    }

    @Test fun backupSagAndAllStoredEnergyAppearExactlyOnce() {
        val rig=setup();val result=SimpleSolver(materials).solve(rig);val ledger=result.fallEnergy!!
        val mg=rig.walker.massKg*rig.gravity;val person=rig.cut!!.walkerGeometry
        val z0=result.standing.walkerElevations!!.comM;val zf=result.catch!!.walkerElevations!!.comM
        val attachmentDrop=result.standing.attachmentPosition!!.y-result.catch!!.attachmentPosition!!.y
        assertEquals(mg*(z0-zf),ledger.walkerGravityReleasedJ,1e-8)
        assertEquals(mg*(attachmentDrop+person.engagementDropM),ledger.walkerGravityReleasedJ,1e-8)
        assertEquals(ledger.walkerGravityReleasedJ+ledger.webbingGravityReleasedJ,ledger.elasticIncreaseJ,1e-5)
        assertEquals(0.0,ledger.dissipatedJ,0.0)
        assertTrue(ledger.turning.backupElasticJ>0)
        assertEquals(result.initialEnergyJ!!,ledger.initial.totalJ,1e-8)
        assertEquals(result.catch!!.value.total,ledger.turning.totalJ,1e-8)
    }

    @Test fun slackEngagementIsContinuousAndZeroDropDoesNotReleaseEnergy() {
        val person=WalkerGeometry(1.8,1.5);val y=-2.3;val mg=80*9.81
        val standing=person.atAttachment(y,WalkerPosture.STANDING)
        assertEquals(standing,person.duringSlackFall(y,0.0))
        assertEquals(.6,standing.slackM,1e-12)
        val engagement=person.duringSlackFall(y,person.engagementDropM)
        val hanging=person.atAttachment(y,WalkerPosture.LEASH_SUPPORTED)
        assertEquals(hanging.comM,engagement.comM,1e-12)
        assertEquals(hanging.harnessM,engagement.harnessM,1e-12)
        assertEquals(0.0,engagement.slackM,1e-12)
        val near=person.duringSlackFall(y,person.engagementDropM-1e-6)
        assertEquals(mg*1e-6,mg*(near.comM-hanging.comM),1e-9)
        assertEquals(0.0,mg*(standing.comM-person.duringSlackFall(y,0.0).comM),0.0)
        // Initially just taut upwards does not mean initially hanging downwards.
        val limit=WalkerGeometry(1.8,.9)
        assertEquals(0.0,limit.initialSlackM,0.0)
        assertEquals(1.8,limit.engagementDropM,1e-12)
    }

    @Test fun verticalDatumDoesNotChangeCatchOrReleasedEnergy() {
        val rig=setup();val base=SimpleSolver(materials).solve(rig)
        val shifted=SimpleSolver(materials).solve(rig.copy(geometry=rig.geometry.copy(leftHeightM=6.0,rightHeightM=6.0)))
        assertEquals(base.fallEnergy!!.walkerGravityReleasedJ,shifted.fallEnergy!!.walkerGravityReleasedJ,.002)
        assertEquals(base.fallEnergy!!.webbingGravityReleasedJ,shifted.fallEnergy!!.webbingGravityReleasedJ,.002)
        assertEquals(base.catch!!.attachmentPosition!!.y+6,shifted.catch!!.attachmentPosition!!.y,2e-4)
        assertArrayEquals(base.catch!!.value.tensions,shifted.catch!!.value.tensions,.02)
    }

    @Test fun oldSavedInputsRetainPhysicalLeashAndBodyHeightMeaning() {
        val old="""{"cutEnabled":true,"walkerHeight":"1.8","leash":"1.5","cutPosition":"26"}"""
        val draft=Json.decodeFromString<Draft>(old)
        val model=Cut(draft.cutPosition.toDouble(),draft.walkerHeight.toDouble(),draft.leash.toDouble()).walkerGeometry
        assertEquals(.99,model.standingComHeightM,1e-12)
        assertEquals(-1.41,model.comOffset(WalkerPosture.LEASH_SUPPORTED),1e-12)
        assertEquals(draft,Json.decodeFromString<Draft>(Json.encodeToString(draft)))
    }

    @Test fun impossibleGeometryIsRejectedInsteadOfReturningSuccess() {
        for(person in listOf(1.8 to .5,1.8 to -1.0,0.0 to 1.5,Double.NaN to 1.5)) {
            assertThrows(IllegalArgumentException::class.java){WalkerGeometry(person.first,person.second)}
        }
        val rig=setup();val n=buildNetwork(rig,materials,listOf(35.0),true)
        assertThrows(IllegalArgumentException::class.java){Equilibrium().solve(n,DoubleArray(n.coordinates.size){Double.NaN})}
        assertThrows(IllegalArgumentException::class.java){Equilibrium().solve(n,fixedExtra=mapOf(999 to 0.0))}
        val stalled=assertThrows(SolverWarning::class.java){Equilibrium().solve(n,maxIterations=1)}
        assertTrue(stalled.message!!.contains("did not converge"))
        assertThrows(IllegalArgumentException::class.java){SimpleSolver(materials).solve(setup(leash=.5))}
        assertThrows(IllegalArgumentException::class.java){SimpleSolver(materials).solve(rig.copy(geometry=rig.geometry.copy(spanM=-1.0)))}
        assertThrows(IllegalArgumentException::class.java){SimpleSolver(materials).solve(rig.copy(cut=rig.cut!!.copy(referencePositionM=12.0)))}
    }
}

package org.linesolver.base

import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.contact.*
import org.linesolver.base.energy.*
import org.linesolver.base.material.*
import org.linesolver.base.model.*
import org.linesolver.base.solver.*
import org.linesolver.base.ui.*
import org.linesolver.base.diagnostics.*

class Phase08Test {
    private val materials=JsonMaterialRepository(javaClass.classLoader!!.getResource("webbing_material_models_v1.json")!!.readText())
    private val main=materials.webbings("RAED").first{it.product=="Parsec"}.id
    private val backup=materials.webbings("RAED").first{it.product=="MOTM"}.id
    private fun setup(cut:Double?=null)=LineSetup(Geometry(70.0,0.0,0.0),List(2){Section(35.0,main,backup,.05)},1500.0,Walker(80.0,12.0),cut?.let{Cut(it,1.8,1.5)},ContactOptions(),9.81,8)
    private fun ring(slackMain:Boolean=false):Network {
        val m=LinearMaterial(50000.0,0.0)
        val e=Cable.entries.flatMap { c->val node=if(c==Cable.MAIN)2 else 3;val length=if(c==Cable.MAIN && slackMain)20.0 else 5.0
            listOf(Element(0,node,length,0.0,m,0,c,0.0,.5),Element(node,1,length,0.0,m,0,c,.5,1.0)) }
        return Network(doubleArrayOf(0.0,0.0,10.0,0.0,5.0,-1.0,5.0,-1.0),e,listOf(0,1),
            LeashAttachment(2,3,ContactState(LineContact(0,5.0),LineContact(0,5.0)),Cable.BACKUP),80.0,9.81,10.0,WalkerGeometry(1.8,1.5),WalkerPosture.LEASH_SUPPORTED)
    }
    @Test fun intactContactsShareLoadWithoutBecomingStructuralSplits() {
        val s=Equilibrium().solve(ring())
        assertEquals(s.r[4],s.r[6],0.0);assertEquals(s.r[5],s.r[7],0.0)
        assertTrue(s.network.structuralConnections.isEmpty());assertNotEquals(s.network.attachment!!.mainNode,s.network.attachment!!.backupNode)
        for(c in Cable.entries)assertEquals(80*9.81/2,s.ringReaction(c)!!.y,2e-6)
        assertEquals(5.0,s.network.attachment!!.contacts.main.materialCoordinateM,0.0)
    }
    @Test fun intactThreadedMainCanRemainUnloaded() {
        val s=Equilibrium().solve(ring(true))
        assertTrue(s.network.attachment!!.contacts.main.threaded)
        assertEquals(0.0,s.ringReaction(Cable.MAIN)!!.y,1e-12)
        assertEquals(80*9.81,s.ringReaction(Cable.BACKUP)!!.y,2e-6)
        assertTrue(s.network.elements.indices.filter{s.network.elements[it].cable==Cable.MAIN}.all{s.value.tensions[it]==0.0})
    }
    @Test fun cutsInsideAndOutsideWalkerSectionPreserveLabelsMassAndEnergy() {
        for(cut in listOf(26.0,50.0)) {
            val result=SimpleSolver(materials).solve(setup(cut))
            val pre=result.standing.network
            for(s in listOf(result.catch!!,result.rest!!)) {
                assertEquals(cut>35,s.network.attachment!!.contacts.main.threaded)
                assertEquals(pre.splits,s.network.splits)
                assertEquals(pre.attachment!!.contacts.main.materialCoordinateM,s.network.attachment!!.contacts.main.materialCoordinateM,0.0)
                assertEquals(pre.elements.sumOf{it.mass},s.network.elements.sumOf{it.mass},1e-10)
                assertEquals(cut>35,s.network.ringContactPair!=null)
            }
            val rest=result.rest!!
            assertEquals(80*9.81,Cable.entries.sumOf{rest.ringReaction(it)?.y?:0.0},3e-6)
            assertEquals(0.0,result.fallEnergy!!.residualJ,1e-5)
            assertEquals(cut>35,stateDiagnostics(rest).getValue("main_contact").toString().contains("\"threaded\":true"))
        }
    }
    @Test fun repeatedFailureUsesExactStandingCoordinatesAndFreshSolveMatches() {
        val solver=SimpleSolver(materials);val standing=solver.solve(setup())
        for(section in 0..1) {
            val q=failureCoordinate(standing.installedLengths,section,12.0)
            val rig=setup(q);val cached=solver.solveFrom(rig,standing);val fresh=solver.solve(rig)
            assertArrayEquals(standing.standing.r,cached.standing.r,0.0)
            assertArrayEquals(fresh.catch!!.r,cached.catch!!.r,1e-6)
            assertEquals(fresh.initialEnergyJ!!,cached.initialEnergyJ!!,1e-6)
        }
        assertEquals(2.5,failureCoordinate(listOf(10.0),0,5.0),0.0)
        val moved=solver.solve(setup().copy(walker=Walker(80.0,22.0)))
        assertNotEquals(standing.standing.walker,moved.standing.walker)
    }
    @Test fun sparseDirectStepMatchesDenseAndPhysicalSplitRetainsContact() {
        val a=arrayOf(doubleArrayOf(4.0,-1.0,0.0,1.0),doubleArrayOf(-1.0,5.0,-2.0,0.0),doubleArrayOf(0.0,-2.0,6.0,-1.0),doubleArrayOf(1.0,0.0,-1.0,4.0))
        val b=doubleArrayOf(2.0,-1.0,4.0,3.0);val reg=1e-6
        val dense=linearSolve(Array(4){i->a[i].copyOf().also{it[i]+=reg}},b)!!
        val sparse=sparseSolve(Array(4){i->a[i].mapIndexed{j,v->j to v}.filter{it.second!=0.0}.toMap().toMutableMap()},b,reg,{})!!
        assertArrayEquals(dense,sparse,1e-12)
        val rig=setup(50.0).copy(walker=Walker(80.0,35.0))
        val result=SimpleSolver(materials).solve(rig)
        assertTrue(result.catch!!.network.attachment!!.contacts.main.threaded)
        assertTrue(result.catch!!.network.attachment!!.mainNode in result.catch!!.network.splits)
    }
    private fun draft(count:String="5")=Draft(span="100",leftHeight="0",rightHeight="0",position="42",mass="80",walkerHeight="1.8",leash="1.5",pretensionKn="0.1").withUniform(
        UniformGenerator(count,Selection("RAED",main),Selection("RAED",backup),"5",ExtraUnit.METRES))
    @Test fun hundredSectionsAndConversionPreserveTotalsAndLibrary() {
        for(n in listOf(1,2,5,13,100)) {
            val d=draft(n.toString());val sections=d.activeSections
            assertEquals(n,sections.size);assertEquals(100.0,sections.sumOf{it.length.number()!!},1e-10)
            assertEquals(5.0,sections.sumOf{it.extra.number()!!},1e-10)
            assertEquals(n-1,d.schematicSplitFractions().size)
            assertEquals(d.validate(materials).setup,d.toMixed().validate(materials).setup)
            val c=RigConfiguration.from(d);val doc=RigLibraryDocument(rigs=listOf(SavedRig("id","100",1,c)))
            assertEquals(c,RigLibraryCodec.decode(RigLibraryCodec.encode(doc)).rigs.single().configuration)
        }
    }
    @Test fun largeNetworkSolvesWithSparseHessian() {
        // Choose a physically feasible pretension from a known half-metre right-end take-up.
        // Hundreds of short sections do not waive the existing terminal-only installation rule.
        val d=draft("100").copy(elements="8",pretensionKn="0.5")
        val rig=d.validate(materials).setup!!
        val installed=Equilibrium().solve(buildNetwork(rig,materials,List(99){1.0}+.5,false))
        val tension=installed.value.tensions[installed.network.elements.indexOfLast{it.cable==Cable.MAIN}]
        val result=SimpleSolver(materials).solve(rig.copy(pretensionN=tension))
        assertTrue(result.standing.r.size>512);assertTrue(result.standing.residualN<2e-6)
        assertEquals(99,result.standing.network.structuralConnections.size)
    }
    @Test fun downwardPreviewResetFormattingAndHitTesting() {
        val d=draft();val p=previewGeometry(d)!!
        assertEquals(p,previewGeometry(d));assertEquals(-7.5,p.walker.y,1e-12)
        p.backup.forEach{loop->loop.forEach { q->
            val mainY=if(q.x<=p.walker.x)-7.5*q.x/p.walker.x else -7.5*(100-q.x)/(100-p.walker.x)
            assertTrue(q.y<=mainY+1e-10)
        }}
        val reset=resetWorkingRig(d)
        assertEquals(WalkerPreferences.from(d),WalkerPreferences.from(reset));assertEquals(ModelPreferences.from(d),ModelPreferences.from(reset))
        assertEquals(RigConfiguration.from(freshGeneratorDraft()),RigConfiguration.from(reset))
        assertEquals("6.70 kN",forceText(6699.1));assertEquals("1.25 kJ",energyText(1248.63));assertEquals("48.0",significant(47.9804))
        val s=Equilibrium().solve(ring());val view=Viewport(10.0,0.0,0.0)
        assertEquals(0,nearestMainSection(s,view,view.map(Point(s.r[4],s.r[5])),1.0))
        assertNull(nearestMainSection(s,view,Point(500.0,500.0),1.0))
    }
}

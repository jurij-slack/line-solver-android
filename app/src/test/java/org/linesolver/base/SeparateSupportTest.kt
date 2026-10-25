package org.linesolver.base

import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.contact.ContactOptions
import org.linesolver.base.energy.*
import org.linesolver.base.material.JsonMaterialRepository
import org.linesolver.base.model.*
import org.linesolver.base.solver.*
import org.linesolver.base.ui.*

class SeparateSupportTest {
    private val materials=JsonMaterialRepository(javaClass.classLoader!!.getResource("webbing_material_models_v1.json")!!.readText())
    private fun setup(count:Int=1,position:Double=12.0):LineSetup {
        val a=materials.webbings("RAED").first{it.product=="Parsec"}.id
        val b=materials.webbings("RAED").first{it.product=="MOTM"}.id
        return LineSetup(Geometry(35.0*count,0.0,0.0),List(count){Section(35.0,a,b,.05)},1500.0,Walker(80.0,position),Cut(26.0,1.8,1.5),ContactOptions(),9.81,8)
    }
    @Test fun onlyActualSplitsCanShareCableDegreesOfFreedom() {
        for(count in 1..3) for(position in listOf(12.0,18.0,if(count>1)35.0 else 20.0)) {
            val rig=setup(count,position);val n=buildNetwork(rig,materials,List(count){35.0},true)
            fun nodes(c:Cable)=n.elements.filter{it.cable==c}.flatMap{listOf(it.a,it.b)}.toSet()
            assertEquals("The leash must not mechanically tie the cables",n.splits.toSet(),nodes(Cable.MAIN).intersect(nodes(Cable.BACKUP)))
        }
    }
    @Test fun isolatedCutMainCarriesItsOwnWeightNotTheWalker() {
        val result=SimpleSolver(materials).solve(setup())
        for(s in listOf(result.catch!!,result.rest!!)) {
            val main=s.network.elements.indices.filter{s.network.elements[it].cable==Cable.MAIN}
            val totalWeight=main.sumOf{s.network.elements[it].mass}*s.network.gravity
            assertTrue("Cut main tension must be bounded by its own hanging weight",main.maxOf{s.value.tensions[it]}<=totalWeight+1e-4)
        }
    }

    @Test fun independentSupportsAndDrawingUseExactlyTheSolvedCoordinates() {
        for(count in 1..3) for(position in listOf(12.0,if(count>1)35.0 else 18.0)) {
            val result=SimpleSolver(materials).solve(setup(count,position))
            val states=listOf(result.standing,result.catch!!,result.rest!!)
            for((index,state) in states.withIndex()) {
                val support=if(index==0)Cable.MAIN else Cable.BACKUP
                assertEquals(support,state.network.attachment!!.support)
                val figure=state.walkerDrawing()!!;val pose=state.walkerElevations!!
                assertEquals(state.attachmentPosition,figure.attachment)
                assertEquals(pose.comM,figure.com.y,0.0);assertEquals(pose.harnessM,figure.harness.y,0.0)
                if(index>0) {
                    assertEquals(1.5,figure.attachment.y-figure.harness.y,1e-12)
                    assertTrue(figure.com.y<figure.attachment.y);assertNotNull(figure.leash)
                    assertTrue(state.forceLabel(Cable.MAIN).startsWith("Post-failure"))
                } else {assertNull(figure.leash);assertTrue(figure.com.y>figure.attachment.y)}
                assertTrue(state.residualN<2e-6)
            }
            assertNotEquals(result.catch!!.attachmentPosition,result.rest!!.attachmentPosition)
            assertEquals(0.0,result.fallEnergy!!.residualJ,1e-5)
            assertEquals(result.standing.network.splits,result.catch!!.network.splits)
        }
    }
}

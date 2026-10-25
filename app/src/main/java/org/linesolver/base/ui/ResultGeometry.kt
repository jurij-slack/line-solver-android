package org.linesolver.base.ui

import org.linesolver.base.energy.*
import org.linesolver.base.model.WalkerPosture
import org.linesolver.base.solver.StaticState

/** Shared physical coordinates for rendering, diagnostics and geometry tests. No pixel offsets. */
data class WalkerDrawing(val attachment:Point,val harness:Point,val com:Point,val feet:Point,
    val head:Point,val headRadiusM:Double,val limbs:List<Pair<Point,Point>>,val leash:Pair<Point,Point>?) {
    val bounds get()=listOf(Point(feet.x-headRadiusM*4,feet.y),Point(feet.x+headRadiusM*4,head.y+headRadiusM),attachment)
}
fun StaticState.walkerDrawing():WalkerDrawing? {
    val a=attachmentPosition?:return null;val p=walkerElevations?:return null
    val h=network.walkerGeometry?.heightM?:return null
    fun point(dx:Double,f:Double)=Point(a.x+dx*h,p.feetM+f*h)
    val harness=Point(a.x,p.harnessM);val neck=point(0.0,.8)
    return WalkerDrawing(a,harness,Point(a.x,p.comM),point(0.0,0.0),point(0.0,.925),.075*h,
        listOf(neck to harness,harness to point(-.18,0.0),harness to point(.18,0.0),neck to point(-.25,.55),neck to point(.25,.55)),
        if(network.posture==WalkerPosture.LEASH_SUPPORTED)a to harness else null)
}
data class SectionForce(val section:Int,val cable:Cable,val maximumN:Double)
fun StaticState.sectionForces()=network.elements.groupBy{it.section to it.cable}.map{(key,_)->
    SectionForce(key.first,key.second,network.elements.indices.filter{network.elements[it].section==key.first && network.elements[it].cable==key.second}.maxOf{value.tensions[it]})
}
fun StaticState.forceLabel(cable:Cable):String =
    "${if(network.broken==null)"Pre-failure" else "Post-failure"} ${cable.name.lowercase()} spatial maximum"

/** Displayed incident backup segments, made solid near the ring in screen space. */
fun StaticState.backupThreading(lengthM:Double)=backupDrawing().threading(lengthM)

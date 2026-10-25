package org.linesolver.base.ui

import org.linesolver.base.energy.*
import org.linesolver.base.model.*
import org.linesolver.base.solver.StaticState
import java.util.Locale
import kotlin.math.*

fun resetWorkingRig(d:Draft)=WalkerPreferences.from(d).applyTo(freshGeneratorDraft()).copy(gravity=d.gravity,elements=d.elements,resistance=d.resistance,modelConnections=d.modelConnections,showBungeeOptions=d.showBungeeOptions)
/** Section selection uses the existing prescribed cut, at its midpoint (quarter point if occupied). */
fun failureCoordinate(lengths:List<Double>,section:Int,walker:Double):Double {
    require(section in lengths.indices && lengths.all{it>0 && it.isFinite()})
    val start=lengths.take(section).sum();val mid=start+lengths[section]*.5
    return if(abs(mid-walker)<1e-8)start+lengths[section]*.25 else mid
}
fun significant(value:Double)=String.format(Locale.ROOT,"%.3g",value)
fun forceText(n:Double)=if(abs(n)>=1000)"${significant(n/1000)} kN" else "${significant(n)} N"
fun energyText(j:Double)=if(abs(j)>=1000)"${significant(j/1000)} kJ" else "${significant(j)} J"

data class RigPreview(val main:List<Point>,val backup:List<List<Point>>,val splits:List<Point>,val walker:Point)
fun previewGeometry(d:Draft):RigPreview? {
    val span=d.span.number()?.takeIf{it>0}?:return null
    val left=d.leftHeight.number()?:return null;val right=d.rightHeight.number()?:return null
    val sections=d.activeSections;val total=sections.sumOf{it.length.number()?:0.0}
    val f=(if(total>0)(d.position.number()?:total*.5)/total else .5).coerceIn(.001,.999)
    fun at(q:Double):Point {
        val sag=.075*span*(if(q<=f)q/f else (1-q)/(1-f))
        return Point(span*q,left*(1-q)+right*q-sag)
    }
    val boundaries=listOf(0.0)+d.schematicSplitFractions()+1.0
    val main=(boundaries+f).distinct().sorted().map(::at)
    val loops=boundaries.zipWithNext().mapIndexed { i,(a,b) ->
        val section=sections.getOrNull(i);val length=section?.length?.number()?:span*(b-a)
        val extra=section?.extra?.number()?.coerceAtLeast(0.0)?:0.0
        val metres=if(section?.extraUnit==ExtraUnit.METRES)extra else length*extra/100
        val start=at(a);val end=at(b)
        // The editor is illustrative: add prepared extra to the schematic main arc.
        // The guide is a drawing point, never a new section or a fixed backup label.
        if(f>a && f<b) {
            val ring=at(f)
            routedBackup(i,start,ring,end,distance(start,ring)+distance(ring,end)+metres).points
        } else slackLoop(start,end,distance(start,end)+metres)
    }
    return RigPreview(main,loops,boundaries.drop(1).dropLast(1).map(::at),at(f))
}
fun StaticState.pointAtReference(q:Double,lengths:List<Double>):Point? {
    var section=0;var local=q
    while(section<lengths.lastIndex && local>lengths[section]){local-=lengths[section];section++}
    val f=local/lengths[section]
    val e=network.elements.firstOrNull{it.cable==Cable.MAIN && it.section==section && f>=it.from && f<=it.to}?:return null
    val t=(f-e.from)/(e.to-e.from)
    return Point(r[2*e.a]*(1-t)+r[2*e.b]*t,r[2*e.a+1]*(1-t)+r[2*e.b+1]*t)
}
fun nearestMainSection(state:StaticState,view:Viewport,point:Point,tolerance:Double):Int? {
    fun distance(e:Element):Double {
        val a=view.map(Point(state.r[2*e.a],state.r[2*e.a+1]));val b=view.map(Point(state.r[2*e.b],state.r[2*e.b+1]))
        val dx=b.x-a.x;val dy=b.y-a.y;val t=if(dx*dx+dy*dy==0.0)0.0 else ((point.x-a.x)*dx+(point.y-a.y)*dy)/(dx*dx+dy*dy)
        return hypot(point.x-a.x-t.coerceIn(0.0,1.0)*dx,point.y-a.y-t.coerceIn(0.0,1.0)*dy)
    }
    return state.network.elements.filter{it.cable==Cable.MAIN}.minByOrNull(::distance)?.takeIf{distance(it)<=tolerance}?.section
}

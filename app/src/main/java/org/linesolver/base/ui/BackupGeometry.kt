package org.linesolver.base.ui

import org.linesolver.base.energy.Cable
import org.linesolver.base.energy.Point
import org.linesolver.base.solver.StaticState
import kotlin.math.*

internal fun distance(a:Point,b:Point)=hypot(b.x-a.x,b.y-a.y)
internal fun arcLength(points:List<Point>)=points.zipWithNext().sumOf{(a,b)->distance(a,b)}

/** A downward display loop whose sampled arc, including its endpoints, has the given length. */
internal fun slackLoop(a:Point,b:Point,lengthM:Double):List<Point> {
    if(lengthM<=distance(a,b)+1e-10)return listOf(a,b)
    fun curve(depth:Double)=(0..48).map { i->
        when(i) {
            0->a
            48->b
            else->{val u=i/48.0;Point(a.x+(b.x-a.x)*u,a.y+(b.y-a.y)*u-depth*sin(PI*u))}
        }
    }
    // Dragging calls this every frame; avoid allocating trial polylines during bisection.
    fun length(depth:Double):Double {
        var previousY=a.y;var result=0.0
        val dx=(b.x-a.x)/48
        for(i in 1..48) {
            val u=i/48.0;val y=if(i==48)b.y else a.y+(b.y-a.y)*u-depth*sin(PI*u)
            result+=hypot(dx,y-previousY);previousY=y
        }
        return result
    }
    var low=0.0;var high=lengthM
    repeat(48) {val mid=(low+high)/2;if(length(mid)<lengthM)low=mid else high=mid}
    return curve((low+high)/2)
}

data class BackupCurve(val section:Int,val points:List<Point>,val lengthDeficitM:Double=0.0)
data class BackupDrawing(val curves:List<BackupCurve>,val ring:Point?,val schematic:Boolean) {
    val points get()=curves.flatMap{it.points}
    val lengthDeficitM get()=curves.sumOf{it.lengthDeficitM}

    /** Follow both displayed branches through the ring so dash gaps cannot hide the contact. */
    fun threading(lengthM:Double):List<Pair<Point,Point>> {
        val ring=ring?:return emptyList()
        return curves.flatMap { curve->
            val i=curve.points.indexOf(ring)
            if(i<0)emptyList() else {
                val branches=listOf(curve.points.take(i+1).asReversed(),curve.points.drop(i))
                branches.flatMap { branch->
                    var remaining=lengthM
                    branch.zipWithNext().mapNotNull { (a,b)->
                        val d=distance(a,b)
                        if(remaining<=0.0 || d==0.0)null else {
                            val f=min(remaining/d,1.0);remaining-=d
                            a to Point(a.x+(b.x-a.x)*f,a.y+(b.y-a.y)*f)
                        }
                    }
                }
            }
        }
    }
}

/** Only geometric slack is distributed across the sliding guide. No backup material label
 * is fixed at the ring and no length is borrowed across a real section connection.
 * A short section is explicitly reported: drawing cannot supply missing physical length.
 */
internal fun routedBackup(section:Int,a:Point,ring:Point,b:Point,lengthM:Double):BackupCurve {
    val left=distance(a,ring);val right=distance(ring,b);val minimum=left+right
    val slack=max(0.0,lengthM-minimum)
    val fraction=if(minimum>0)left/minimum else .5
    val points=slackLoop(a,ring,left+slack*fraction).dropLast(1)+slackLoop(ring,b,right+slack*(1-fraction))
    return BackupCurve(section,points,max(0.0,minimum-lengthM))
}

/** Standing display only: route the occupied section through the actual main attachment.
 * Preserve its current backup arc (or reference length if numerically compressed). This is
 * schematic slack placement, not a newly solved equilibrium. Catch/Rest retain every solved
 * backup vertex. Nothing here edits coordinates, contact-v1, energies or network topology.
 * A drag supplies a MAIN reference coordinate; it is never treated as a backup length.
 */
fun StaticState.backupDrawing(previewPositionM:Double?=null):BackupDrawing {
    fun point(node:Int)=Point(r[2*node],r[2*node+1])
    if(network.bungeePaths.isNotEmpty()) return BackupDrawing(network.elements.filter{it.cable==Cable.BACKUP}.map{e->BackupCurve(e.section,listOf(point(e.a),point(e.b)))},attachmentPosition,false)
    val groups=network.elements.filter{it.cable==Cable.BACKUP}.groupBy{it.section}
    val lengths=network.elements.filter{it.cable==Cable.MAIN}.groupBy{it.section}.toSortedMap().values.map{es->es.sumOf{it.rest}}
    val ring=if(previewPositionM==null)attachmentPosition else pointAtReference(previewPositionM,lengths)
    var section=network.attachment?.contacts?.main?.section
    if(previewPositionM!=null) {
        var q=previewPositionM;section=0
        while(section<lengths.lastIndex && q>=lengths[section]){q-=lengths[section];section++}
    }
    val standing=network.broken==null
    var routed=false
    val curves=groups.toSortedMap().map { (index,edges)->
        val ordered=edges.sortedBy{it.from}
        val points=listOf(point(ordered.first().a))+ordered.map{point(it.b)}
        if(standing && index==section && ring!=null && ring!=points.first() && ring!=points.last()) {
            routed=true
            routedBackup(index,points.first(),ring,points.last(),max(arcLength(points),edges.sumOf{it.rest}))
        } else BackupCurve(index,points)
    }
    return BackupDrawing(curves,ring,routed)
}

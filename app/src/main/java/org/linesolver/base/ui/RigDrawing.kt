package org.linesolver.base.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.linesolver.base.energy.*
import org.linesolver.base.model.*
import org.linesolver.base.solver.StaticState
import kotlin.math.*

/** One scale in px/metre for both axes. Also supports future hit-testing via inverse(). */
data class Viewport(val scale:Double,val x0:Double,val y0:Double) {
    fun map(p:Point)=Point(x0+p.x*scale,y0-p.y*scale)
    fun zoomed(factor:Double,width:Double,height:Double,pan:Point=Point(0.0,0.0))=Viewport(scale*factor,
        width/2+(x0-width/2)*factor+pan.x,height/2+(y0-height/2)*factor+pan.y)
    fun inverse(p:Point)=Point((p.x-x0)/scale,(y0-p.y)/scale)
    companion object {
        fun fit(points:List<Point>,width:Double,height:Double,padding:Double):Viewport {
            val minX=points.minOf{it.x};val maxX=points.maxOf{it.x};val minY=points.minOf{it.y};val maxY=points.maxOf{it.y}
            val scale=min((width-2*padding)/max(maxX-minX,1e-6),(height-2*padding)/max(maxY-minY,1e-6))
            return Viewport(scale,width/2-(minX+maxX)*scale/2,height/2+(minY+maxY)*scale/2)
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun RigDrawing(draft:Draft,modifier:Modifier=Modifier,state:StaticState?=null,selectedSection:Int?=null,onSectionTap:((Int)->Unit)?=null,previewPositionM:Double?=null,onJointTap:((String)->Unit)?=null,onConnections:(()->Unit)?=null) {
    val main=MaterialTheme.colorScheme.onSurface;val backup=MaterialTheme.colorScheme.tertiary;val person=MaterialTheme.colorScheme.primary
    val span=draft.span.number();val left=draft.leftHeight.number();val right=draft.rightHeight.number()
    val geometry=span!=null && span>0 && left!=null && right!=null
    var zoom by remember(state){mutableFloatStateOf(1f)}
    var pan by remember(state){mutableStateOf(Offset.Zero)}
    BoxWithConstraints(modifier) {
    val drawingWidth=maxWidth
    Column {
        if(!geometry){Text("Enter span and both anchor heights to draw the rig.",Modifier.padding(20.dp));return@Column}
        val preview=remember(draft){previewGeometry(draft)}
        val backupDrawing=remember(state,previewPositionM){state?.backupDrawing(previewPositionM)}
        val ring=if(state==null)preview?.walker else backupDrawing?.ring
        val solvedWalker=if(previewPositionM==null)state?.walkerDrawing() else null
        var canvasSize by remember{mutableStateOf(IntSize.Zero)}
        val paddingPx=with(LocalDensity.current){32.dp.toPx().toDouble()}
        val joints=remember(draft){if(draft.modelConnections)StructuralConnections.labeled(draft).filter{it.joint.kind!=JointKind.anchor} else emptyList()}
        val jointPoints=remember(draft,state) { joints.mapNotNull { l ->
            val point=if(state!=null)runCatching{val i=StructuralConnections.node(l.joint,draft,state.network);Point(state.r[2*i],state.r[2*i+1])}.getOrNull()
                else if(draft.bungees?.banks.isNullOrEmpty()) {
                    val i=StructuralConnections.sectionIds(draft).indexOf(l.joint.ends.first().instanceId)
                    val fraction=draft.schematicSplitFractions().getOrNull(i)
                    fraction?.let{Point(span*it,left*(1-it)+right*it)}
                } else null
            point?.let{l to it}
        } }
        val nodes=state?.r?.let{r->(r.indices step 2).map{Point(r[it],r[it+1])}}.orEmpty()
        val points=if(state!=null)state.network.elements.filter{it.cable==Cable.MAIN}.flatMap{listOf(nodes[it.a],nodes[it.b])}+backupDrawing!!.points+solvedWalker?.bounds.orEmpty()+listOfNotNull(ring)
            else preview?.let{it.main+it.backup.flatten()}?:listOf(Point(0.0,left),Point(span,right))
        val aspect=((points.maxOf{it.y}-points.minOf{it.y})/(points.maxOf{it.x}-points.minOf{it.x}).coerceAtLeast(1e-6)).toFloat()
        val graphHeight=((drawingWidth-64.dp).coerceAtLeast(1.dp)*aspect+64.dp).coerceIn(160.dp,300.dp)
        val desc=(if(state==null)"Schematic rig, not solved." else if(backupDrawing?.schematic==true)"Solved mainline with schematic backup loops through the leash ring." else "Solved rig.")+" Main solid, backup dashed. Equal horizontal and vertical metre scale."
        Canvas(Modifier.fillMaxWidth().height(graphHeight).clipToBounds()
            .then(if(zoom>1f)Modifier.pointerInput(state){detectTransformGestures { _,delta,factor,_->
                zoom=(zoom*factor).coerceIn(1f,4f);pan+=delta
            }} else Modifier)
            .onSizeChanged{canvasSize=it}.pointerInput(state,onSectionTap,onJointTap,jointPoints,canvasSize,points,zoom,pan){detectTapGestures { tap ->
            if(canvasSize.width>0 && onJointTap!=null && jointPoints.isNotEmpty()) {
                val view=Viewport.fit(points,canvasSize.width.toDouble(),canvasSize.height.toDouble(),paddingPx).zoomed(zoom.toDouble(),canvasSize.width.toDouble(),canvasSize.height.toDouble(),Point(pan.x.toDouble(),pan.y.toDouble()))
                val nearest=jointPoints.minBy{(_,p)->val q=view.map(p);hypot(q.x-tap.x,q.y-tap.y)}
                val q=view.map(nearest.second)
                if(hypot(q.x-tap.x,q.y-tap.y)<=24.dp.toPx()) { onJointTap(nearest.first.joint.id);return@detectTapGestures }
            }
            if(state!=null && onSectionTap!=null && canvasSize.width>0) {
                val view=Viewport.fit(points,canvasSize.width.toDouble(),canvasSize.height.toDouble(),paddingPx).zoomed(zoom.toDouble(),canvasSize.width.toDouble(),canvasSize.height.toDouble(),Point(pan.x.toDouble(),pan.y.toDouble()))
                nearestMainSection(state,view,Point(tap.x.toDouble(),tap.y.toDouble()),24.dp.toPx().toDouble())?.let(onSectionTap)
            }
        }}.testTag("full-span-drawing").semantics{contentDescription=desc}) {
            val pad=32.dp.toPx().toDouble()
            val view=Viewport.fit(points,size.width.toDouble(),size.height.toDouble(),pad).zoomed(zoom.toDouble(),size.width.toDouble(),size.height.toDouble(),Point(pan.x.toDouble(),pan.y.toDouble()))
            fun screen(p:Point):Offset {val q=view.map(p);return Offset(q.x.toFloat(),q.y.toFloat())}
            fun drawBackup(points:List<Point>) {
                val path=Path()
                points.forEachIndexed { i,p->val q=screen(p);if(i==0)path.moveTo(q.x,q.y) else path.lineTo(q.x,q.y) }
                drawPath(path,backup,style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(),5.dp.toPx()))))
            }
            if(state!=null) {
                backupDrawing!!.curves.forEach{drawBackup(it.points)}
                for(e in state.network.elements.filter{it.cable==Cable.MAIN})
                    drawLine(if(e.section==selectedSection)person else main,screen(nodes[e.a]),screen(nodes[e.b]),3.dp.toPx())
                for((a,b) in backupDrawing.threading(7.dp.toPx()/view.scale))
                    drawLine(backup,screen(a),screen(b),2.dp.toPx())
                for(i in state.network.splits) drawRect(main,screen(nodes[i])-Offset(4.dp.toPx(),4.dp.toPx()),androidx.compose.ui.geometry.Size(8.dp.toPx(),8.dp.toPx()))
                for(path in state.network.bungeePaths) {
                    for(i in path.elementIndices) { val e=state.network.elements[i]
                        drawLine(person,screen(nodes[e.a]),screen(nodes[e.b]),4.dp.toPx())
                        drawCircle(person,2.dp.toPx(),screen(Point((nodes[e.a].x+nodes[e.b].x)/2,(nodes[e.a].y+nodes[e.b].y)/2)))
                    }
                    val start=state.network.elements[path.elementIndices.first()].a
                    val end=state.network.elements[path.elementIndices.last()].b
                    for(i in listOf(start,end))drawCircle(main,4.dp.toPx(),screen(nodes[i]))
                }
            } else {
                preview?.let { schematic ->
                    schematic.backup.forEach(::drawBackup)
                    schematic.main.zipWithNext().forEach{(a,b)->drawLine(main,screen(a),screen(b),2.dp.toPx())}
                    val threading=BackupDrawing(schematic.backup.mapIndexed{i,p->BackupCurve(i,p)},schematic.walker,true)
                    threading.threading(7.dp.toPx()/view.scale).forEach{(a,b)->drawLine(backup,screen(a),screen(b),2.dp.toPx())}
                    schematic.splits.forEach{p->drawRect(main,screen(p)-Offset(4.dp.toPx(),4.dp.toPx()),androidx.compose.ui.geometry.Size(8.dp.toPx(),8.dp.toPx()))}
                }

            }
            val textPaint=android.graphics.Paint().apply{color=android.graphics.Color.rgb((person.red*255).toInt(),(person.green*255).toInt(),(person.blue*255).toInt());textSize=12.dp.toPx();isAntiAlias=true}
            val occupied=mutableListOf<Offset>()
            for((l,p) in jointPoints) {
                val q=screen(p);drawCircle(person,5.dp.toPx(),q)
                if(occupied.none{abs(it.x-q.x)<36.dp.toPx() && abs(it.y-q.y)<24.dp.toPx()}) {
                    drawContext.canvas.nativeCanvas.drawText(l.label,q.x+6.dp.toPx(),q.y-8.dp.toPx(),textPaint);occupied+=q
                }
            }
            if(solvedWalker!=null)drawWalker(solvedWalker,view,person,backup)
            else ring?.let {
                // Unknown body height in equilibrium-only/schematic mode: symbolic glyph.
                val feet=screen(it);val height=34.dp.toPx()
                val hip=feet-Offset(0f,.5f*height);val neck=feet-Offset(0f,.8f*height)
                drawCircle(person,.075f*height,feet-Offset(0f,.925f*height))
                fun limb(a:Offset,b:Offset)=drawLine(person,a,b,2.dp.toPx(),cap=StrokeCap.Round)
                limb(neck,hip);limb(hip,feet-Offset(.18f*height,0f));limb(hip,feet+Offset(.18f*height,0f))
                limb(neck,feet-Offset(.25f*height,.55f*height));limb(neck,feet+Offset(.25f*height,-.55f*height))
            }
            // Also visible when standing-only results have no solver-owned body geometry.
            ring?.let{drawCircle(backup,5.dp.toPx(),screen(it),style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("━\u00a0Main   ┄\u00a0Backup   ○\u00a0Ring",style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f))
            TextButton(onClick={zoom=(zoom*1.5f).coerceAtMost(4f)},modifier=Modifier.testTag("rig-zoom-in").semantics{contentDescription="Zoom in"}){Text("+")}
            TextButton(onClick={zoom=1f;pan=Offset.Zero},modifier=Modifier.testTag("rig-fit")){Text("Fit")}
        }
        if(draft.modelConnections) {
            ConnectionEditLinks(joints,onJointTap,onConnections)

        }
        if(state==null && !draft.bungees?.banks.isNullOrEmpty())Text(if(DemoBungeePolicy.blocks(draft))"Bungee configuration retained · Currently unavailable. Preview only." else "Webbing preview only; physical bungee order/branches are shown in the bungee schematic below. This is not equilibrium.")
        if(state==null)Text("Rig preview · illustrative sag and slack loops, not equilibrium",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(16.dp,4.dp))
        if(backupDrawing?.schematic==true)Text("Backup loops through ring are schematic.",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(16.dp,4.dp))
        if((backupDrawing?.lengthDeficitM?:0.0)>1e-7)Text("Backup is too short to reach this ring position at its calculated length.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(16.dp,4.dp))
    }
    }
}

private fun DrawScope.drawWalker(w:WalkerDrawing,view:Viewport,body:Color,support:Color) {
    fun screen(p:Point):Offset {val q=view.map(p);return Offset(q.x.toFloat(),q.y.toFloat())}
    w.leash?.let{(a,b)->drawLine(support,screen(a),screen(b),2.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),2.dp.toPx())))}
    for((a,b) in w.limbs)drawLine(body,screen(a),screen(b),2.dp.toPx(),cap=StrokeCap.Round)
    drawCircle(body,(w.headRadiusM*view.scale).toFloat(),screen(w.head))
    drawCircle(support,3.dp.toPx(),screen(w.harness))
    val c=screen(w.com);val d=2.dp.toPx()
    drawLine(body,c-Offset(d,0f),c+Offset(d,0f),1.dp.toPx());drawLine(body,c-Offset(0f,d),c+Offset(0f,d),1.dp.toPx())
}

/** Bound the inline list for dense rigs; the overview remains searchable for every joint. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun ConnectionEditLinks(joints:List<LabeledJoint>,onJointTap:((String)->Unit)?,onConnections:(()->Unit)?=null) {
    FlowRow(Modifier.fillMaxWidth().testTag("connection-schematic"),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
        joints.take(8).forEach { l ->
            TextButton(onClick={onJointTap?.invoke(l.joint.id)},contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.testTag("edit-joint-${l.joint.id}")) { Text("Edit ${l.label}") }
        }
    }
    if(joints.size>8)TextButton(onClick={onConnections?.invoke()},modifier=Modifier.testTag("all-graph-connections")){Text("All ${joints.size} connections") }
}

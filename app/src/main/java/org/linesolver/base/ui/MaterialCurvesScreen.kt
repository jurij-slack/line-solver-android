package org.linesolver.base.ui

import android.graphics.Paint
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.linesolver.base.material.*
import org.linesolver.base.material.catalogue.*
import kotlinx.serialization.json.*
import java.util.Locale
import kotlin.math.max

private fun curveColor(index:Int)=Color.hsv((index*137.508f)%360f,.72f,.85f)
private fun label(n:Double)=String.format(Locale.ROOT,"%.3g",n)
private fun evidenceMessage(message:String)=when(message) {
    "nonmonotonic_points"->"Force and strain must both increase strictly; this series cannot define an invertible law."
    "conflicting_source"->"Source evidence conflicts; no imported model is enabled."
    "source_curve_exceeds_declared_strength"->"Source curve exceeds the declared breaking strength; review required."
    else->message.replace('_',' ')
}
@Composable fun MaterialCurvesScreen(repository:MaterialRepository,initialIds:List<String>,onClose:()->Unit) {
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    val numberKeyboard=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=ImeAction.Done)
    val done=KeyboardActions(onDone={focus.clearFocus();keyboard?.hide()})
    val all=remember(repository){repository.all()}
    var ids by rememberSaveable{mutableStateOf(initialIds.distinct())}
    var query by rememberSaveable{mutableStateOf("")}
    var minText by rememberSaveable{mutableStateOf("0")}
    var maxText by rememberSaveable{mutableStateOf("")}
    var detail by rememberSaveable{mutableStateOf<String?>(null)}
    var sourceDetail by rememberSaveable{mutableStateOf<String?>(null)}
    val catalogue=repository as? CatalogueMaterialRepository
    val excluded=remember(catalogue){catalogue?.excludedDatasets.orEmpty()}
    val selected=remember(all,ids){selectedCurves(all,ids)}
    val auto=automaticCurveLimit(selected)
    val error=curveRangeError(minText,if(maxText.isBlank())auto.toString() else maxText)
    val lo=if(error!=null)0.0 else minText.replace(',','.').toDouble()
    val hi=if(maxText.isBlank() || error!=null)auto else maxText.replace(',','.').toDouble()
    val plots=remember(selected,lo,hi){selected.associate{it.id to it.curvePlot(lo,hi)}}
    BackHandler(onBack=onClose)
    Scaffold(topBar={Surface(tonalElevation=2.dp){Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween){
        Text("Material Curves",style=MaterialTheme.typography.headlineSmall)
        TextButton(onClick=onClose,modifier=Modifier.testTag("close-curves")){Text("Close")}
    }}}){padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp).testTag("curve-list"),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=24.dp)) {
            item {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                CurveChart(all,plots,lo,hi)
                LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {items(selected,key={it.id}){m->
                    TextButton(onClick={detail=m.id}){Text("━ ${all.indexOf(m)+1}. ${m.name}",color=curveColor(all.indexOf(m)))}
                }}
                Text("Solid: supplied-data range · dashed: extrapolated or unknown range\n● Source knots · □ Supplied-range boundaries",style=MaterialTheme.typography.bodySmall)
                Text("Knots are database source points (including chart readouts), not necessarily raw measurements. Synthetic origins and fit samples are not marked. Curves stop at the solver limit; continuation is not a validated strength prediction.",style=MaterialTheme.typography.bodySmall)
                if(selected.isEmpty())Text("Select materials below to compare curves.")
                if(selected.size>8)Text("Many curves overlap. Search and select fewer for detailed comparison.")
                plots.filterValues{it.error!=null}.forEach{(id,p)->Text("${repository.get(id).name}: ${p.error}",color=MaterialTheme.colorScheme.error)}
            }}
            item {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(minText,{minText=it},label={Text("Min (%)")},singleLine=true,keyboardOptions=numberKeyboard,keyboardActions=done,modifier=Modifier.weight(1f).testTag("curve-min"))
                    OutlinedTextField(maxText,{maxText=it},label={Text("Max (%) · auto if empty")},singleLine=true,keyboardOptions=numberKeyboard,keyboardActions=done,modifier=Modifier.weight(1f).testTag("curve-max"))
                }
                if(error!=null)Text("$error Showing automatic range until corrected.",color=MaterialTheme.colorScheme.error)
                TextButton(onClick={minText="0";maxText=""},modifier=Modifier.testTag("curve-reset")){Text("Reset range · fit selected")}
                Text("${ids.count{id->all.any{it.id==id}}} / ${all.size} selected · selection changes only this view",modifier=Modifier.testTag("curve-count"))
                Row {TextButton(onClick={ids=all.map{it.id}},modifier=Modifier.testTag("curve-all")){Text("Select All")}
                    TextButton(onClick={ids=emptyList()},modifier=Modifier.testTag("curve-clear")){Text("Clear Selection")}}
                OutlinedTextField(query,{query=it},label={Text("Search materials or sources")},modifier=Modifier.fillMaxWidth().testTag("curve-search"),singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=done)
            }}
            itemsIndexed(all.filter{(it.name+" "+it.id+" "+it.metadata["source_method"]).contains(query,ignoreCase=true)},key={_,m->m.id}){_,m->
                val index=all.indexOf(m)
                Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(m.id in ids,{ids=toggleCurve(ids,m.id)},modifier=Modifier.testTag("curve-select-${m.id}"))
                    Canvas(Modifier.size(24.dp,8.dp)){drawLine(curveColor(index),Offset(0f,size.height/2),Offset(size.width,size.height/2),4.dp.toPx())}
                    Column(Modifier.weight(1f).padding(8.dp).clickable{ids=toggleCurve(ids,m.id)}) {
                        Text("${index+1}. ${m.name}")
                        Text(m.modelLabel(),style=MaterialTheme.typography.bodySmall)
                        m.modelWarning?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                        Text(m.metadata["source_method"]?.jsonPrimitive?.contentOrNull?:"Source unspecified",style=MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick={detail=m.id},modifier=Modifier.testTag("curve-details-${m.id}")){Text("Details")}
                }
            }
            if(excluded.isNotEmpty())item {
                Text("Catalogue and review records",style=MaterialTheme.typography.titleMedium)
                Text("These source datasets have no selectable law. A product may still have a separate curated model above.",style=MaterialTheme.typography.bodySmall)
            }
            items(excluded.filter{(it.source.citation+" "+it.source.note).contains(query,ignoreCase=true)},key={it.id}){dataset->
                val assessment=CatalogueValidation.assess(dataset)
                OutlinedButton(onClick={sourceDetail=dataset.id},modifier=Modifier.fillMaxWidth().testTag("catalogue-${dataset.id}")) {
                    Text("${dataset.source.citation.substringAfter('(').removeSuffix(")")} · ${assessment.disposition.name.replace('_',' ')}")
                }
            }
        }
    }
    detail?.let{id->val m=repository.get(id);val domain=m.suppliedDomain()
        AlertDialog(onDismissRequest={detail=null},title={Text(m.name)},text={Column(Modifier.heightIn(max=450.dp).verticalScroll(rememberScrollState())){
            Text("Model ID: ${m.id}")
            Text(m.modelLabel())
            repository.limitations(m.id).forEach{Text(evidenceMessage(it))}
            catalogue?.sourceEvidence(m.id)?.forEach { dataset ->
                TextButton(onClick={detail=null;sourceDetail=dataset.id}){Text("Source evidence: ${dataset.source.citation}")}
            }
            if(m.mbs==null)Text("Nominal MBS unknown; no breaking strain inferred.")
            Text(if(domain==null)"Supplied range unknown; no solid region or source markers inferred." else "Supplied range: ${label(domain.lower*100)}–${label(domain.upper*100)} %")
            Text("Solver limit: ${label(m.maxStrain*100)} %${if(m.breakingStrain!=null)" (MBS boundary; no post-rupture model)" else " (no supported extension)"}")
            Text("Solver law: tension-only ${if(m.provisionalLinear)"provisional linear" else "PCHIP"}; ${if(m.continuation!=null)"linear continuation above ${label(m.measuredMax*100)} %, bounded by MBS" else "no upper continuation"}.")
            Text(m.extrapolationReason)
            m.metadata["strength_tests"]?.jsonArray?.let { tests ->
                Text("Termination specifications (not selected automatically):")
                tests.forEach { entry ->
                    val t=entry.jsonObject;val name=t["termination"]!!.jsonPrimitive.content.replace('_',' ')
                    val force=t["MBS_N"]?.jsonPrimitive?.doubleOrNull
                    Text("$name: ${if(t["compatible"]?.jsonPrimitive?.booleanOrNull==false)"incompatible" else force?.let{"${label(it/1000)} kN"}?:"unspecified"}")
                }
            }
            if(m.density==null)Text("Linear mass missing: curve can be viewed, but rig calculations are blocked.")
            if(domain!=null && domain.lower>0)Text("Below the first supplied point: solver model toward its imposed origin, shown dashed.")
            for(key in listOf("source_method","source_url","source_note","strength_basis","quality","status","approximate_source","imposed_origin","extrapolation_policy"))m.metadata[key]?.let{Text("${key.replace('_',' ')}: ${if(it is JsonPrimitive)it.content else it}")}
            Text("The supported domain and any continuation are stated above. MBS is not a working-load limit. No pretension offset is applied to relative strain.")
        }},confirmButton={TextButton(onClick={detail=null},modifier=Modifier.testTag("close-curve-details")){Text("Close")}})
    }
    sourceDetail?.let { id ->
        catalogue?.document?.datasets?.singleOrNull{it.id==id}?.let { dataset ->
            AlertDialog(onDismissRequest={sourceDetail=null},title={Text("Catalogue source record")},
                text={Column(Modifier.heightIn(max=450.dp).verticalScroll(rememberScrollState())) {
                    Text(dataset.source.citation)
                    Text(CatalogueValidation.assess(dataset).disposition.name.replace('_',' '))
                    (dataset.conflictFlags+CatalogueValidation.assess(dataset).reasons+dataset.uncertaintyNotes).distinct().forEach{Text(evidenceMessage(it))}
                    catalogue?.product(dataset)?.restrictions?.forEach{Text(it)}
                    Text("Original supplied record (force kN, elongation %, linear mass g/m):")
                    Text(Json{prettyPrint=true}.encodeToString(JsonObject.serializer(),dataset.rawPayload?:JsonObject(emptyMap())))
                }},confirmButton={TextButton(onClick={sourceDetail=null},modifier=Modifier.testTag("close-source-details")){Text("Close")}})
        }
    }
}

@Composable private fun CurveChart(all:List<Webbing>,plots:Map<String,CurvePlot>,low:Double,high:Double) {
    val ink=MaterialTheme.colorScheme.onSurface;val grid=MaterialTheme.colorScheme.outlineVariant
    val ymax=max(.1,plots.values.flatMap{it.segments}.flatMap{it.points}.maxOfOrNull{it.kn}?:1.0)*1.08
    Canvas(Modifier.fillMaxWidth().height(330.dp).testTag("curve-chart").semantics{contentDescription="Relative elongation (%) versus tensile force (kN). ${plots.size} curves. Solid supplied; dashed extrapolated or unknown. Source circles and boundary squares."}) {
        val left=55.dp.toPx();val top=30.dp.toPx();val right=size.width-12.dp.toPx();val bottom=size.height-48.dp.toPx()
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=ink.toArgb();textSize=11.dp.toPx()}
        fun point(p:CurvePoint)=Offset(left+((p.percent-low)/(high-low)*(right-left)).toFloat(),bottom-(p.kn/ymax*(bottom-top)).toFloat())
        drawContext.canvas.nativeCanvas.drawText("T / kN",left,16.dp.toPx(),paint)
        for(i in 0..4){
            val x=left+(right-left)*i/4;val y=bottom-(bottom-top)*i/4
            drawLine(grid,Offset(x,top),Offset(x,bottom));drawLine(grid,Offset(left,y),Offset(right,y))
            paint.textAlign=Paint.Align.RIGHT;drawContext.canvas.nativeCanvas.drawText(label(ymax*i/4),left-5.dp.toPx(),y,paint)
            paint.textAlign=if(i==4)Paint.Align.RIGHT else if(i==0)Paint.Align.LEFT else Paint.Align.CENTER
            drawContext.canvas.nativeCanvas.drawText(label(low+(high-low)*i/4),x,bottom+16.dp.toPx(),paint)
        }
        paint.textAlign=Paint.Align.CENTER
        drawContext.canvas.nativeCanvas.drawText("ε / %",(left+right)/2,size.height-6.dp.toPx(),paint)
        clipRect(left,top,right,bottom){all.forEachIndexed{index,m->plots[m.id]?.let{plot->
            val color=curveColor(index)
            plot.segments.forEach{s->
                val path=Path();s.points.forEachIndexed{i,p->val q=point(p);if(i==0)path.moveTo(q.x,q.y)else path.lineTo(q.x,q.y)}
                drawPath(path,color,style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(),pathEffect=if(s.region==CurveRegion.SUPPLIED)null else PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(),5.dp.toPx()))))
            }
            plot.samples.forEach{drawCircle(color,2.5.dp.toPx(),point(it))}
            plot.boundaries.forEach{val p=point(it);drawRect(color,p-Offset(4.dp.toPx(),4.dp.toPx()),androidx.compose.ui.geometry.Size(8.dp.toPx(),8.dp.toPx()),style=androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))}
        }}}
    }
}

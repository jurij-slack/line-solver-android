package org.linesolver.base.ui

import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.linesolver.base.material.*
import org.linesolver.base.material.catalogue.*

@Composable private fun EditField(label: String, value: String, tag: String = label, change: (String) -> Unit) {
    OutlinedTextField(value,change,label={Text(label)},modifier=Modifier.fillMaxWidth().testTag(tag),minLines=1)
}
@Composable private fun Options(label: String, value: String, options: List<String>, change: (String)->Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick={open=true},modifier=Modifier.testTag("editor-$label")){Text("$label: $value")}
    DropdownMenu(expanded=open,onDismissRequest={open=false}) { options.forEach { v -> DropdownMenuItem(text={Text(v)},onClick={change(v);open=false}) } }
}

@Composable fun MyMaterialsScreen(vm: SetupViewModel, close: () -> Unit) {
    val edit=vm.materialEdit
    val canEdit=DemoBungeePolicy.allowsMaterialEdit(edit.category)
    val repository=vm.materials as CatalogueMaterialRepository
    val document=repository.document
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    val done=KeyboardActions(onDone={focus.clearFocus();keyboard?.hide()})
    val numberKeyboard=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=ImeAction.Done)
    var search by rememberSaveable { mutableStateOf("") }
    var chooseProduct by rememberSaveable { mutableStateOf(false) }
    var productSearch by rememberSaveable { mutableStateOf("") }
    var metadata by rememberSaveable { mutableStateOf(false) }
    var tableText by rememberSaveable { mutableStateOf(false) }
    var importDistinct by rememberSaveable { mutableStateOf(false) }
    var importRevisions by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String,String>?>(null) }
    var exportingCsv by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Pair<EditorPreview,Webbing?>?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    val packageImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.importMaterials(it,importDistinct,vm.materialStore.readError!=null,importRevisions) }
    val packageExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { vm.exportMaterials(it) }
    val csvImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(),vm::importMeasurementCsv)
    val csvExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { vm.exportMaterials(it,exportingCsv) }
    BackHandler(onBack=close) // draft is already persisted, including invalid cells
    LaunchedEffect(edit,repository) {
        preview=null;previewError=null
        if(!canEdit)return@LaunchedEffect
        delay(250)
        try { preview=withContext(Dispatchers.Default) { vm.previewMaterial(edit) } }
        catch(e:IllegalArgumentException) { previewError=e.message }
    }
    val rawRows=remember(edit.table) { runCatching { MeasurementCsv.rows(edit.table) }.getOrDefault(emptyList()) }
    val header=edit.csvHasHeader || runCatching { MeasurementCsv.detect(edit.table)!=null }.getOrDefault(false)
    val offset=if(header)1 else 0
    fun updateRows(rows:List<List<String>>) { vm.editMaterial(edit.copy(table=MeasurementCsv.encodeRows(rows))) }
    Scaffold(topBar={Surface { Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
        Text("My Materials",style=MaterialTheme.typography.headlineSmall)
        TextButton(onClick=close,modifier=Modifier.testTag("close-my-materials")){Text("Close · keep draft")}
    } }}) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal=16.dp).testTag("my-materials-list"),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=32.dp)) {
            item {
                Text("Offline material library. Bundled records are read-only. Editing creates a snapshot; existing rig selections stay pinned.")
                vm.materialMessage?.let { Text(it,modifier=Modifier.testTag("material-message")) }
                if(vm.materialBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick={packageExport.launch("my-materials-v1.json")},enabled=!vm.materialBusy){Text("Export backup JSON")}
                    TextButton(onClick={packageImport.launch(arrayOf("application/json","text/plain"))},enabled=!vm.materialBusy){Text(if(vm.materialStore.readError!=null)"Restore damaged library" else "Import JSON")}
                }
                Row { Checkbox(importDistinct,{importDistinct=it;if(it)importRevisions=false});Text("Import as distinct (new product and record IDs)") }
                Row { Checkbox(importRevisions,{importRevisions=it;if(it)importDistinct=false});Text("Create new revisions on conflicts; keep existing product metadata and all old snapshots") }
                if(!importDistinct && !importRevisions)Text("Import policy: skip identical records; reject conflicts.")
                TextButton(onClick={confirm="new" to "Start a new product? Current edits are kept until you confirm replacing the draft."},modifier=Modifier.testTag("new-material")){Text("New webbing product")}
                TextButton(onClick={vm.editMaterial(MaterialEdit(category=ProductCategory.bungee,bungee=BungeeLaw(basis=BungeeBasis.physical_cord,testedPhysicalLengthM=0.0,source="User-entered measurement")))},enabled=DemoBungeePolicy.available,modifier=Modifier.testTag("new-bungee-material")){Text(if(DemoBungeePolicy.available)"New bungee product" else "New bungee product · Currently unavailable")}
                TextButton(onClick={chooseProduct=true},enabled=canEdit,modifier=Modifier.testTag("link-product")){Text("Add dataset / model to existing product")}
                Text("Editing: ${edit.productId?.let { id -> document.products.firstOrNull { it.id==id }?.name } ?: "New product"}")
                if(canEdit) {
                EditField("Product name",edit.name,"editor-name"){vm.editMaterial(edit.copy(name=it))}
                EditField("Brand (optional)",edit.brand,"editor-brand"){vm.editMaterial(edit.copy(brand=it))}
                if(edit.productId!=null && document.products.any { it.id==edit.productId && !it.id.startsWith("user:") })Text("Bundled name/specifications stay immutable. Your metadata is retained on this dataset/model.")
                TextButton(onClick={metadata=!metadata}){Text(if(metadata)"Hide metadata" else "Product, version, batch and test metadata")}
                } else Text("Bungee material draft retained unchanged. Currently unavailable in this demonstration.",modifier=Modifier.testTag("retained-bungee-material"))
            }
            if(metadata && canEdit) item { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                EditField("Aliases (one per line)",edit.aliases){vm.editMaterial(edit.copy(aliases=it))}
                EditField("Documented version (optional)",edit.version){vm.editMaterial(edit.copy(version=it,versionId=null,batchId=null))}
                document.versions.filter { it.productId==edit.productId }.takeIf { it.isNotEmpty() }?.let { choices ->
                    Options("Existing version",choices.firstOrNull { it.id==edit.versionId }?.label ?: "Unspecified",listOf("Unspecified")+choices.map { "${it.label} · ${it.id.takeLast(8)}" }) { label ->
                        val v=choices.firstOrNull { "${it.label} · ${it.id.takeLast(8)}"==label }
                        vm.editMaterial(edit.copy(versionId=v?.id,version=v?.label.orEmpty(),batchId=null,batch=""))
                    }
                }
                EditField("Batch identifier (user-declared; optional)",edit.batch){vm.editMaterial(edit.copy(batch=it,batchId=null))}
                Row { Checkbox(edit.syntheticBatch,{vm.editMaterial(edit.copy(syntheticBatch=it,batchId=null))});Text("Synthetic example batch (test/documentation only)") }
                document.batches.filter { it.productId==edit.productId && it.versionId==edit.versionId }.takeIf { it.isNotEmpty() }?.let { choices ->
                    Options("Existing batch",choices.firstOrNull { it.id==edit.batchId }?.batchCode ?: "Unspecified",listOf("Unspecified")+choices.map { "${it.batchCode} · ${it.id.takeLast(8)}" }) { label ->
                        val b=choices.firstOrNull { "${it.batchCode} · ${it.id.takeLast(8)}"==label };vm.editMaterial(edit.copy(batchId=b?.id,batch=b?.batchCode.orEmpty()))
                    }
                }
                Text("Unknown batch is unspecified. A different curve does not establish a batch. User declarations are not independently verified.")
                EditField("Composition",edit.composition){vm.editMaterial(edit.copy(composition=it))}
                EditField("Width/mm (optional)",edit.widthMm){vm.editMaterial(edit.copy(widthMm=it))}
                EditField("Notes",edit.notes){vm.editMaterial(edit.copy(notes=it))}
                EditField("Restrictions (one per line)",edit.restrictions){vm.editMaterial(edit.copy(restrictions=it))}
                EditField("Source URL",edit.url){vm.editMaterial(edit.copy(url=it))}
                EditField("License / attribution notice",edit.license){vm.editMaterial(edit.copy(license=it))}
                EditField("Measurement date (YYYY-MM-DD, optional)",edit.date){vm.editMaterial(edit.copy(date=it))}
                EditField("Protocol",edit.protocol){vm.editMaterial(edit.copy(protocol=it))}
                EditField("Conditions",edit.conditions){vm.editMaterial(edit.copy(conditions=it))}
                EditField("Uncertainty / quality notes",edit.uncertainty){vm.editMaterial(edit.copy(uncertainty=it))}
            } }
            if(canEdit) {
            item { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(edit.category==ProductCategory.bungee)BungeeLawFields(edit,vm::editMaterial)
                EditField(if(edit.category==ProductCategory.bungee)"Physical cord reference linear mass kg/m (unknown if blank)" else "Linear mass kg/m (unknown if blank)",edit.mass,"editor-mass"){vm.editMaterial(edit.copy(mass=it))}
                EditField("Nominal material MBS/N (unknown if blank)",edit.mbs,"editor-mbs"){vm.editMaterial(edit.copy(mbs=it))}
                Text("MBS is not a working load. Termination strength is separate and is not entered here.")
                EditField("Source reference",edit.source,"editor-source"){vm.editMaterial(edit.copy(source=it))}
                Options("Origin",edit.origin,listOf("own_measured","manufacturer","derived_from_source","estimated")){vm.editMaterial(edit.copy(origin=it))}
                Options("Branch",edit.branch,listOf("loading","unloading")){vm.editMaterial(edit.copy(branch=it))}
                Options("Force unit",edit.forceUnit,listOf("N","kN")){vm.editMaterial(edit.copy(forceUnit=it))}
                Options("Elongation unit",edit.strainUnit,listOf("%","1","mm","m")){vm.editMaterial(edit.copy(strainUnit=it))}
                if(edit.strainUnit in listOf("mm","m"))EditField("Gauge/reference L0/m",edit.gaugeM,"editor-gauge"){vm.editMaterial(edit.copy(gaugeM=it))}
                TextButton(onClick={csvImport.launch(arrayOf("text/*","application/octet-stream"))}){Text("Import CSV to this dataset draft")}
                EditField("Force column (1-based)",(edit.forceColumn+1).toString()){v->v.toIntOrNull()?.takeIf { it>0 }?.let { vm.editMaterial(edit.copy(forceColumn=it-1)) }}
                EditField("Elongation column (1-based)",(edit.strainColumn+1).toString()){v->v.toIntOrNull()?.takeIf { it>0 }?.let { vm.editMaterial(edit.copy(strainColumn=it-1)) }}
                Text("Confirm units and column mapping before Save. Decimal comma needs semicolon-separated or quoted CSV; grouping separators are unsupported. Source order is preserved.")
                Row { Checkbox(header,{vm.editMaterial(edit.copy(csvHasHeader=it))});Text("First row contains headings (confirm for nonstandard CSV)") }
                TextButton(onClick={tableText=!tableText}){Text("${if(tableText)"Hide" else "Show"} raw CSV / repair malformed cells")}
                if(tableText)EditField("Raw measurement CSV",edit.table,"editor-table"){vm.editMaterial(edit.copy(table=it))}
                Text("Point table · T/${edit.forceUnit} · ${if(edit.strainUnit in listOf("mm","m"))"extension" else "ε"}/${edit.strainUnit}")
            } }
            items((offset until rawRows.size).toList(),key={"point-$it"}) { index ->
                val row=rawRows[index]
                Column {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(row.getOrElse(edit.forceColumn){""},{v->val cells=row.toMutableList();while(cells.size<=maxOf(edit.forceColumn,edit.strainColumn))cells.add("");cells[edit.forceColumn]=v;updateRows(rawRows.mapIndexed{i,r->if(i==index)cells else r})},label={Text("T/${edit.forceUnit}")},singleLine=true,keyboardOptions=numberKeyboard,keyboardActions=done,modifier=Modifier.weight(1f).testTag("point-force-${index-offset}"))
                        OutlinedTextField(row.getOrElse(edit.strainColumn){""},{v->val cells=row.toMutableList();while(cells.size<=maxOf(edit.forceColumn,edit.strainColumn))cells.add("");cells[edit.strainColumn]=v;updateRows(rawRows.mapIndexed{i,r->if(i==index)cells else r})},label={Text("${if(edit.strainUnit in listOf("mm","m"))"extension" else "ε"}/${edit.strainUnit}")},singleLine=true,keyboardOptions=numberKeyboard,keyboardActions=done,modifier=Modifier.weight(1f).testTag("point-strain-${index-offset}"))
                    }
                    Row {
                        TextButton(onClick={updateRows(rawRows.filterIndexed{i,_->i!=index})}){Text("Remove")}
                        TextButton(onClick={val rows=rawRows.toMutableList();java.util.Collections.swap(rows,index,index-1);updateRows(rows)},enabled=index>offset){Text("Up")}
                        TextButton(onClick={val rows=rawRows.toMutableList();java.util.Collections.swap(rows,index,index+1);updateRows(rows)},enabled=index<rawRows.lastIndex){Text("Down")}
                    }
                }
            }
            item { Column {
                TextButton(onClick={updateRows(rawRows+listOf(List(maxOf(edit.forceColumn,edit.strainColumn)+1){""}))},modifier=Modifier.testTag("add-point")){Text("Add point")}
                previewError?.let { Text("Cannot construct preview: $it",color=MaterialTheme.colorScheme.error) }
                preview?.let { (p,m) ->
                    p.problems.forEach { Text(it,modifier=Modifier.testTag("editor-warning")) }
                    val normalized = MeasurementCsv.parse(edit).points
                    normalized.forEachIndexed { i,point -> Text("Parsed row ${i+1}: T/N = ${uiNumber(point.forceN)}, ε/% = ${uiNumber(point.strain*100)} (${point.origin})") }
                    if(m!=null) {
                        DraftCurve(m,p.dataset.points.orEmpty())
                        Text("● Measured observations · ◇ Supplied/derived points · □ Imposed synthetic origin · solid interpolation · dashed imposed/provisional range. No upper extrapolation; ε ≤ ${uiNumber(m.maxStrain*100)} %. Preview calls the solver's native law.",modifier=Modifier.testTag("editor-legend"))
                    } else Text("Review only: no numerical model can be constructed. Rejected rows remain editable.",modifier=Modifier.testTag("editor-no-model"))
                }
                Button(onClick={focus.clearFocus();keyboard?.hide();vm.saveMaterial()},enabled=!vm.materialBusy && vm.materialStore.readError==null,modifier=Modifier.testTag("save-material")){Text("Save dataset / new model revision")}
                Text("Draft edits persist on this device, including invalid cells. Save commits validated records atomically.")
            } }
            }
            item { Column {
                Text("Your saved datasets",style=MaterialTheme.typography.titleMedium)
                EditField("Search your library",search,"my-material-search"){search=it}
            } }
            val entries=vm.materialStore.value.catalogue.datasets.filter { d ->
                val p=document.products.firstOrNull{it.id==d.productId};listOf(p?.name,p?.manufacturer,d.source.citation,d.id).any { it?.contains(search,true)==true }
            }
            items(entries,key={it.id}) { d ->
                val p=document.products.first { it.id==d.productId };val models=vm.materialStore.value.catalogue.models.filter { d.id in it.datasetIds }
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text("${p.name} · ${d.source.citation} · #${d.id.takeLast(8)}")
                    Text("${document.versions.firstOrNull{it.id==d.versionId}?.label ?: "Unspecified version"} · ${document.batches.firstOrNull{it.id==d.batchId}?.batchCode ?: "Unspecified batch"}")
                    TextButton(onClick={confirm="edit:${d.id}" to "Replace the draft with this saved dataset? The saved source snapshot remains immutable."},enabled=DemoBungeePolicy.allowsMaterialEdit(p.category)){Text(if(DemoBungeePolicy.allowsMaterialEdit(p.category))"Edit / duplicate as revision" else "Bungee editing · Currently unavailable")}
                    TextButton(onClick={exportingCsv=d.id;csvExport.launch("measurement-${d.id.takeLast(8)}.csv")}){Text("Export measurement CSV")}
                    models.forEach { m ->
                        Text("Revision ${m.revision} · ${m.type} · ${m.sourceQuality} · #${m.id.takeLast(8)}${if(m.id in vm.materialStore.value.retiredModelIds)" · retired" else ""}")
                        TextButton(onClick={confirm="retire:${m.id}" to "Retire this model from new selections? Its curve and dataset will remain retrievable by saved rigs and included in backups."},enabled=DemoBungeePolicy.allowsMaterialEdit(p.category)){Text("Retire model")}
                    }
                    if(models.isEmpty())TextButton(onClick={confirm="delete:${d.id}" to "Delete this unreferenced review dataset? This cannot be undone without a backup."},enabled=DemoBungeePolicy.allowsMaterialEdit(p.category)){Text("Delete dataset")}
                } }
            }
            items(vm.materialStore.value.catalogue.products.filter { it.name.contains(search,true) },key={"product-${it.id}"}) { p ->
                TextButton(onClick={confirm="delete-product:${p.id}" to "Delete ${p.name}? This is allowed only when no dataset or immutable model depends on it. Export a backup first if needed."},enabled=DemoBungeePolicy.allowsMaterialEdit(p.category)) { Text("Delete unreferenced product: ${p.name}") }
            }
        }
    }
    if(chooseProduct && canEdit)AlertDialog(onDismissRequest={chooseProduct=false},title={Text("Link to existing material")},text={Column {
        EditField("Search name, brand or alias",productSearch){productSearch=it}
        LazyColumn(Modifier.heightIn(max=360.dp)) {
            items(document.products.filter { it.category==edit.category && listOf(it.name,it.manufacturer.orEmpty(),it.aliases.joinToString()).any { text->text.contains(productSearch,true) } },key={it.id}) { p ->
                TextButton(onClick={chooseProduct=false;confirm="product:${p.id}" to "Replace this draft with a new dataset for ${p.name}? Bundled records remain read-only."}){Text("${p.manufacturer.orEmpty()} ${p.name} · #${p.id.takeLast(8)}")}
            }
        }
    }},confirmButton={TextButton(onClick={chooseProduct=false}){Text("Cancel")}})
    confirm?.let { (action,message) -> AlertDialog(onDismissRequest={confirm=null},title={Text("Confirm material action")},text={Text(message)},confirmButton={TextButton(onClick={
        when {
            action=="new" -> vm.editMaterial(MaterialEdit())
            action.startsWith("product:") -> { val p=document.products.single{it.id==action.removePrefix("product:")};vm.editMaterial(MaterialEdit(productId=p.id,name=p.name,brand=p.manufacturer.orEmpty(),mass=p.specifications.densityKgPerM?.toString().orEmpty(),mbs=p.specifications.mbsN?.toString().orEmpty(),restrictions=p.restrictions.joinToString("\n"))) }
            action.startsWith("edit:") -> { val d=document.datasets.single{it.id==action.removePrefix("edit:")};val model=document.models.lastOrNull{d.id in it.datasetIds};vm.editMaterial(MaterialEditing.load(document,d,model?.id));metadata=true }
            action.startsWith("retire:") -> vm.retireMaterial(action.removePrefix("retire:"))
            action.startsWith("delete:") -> vm.deleteMaterialDataset(action.removePrefix("delete:"))
            action.startsWith("delete-product:") -> vm.deleteMaterialProduct(action.removePrefix("delete-product:"))
        };confirm=null
    },modifier=Modifier.testTag("confirm-material-action")){Text("Confirm")}},dismissButton={TextButton(onClick={confirm=null}){Text("Cancel")}}) }
}

@Composable private fun DraftCurve(model:Webbing,points:List<MeasurementPoint>) {
    val plot=remember(model){model.curvePlot(0.0,model.maxStrain*100)}
    val ink=MaterialTheme.colorScheme.onSurface;val line=MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(240.dp).testTag("editor-preview")) {
        val left=48.dp.toPx();val bottom=size.height-32.dp.toPx();val right=size.width-12.dp.toPx();val top=24.dp.toPx()
        val ymax=(plot.segments.flatMap{it.points}.maxOfOrNull{it.kn} ?: 1.0).coerceAtLeast(.001)*1.1
        fun q(e:Double,f:Double)=Offset(left+(e/model.maxStrain*(right-left)).toFloat(),bottom-(f/1000/ymax*(bottom-top)).toFloat())
        val paint=Paint().apply{color=ink.toArgb();textSize=12.dp.toPx()}
        drawContext.canvas.nativeCanvas.drawText("T/kN",left,16.dp.toPx(),paint)
        drawContext.canvas.nativeCanvas.drawText("ε/% · 0–${significant(model.maxStrain*100)}",left,bottom+24.dp.toPx(),paint)
        drawLine(ink,Offset(left,top),Offset(left,bottom));drawLine(ink,Offset(left,bottom),Offset(right,bottom))
        plot.segments.forEach { s -> val path=Path();s.points.forEachIndexed { i,p -> val pt=q(p.percent/100,p.kn*1000);if(i==0)path.moveTo(pt.x,pt.y)else path.lineTo(pt.x,pt.y) }
            drawPath(path,line,style=Stroke(2.dp.toPx(),pathEffect=if(s.region==CurveRegion.SUPPLIED && !model.provisionalLinear)null else PathEffect.dashPathEffect(floatArrayOf(8f,6f)))) }
        points.forEach { p -> val pt=q(p.strain,p.forceN)
            if(p.origin==PointOrigin.measured)drawCircle(ink,4.dp.toPx(),pt) else { val diamond=Path().apply{moveTo(pt.x,pt.y-5);lineTo(pt.x+5,pt.y);lineTo(pt.x,pt.y+5);lineTo(pt.x-5,pt.y);close()};drawPath(diamond,ink,style=Stroke(2f)) }
        }
        if(model.hasImposedOrigin)drawRect(Color.Gray,q(0.0,0.0)-Offset(4f,4f),androidx.compose.ui.geometry.Size(8f,8f),style=Stroke(2f))
    }
}

package org.linesolver.base.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import org.linesolver.base.solver.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.linesolver.base.R
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.linesolver.base.model.*
import org.linesolver.base.contact.ConnectionBehavior
import org.linesolver.base.material.MaterialRepository
import org.linesolver.base.material.catalogue.modelLabel
import org.linesolver.base.material.catalogue.modelChoiceLabel
import org.linesolver.base.energy.Cable
import org.linesolver.base.energy.bungeeResults
import java.util.Locale
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

fun pretty(n:Double)=uiNumber(n)
@Composable private fun Group(title:String,content:@Composable ColumnScope.()->Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium);content()
    } }
}
@Composable private fun NumberField(label:String,value:String,tag:String,change:(String)->Unit) {
    OutlinedTextField(value,change,label={Text(label)},modifier=Modifier.fillMaxWidth().testTag(tag),singleLine=true,
        isError=value.isNotBlank() && value.number()==null,
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
}
@Composable private fun Choose(label:String,value:String,options:List<Pair<String,String>>,tag:String,enabled:Boolean=true,onChoice:(String)->Unit) {
    var open by remember{mutableStateOf(false)}
    var query by remember{mutableStateOf("")}
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    OutlinedButton(onClick={focus.clearFocus();keyboard?.hide();open=true},enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(tag)) {
        Text(if(value.isBlank()) "Choose $label" else value)
    }
    if(open) AlertDialog(onDismissRequest={open=false},title={Text("Choose $label")},text={
        Column {
            if(options.size>10)OutlinedTextField(query,{query=it},label={Text("Search $label")},singleLine=true,modifier=Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max=380.dp)) {
                items(options.filter { it.second.contains(query,true) || it.first.contains(query,true) },key={it.first}) { (id,name) ->
                    TextButton(onClick={onChoice(id);open=false;query=""},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("$tag-option-$name")){Text(name)}
                }
            }
        }
    },confirmButton={TextButton(onClick={open=false}){Text("Cancel")}})
}
@Composable private fun MaterialPicker(title:String,selection:Selection,materials:MaterialRepository,tag:String,onChange:(Selection)->Unit) {
    Text(title,style=MaterialTheme.typography.labelLarge)
    Choose("manufacturer",selection.manufacturer,materials.manufacturers().map{it to it},"$tag-manufacturer") {onChange(Selection(it,""))}
    val selected=runCatching{materials.get(selection.materialId).product}.getOrDefault("")
    val productChoices=materials.webbings(selection.manufacturer)
    Choose("webbing",selected,productChoices.map{m->m.id to if(productChoices.count{it.product.equals(m.product,true)}>1)"${m.product} · #${m.id.takeLast(8)}" else m.product},"$tag-webbing",selection.manufacturer.isNotBlank()) {onChange(selection.copy(materialId=it))}
    if(selection.materialId.startsWith("user:") && runCatching{materials.get(selection.materialId)}.isFailure)
        Text("Unresolved model ${selection.materialId}. Restore its exact revision with My Materials → Import JSON. No default was substituted.",color=MaterialTheme.colorScheme.error)
    runCatching{materials.get(selection.materialId)}.getOrNull()?.let { model ->
        val alternatives=materials.alternatives(model.id)
        if(alternatives.size>1)Choose("model",model.modelChoiceLabel(),
            alternatives.map{it.id to it.modelChoiceLabel()},
            "$tag-model") {onChange(Selection(materials.get(it).manufacturer,it))}
        else Text(if(model.id.startsWith("user:"))model.modelChoiceLabel() else model.modelLabel(),style=MaterialTheme.typography.bodySmall)
        model.modelWarning?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
        if(model.id.startsWith("user:")) {
            Text("Source: ${model.metadata["source_citation"]?.jsonPrimitive?.contentOrNull} · dataset #${model.metadata["dataset_id"]?.jsonPrimitive?.contentOrNull?.takeLast(8)}",style=MaterialTheme.typography.bodySmall)
            materials.limitations(model.id).filter { it != model.modelWarning }.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
        }
        val limitations=materials.limitations(model.id)
        limitations.filter{it.contains("NOT FOR HIGHLINE") || it.startsWith("Historical NOVA")}.forEach {
            Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        }
        if(limitations.any{it.startsWith("Source handling") || it.startsWith("Not compatible")})
            Text("Manufacturer handling restrictions apply. See Material Curves → Details.",style=MaterialTheme.typography.bodySmall)
    }
}
@Composable fun BaseScreen(vm:SetupViewModel) {
    val screen by vm.state.collectAsStateWithLifecycle();val d=screen.draft
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    var results by rememberSaveable{mutableStateOf(false)};var info by rememberSaveable{mutableStateOf(false)}
    var moreOpen by remember{mutableStateOf(false)}
    var curvesOpen by rememberSaveable{mutableStateOf(false)}
    var myMaterialsOpen by rememberSaveable{mutableStateOf(false)}
    var settingsOpen by rememberSaveable{mutableStateOf(false)}
    var connectionsOpen by rememberSaveable{mutableStateOf(false)}
    var selectedJoint by rememberSaveable{mutableStateOf<String?>(null)}
    var replaceUniform by remember{mutableStateOf(false)}
    var details by rememberSaveable{mutableStateOf(false)}
    var failureSection by remember{mutableStateOf<Int?>(null)}
    var mixedStart by remember{mutableStateOf(false)}
    var sectionEditor by rememberSaveable{mutableIntStateOf(0)}
    var replaceHidden by remember{mutableStateOf(false)}
    fun mixed() { if(d.uniform==null && d.sections.size>1 && d.kind==SetupKind.UNIFORM)replaceHidden=true else vm.convertToMixed() }
    var stage by rememberSaveable{mutableIntStateOf(0)}
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"),vm::exportTo)
    fun exportReport() {
        vm.prepareExport(listOf("standing","catch","rest")[stage.coerceIn(0,2)])
        val stamp=java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        try { exporter.launch("linesolver_debug_$stamp.json") } catch(e:Exception) { vm.exportFailed() }
    }
    val editorScroll=rememberScrollState();val resultScroll=rememberScrollState()
    LaunchedEffect(screen.result){if(screen.result!=null){stage=if(screen.result?.catch!=null)1 else 0;resultScroll.scrollTo(0);results=true}}
    val validation=remember(d,vm.materials){d.validate(vm.materials)}
    if(myMaterialsOpen) { MyMaterialsScreen(vm){myMaterialsOpen=false};return }
    if(info){AboutScreen{info=false};return}
    if(curvesOpen) {
        MaterialCurvesScreen(vm.materials,d.materialSections.flatMap{listOf(it.main.materialId,it.backup.materialId)}.filter{it.isNotBlank()}){curvesOpen=false}
        return
    }
    if(details && screen.result!=null) {
        BackHandler{details=false}
        val result=screen.result!!
        val states=listOfNotNull(result.standing,result.catch,result.rest)
        val state=states[stage.coerceAtMost(states.lastIndex)]
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            TextButton(onClick={details=false},modifier=Modifier.testTag("close-details")){Text("Back to Results")}
            Text("Detailed Results",style=MaterialTheme.typography.headlineSmall)
                Group("Computed state") {
                    for(kind in Cable.entries) {
                        val peak=state.network.elements.indices.filter{state.network.elements[it].cable==kind}.maxByOrNull{state.value.tensions[it]}
                        if(peak!=null)Text("${state.forceLabel(kind)}: ${pretty(state.value.tensions[peak]/1000)} kN · section ${state.network.elements[peak].section+1}")
                    }
                    Text("Mass coordinate: ${state.network.attachment!!.support.name.lowercase()} · ${if(stage==0)"standing equilibrium" else if(stage==1)"energy turning configuration" else "relaxed static equilibrium"}")
                    Text("Spatial maxima in this state; not historical or dynamic peaks.")
                    Text("Line attachment: x ${pretty(state.attachmentPosition!!.x)} m, y ${pretty(state.attachmentPosition!!.y)} m")
                    state.walkerElevations?.let{Text("Walker COM: y ${pretty(it.comM)} m · ${if(state.network.posture==WalkerPosture.STANDING) "standing" else "leash supported"}")}
                    Text("Installed main: ${pretty(result.installedLengths.sum())} m")
                    Text("Installed pretension: ${pretty(result.achievedPretensionN/1000)} kN")
                    Text("Free force residual: ${String.format(Locale.ROOT,"%.3g",state.residualN)} N")
                    if(d.cutEnabled)Text(if(stage>0)"Prescribed main cut applied. Backup remains connected; material labels stay fixed." else "Before cut: standing on the mainline; no added structural connection at the leash.")
                }
                if(result.catch!=null)Group("Energy · fixed-label catch") {
                    Text("Initial: ${energyKj(result.initialEnergyJ!!)}")
                    Text("Catch: ${energyKj(result.catch.value.total)}")
                    Text("Energy residual: ${String.format(Locale.ROOT,"%.3g",result.catchResidualJ)} J")
                    Text("Ring sliding friction: 0 J (no redistribution)")
                    result.fallEnergy?.let{ledger->
                        Text("COM y: ${pretty(ledger.initial.walkerComYM)} → ${pretty(ledger.turning.walkerComYM)} m")
                        Text("Walker gravity released: ${energyKj(ledger.walkerGravityReleasedJ)}")
                        Text("Webbing gravity released: ${energyKj(ledger.webbingGravityReleasedJ)}")
                        if(state.network.connectorMasses.isNotEmpty())Text("Connector gravity released: ${energyKj(ledger.connectorGravityReleasedJ)}")
                        Text("Main elastic: ${energyKj(ledger.initial.mainElasticJ)} → ${energyKj(ledger.turning.mainElasticJ)}")
                        Text("Backup elastic: ${energyKj(ledger.initial.backupElasticJ)} → ${energyKj(ledger.turning.backupElasticJ)}")
                    }
                    Text("Potential released from catch to rest: ${energyKj(result.relaxationLossJ!!)}")
                }
                Group("Model scope") {
                    result.warnings.forEach{Text(displayWarning(it))}
                    result.materialNotices().forEach{Text(it.details())}
                    Text("OK means the calculation completed within its material domain. No anchor rating or engineering safety margin is inferred. Dynamic peak-force validation is not established.")
                }
            Group("Connectors") {
                if(d.hasInactiveConnectionMetadata())Text(CONNECTION_COMPATIBILITY_WARNING)
                Text(if(d.modelConnections)"Known active connector masses are included; individual sharing is not inferred." else "Optional connector mass and strength checks are off.")
                StructuralConnections.labeled(d).filter{it.joint.details.hardware.isNotEmpty()}.forEach { l ->
                    Text("${l.label} · ${connectionComponentNames(d,l.joint).joinToString(" ↔ ")}")
                    regularConnectorItems(l.joint).forEach { h ->
                        Text("${h.role}: "+(runCatching{ConnectorLibrary.resolve(h)?.name}.getOrNull() ?: "Unspecified hardware"))
                        Text(simpleHardwareSummary(h))
                    }
                }
                state.connectorDiagnostics(d).forEach{item->
                    Text("${item.name} · ${item.role}: ${item.assumedLoad.status} (${item.assumedLoad.source})\n${displayWarning(item.comparison)}\n${displayWarning(item.warning)}")
                }
            }
            if(!d.bungees?.banks.isNullOrEmpty())Group("Bungees") {
                state.bungeeResults().forEach{b->
                    Text("${b.id}: length ${lengthM(b.pathLengthM)} · strain ${uiNumber(b.strain*100)} %")
                    Text("Rubber ${forceKn(b.rubberMinN)}–${forceKn(b.rubberMaxN)} · energy ${energyKj(b.elasticJ)} · mass ${uiNumber(b.massKg)} kg")
                    Text("Limiter ${if(b.limiterActive)"active" else "slack / off"}; reaction ${b.limiterReactionN?.let(::forceKn) ?: "undetermined"}; strength unknown")
                }
                Text("Limiter violation: ${String.format(Locale.ROOT,"%.3g",state.constraintViolationM)} m")
                val forces=state.totalGradient()
                for((label,node) in listOf("Left" to state.network.splits.first(),"Right" to state.network.splits.last()))Text("$label anchor reaction: ${forceKn(kotlin.math.hypot(forces[2*node],forces[2*node+1]))}")
            }
            Group("Numerical diagnostics") {
                Text("Attachment: ${state.attachmentPosition?.let{lengthM(it.x)+", "+lengthM(it.y)}}\nTotal energy: ${energyKj(state.value.total)}\nElastic energy: ${energyKj(state.value.elastic)}\nForce residual: ${String.format(Locale.ROOT,"%.3g",state.residualN)} N\nIterations: ${state.iterations}")
                Text("Gravity: ${d.gravity} m/s² · mesh: ${d.elements} elements/section · resistance: ${d.resistance} N (inactive in Simple)")
                Text("Ring: fixed material labels, ideal zero-size contact. Full precision and all installed item records remain in Export Diagnostics.")
                Cable.entries.forEach { c->state.ringReaction(c)?.let{Text("${c.name} ring reaction: x ${forceKn(it.x)}, upward ${forceKn(it.y)}")} }
                state.sectionForces().forEach{Text("Section ${it.section+1} ${it.cable}: ${forceKn(it.maximumN)}")}
            }
            TextButton(onClick={exportReport()}){Text("Export Diagnostics")}
        }
        return
    }
    Scaffold(topBar={Surface(tonalElevation=2.dp){Row(Modifier.fillMaxWidth().statusBarsPadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)){Text(stringResource(R.string.app_name),style=MaterialTheme.typography.labelLarge);Text("${if(d.mode==Mode.SIMPLE)"Simple" else "Dynamics"} · ${if(results && screen.result!=null) "Results" else "Setup"}",style=MaterialTheme.typography.headlineSmall)}
        Row {
            IconButton(onClick={settingsOpen=true},modifier=Modifier.testTag("solver-settings")) {
                Icon(painterResource(R.drawable.ic_settings),contentDescription="Solver Settings")
            }
            Box {
                TextButton(onClick={moreOpen=true},modifier=Modifier.testTag("more-menu")){Text("More")}
                DropdownMenu(expanded=moreOpen,onDismissRequest={moreOpen=false}) {
                    DropdownMenuItem(text={Text("Material Curves")},onClick={moreOpen=false;curvesOpen=true},modifier=Modifier.testTag("material-curves"))
                    DropdownMenuItem(text={Text("My Materials")},onClick={moreOpen=false;myMaterialsOpen=true},modifier=Modifier.testTag("my-materials"))
                    DropdownMenuItem(text={Text("About")},onClick={moreOpen=false;info=true},modifier=Modifier.testTag("about-menu"))
                }
            }
        }
    }}},bottomBar={Surface(shadowElevation=6.dp){Column(Modifier.navigationBarsPadding().imePadding().padding(12.dp)) {
        if(screen.busy){LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick=vm::cancel,modifier=Modifier.fillMaxWidth()){Text("Cancel calculation")}}
        else if(results && screen.result!=null)Button(onClick={results=false},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("Edit rig")}
        else Button(onClick={focus.clearFocus();keyboard?.hide();vm.solve()},enabled=validation.setup!=null && d.mode==Mode.SIMPLE && !DemoBungeePolicy.blocks(d),modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("solve")) {
            Text(if(d.mode==Mode.ADVANCED)"Dynamics · not yet available" else if(d.cutEnabled)"Solve static fall" else "Solve equilibrium")
        }
    }}}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(if(results && screen.result!=null)resultScroll else editorScroll).padding(16.dp).testTag("editor"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(!(results && screen.result!=null) && (d.modelConnections || d.connections?.detached?.isNotEmpty()==true))TextButton(onClick={connectionsOpen=true},modifier=Modifier.testTag("connection-overview")){Text(if(d.connections?.detached?.isNotEmpty()==true)"Connections · review retained records" else "Connections")}
            if(results && screen.result!=null) {
                val result=screen.result!!
                Text(if(screen.issue==null)"Calculation successful" else "Calculation failed · retained result shown",style=MaterialTheme.typography.titleSmall,modifier=Modifier.testTag("result-status"))
                val states=listOfNotNull(result.standing,result.catch,result.rest)
                val state=states[stage.coerceAtMost(states.lastIndex)]
                ResultStateControls(states.size,stage,{stage=it},result.catch!=null || d.cutEnabled,vm::resetFailure)
                WalkerPositionControl(d,result.installedLengths.sum(),onChange={},onFinished=vm::moveWalker,
                    onJointTap={selectedJoint=it;connectionsOpen=true},onConnections={selectedJoint=null;connectionsOpen=true},
                    state=state,standing=result.standing,resultMode=listOf("Standing","Catch","Rest")[stage.coerceIn(0,2)],
                    failureApplied=result.catch!=null || d.cutEnabled,selectedSection=failureSection,
                    onSectionTap=if(result.catch==null && !d.cutEnabled && d.bungees?.banks.isNullOrEmpty())({failureSection=it}) else null)
                CompactResults(state,d)
                if(stage==1)Text("Catch is an energy-based turning-state estimate, not a time-resolved dynamic simulation.",style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("catch-scope"))
                if(result.catch==null && !d.cutEnabled && d.bungees?.banks.isNullOrEmpty())Choose("failure section","Apply mainline failure",result.installedLengths.indices.map{it.toString() to "Section ${it+1}"},"failure-section") {failureSection=it.toInt()}
                val notices=remember(result){result.materialNotices()}
                MaterialWarningInformation(notices)
                val warnings=remember(result){result.warnings.distinct()}
                if(warnings.isNotEmpty())InformationDisclosure("Solver warnings (${warnings.size})","solver-warnings") {warnings.forEach{Text(displayWarning(it))}}
                InformationDisclosure("Model information","model-information") {
                    Text(if(d.modelConnections)"Structural joints are ideal; known connector masses are included. Connector-specific force distribution is not evaluated." else "Structural joints are ideal. Optional connector masses and strength checks are omitted.",style=MaterialTheme.typography.bodySmall)
                    if(d.hasInactiveConnectionMetadata())Text(CONNECTION_COMPATIBILITY_WARNING,style=MaterialTheme.typography.bodySmall)
                    if(d.connections?.joints?.any{!simpleConnectionEditable(it)}==true)Text("Saved assemblies and their active masses are retained unchanged.",style=MaterialTheme.typography.bodySmall)
                    Text("Displayed forces are spatial maxima for the selected state, not dynamic time-history peaks.",style=MaterialTheme.typography.bodySmall)
                    if(!d.bungees?.banks.isNullOrEmpty())Text("Bungees remain active. Catch/failure is unavailable because transient limiter engagement is not modeled.",style=MaterialTheme.typography.bodySmall)
                }
                screen.issue?.let{issue->InformationDisclosure("Calculation message","calculation-message"){Text(displayWarning(issue.message))}}
                OutlinedButton(onClick={details=true},modifier=Modifier.fillMaxWidth().testTag("detailed-results")){Text("Detailed Results")}

            } else {
                RigLibraryControls(vm)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    for(mode in Mode.entries)FilterChip(d.mode==mode,{vm.edit{it.copy(mode=mode)}},label={Text(if(mode==Mode.SIMPLE)"Simple" else "Dynamics")},modifier=Modifier.testTag("mode-${mode.name}"))
                }
                Text(if(d.mode==Mode.SIMPLE)"Static equilibrium / fall · no webbing sliding" else "Same rig · contacts and sliding (port pending)",style=MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    for(kind in SetupKind.entries)FilterChip(d.kind==kind,{
                        if(kind!=d.kind) {
                            if(kind==SetupKind.UNIFORM) { if(!vm.switchToUniform())replaceUniform=true }
                            else if(d.uniform?.errors(d.span).isNullOrEmpty())mixed() else mixedStart=true
                        }
                    },label={Text(if(kind==SetupKind.UNIFORM)"Uniform" else "Mixed")},modifier=Modifier.testTag("setup-${kind.name}"))
                }
                WalkerPositionControl(d,d.activeSections.sumOf{it.length.number()?:0.0},onChange={v->vm.edit{it.copy(position=v.toString())}},onJointTap={selectedJoint=it;connectionsOpen=true},onConnections={selectedJoint=null;connectionsOpen=true})
                Group("Geometry") {
                    NumberField("Horizontal span (m)",d.span,"span"){v->vm.edit{it.copy(span=v)}}
                    NumberField("Left anchor height (m)",d.leftHeight,"left-height"){v->vm.edit{it.copy(leftHeight=v)}}
                    NumberField("Right anchor height (m)",d.rightHeight,"right-height"){v->vm.edit{it.copy(rightHeight=v)}}
                    Text("Use one height datum for both anchors. Enter 0 explicitly for a level datum.",style=MaterialTheme.typography.bodySmall)
                }
                Group("Main installation") {
                    NumberField("Main pretension (kN)",d.pretensionKn,"pretension"){v->vm.edit{it.copy(pretensionKn=v)}}
                    Text("Right-hand tensioning. Only the final prepared section is shortened; internal material splits are retained.",style=MaterialTheme.typography.bodySmall)
                }
                val sections=d.activeSections
                if(d.kind==SetupKind.UNIFORM && d.uniform!=null)Group("Uniform sections") {
                    val generator=d.uniform
                    NumberField("Number of sections (1–100)",generator.count,"uniform-count"){v->vm.generator{it.copy(count=v)}}
                    MaterialPicker("MAIN · all sections",generator.main,vm.materials,"main-0"){v->vm.generator{it.copy(main=v)}}
                    MaterialPicker("BACKUP · all sections",generator.backup,vm.materials,"backup-0"){v->vm.generator{it.copy(backup=v)}}
                    NumberField("Backup extra ${if(generator.extraUnit==ExtraUnit.PERCENT) "(%)" else "(m total)"}",generator.extra,"extra-0"){v->vm.generator{it.copy(extra=v)}}
                    Row { ExtraUnit.entries.forEach{unit->FilterChip(generator.extraUnit==unit,{vm.generator{it.withExtraUnit(unit,d.span)}},
                        label={Text(if(unit==ExtraUnit.PERCENT)"%" else "m total")},modifier=Modifier.testTag("uniform-extra-${unit.name}"))} }
                    if(d.mode==Mode.ADVANCED) {
                        Text("All internal connections (future sliding)")
                        Row {ConnectionBehavior.entries.forEach{b->FilterChip(generator.connection==b,{vm.generator{it.copy(connection=b)}},label={Text(b.name.lowercase())})}}
                    }
                    Text("Prepared main length equals span, divided equally. Metres of backup extra are shared across all sections; percent is the local ratio in each section. During tensioning, backup cuts scale with active main lengths.",style=MaterialTheme.typography.bodySmall)
                    if(sections.isNotEmpty())Text("${sections.size} sections × ${pretty(sections.first().length.number()!!)} m · ${sections.size-1} internal splits",modifier=Modifier.testTag("uniform-summary"))
                    Text("Split locations in the schematic are nominal. Their solved positions can move.",style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick={mixed()},enabled=generator.errors(d.span).isEmpty(),modifier=Modifier.testTag("edit-individual-sections")){Text("Edit Individual Sections")}
                }
                if(d.kind==SetupKind.UNIFORM && d.uniform==null)Group("Legacy Uniform rig") {
                    Text("Original prepared length is preserved. Use the generator to divide the span into equal prepared sections.")
                    OutlinedButton(onClick={if(!vm.switchToUniform())replaceUniform=true}){Text("Use section generator")}
                    OutlinedButton(onClick={mixed()},modifier=Modifier.testTag("edit-individual-sections")){Text("Edit Individual Sections")}
                }
                if(d.uniform==null && sections.size>12)Choose("section to edit","Section ${sectionEditor.coerceAtMost(sections.lastIndex)+1}",sections.indices.map{it.toString() to "Section ${it+1}"},"section-editor"){sectionEditor=it.toInt()}
                if(d.uniform==null)sections.forEachIndexed { i,s -> if(sections.size<=12 || i==sectionEditor.coerceAtMost(sections.lastIndex))Group(if(d.kind==SetupKind.UNIFORM)"Main + backup" else "Section ${i+1}") {
                    NumberField("Prepared main length (m)",s.length,"length-$i"){v->vm.section(i){it.copy(length=v)}}
                    MaterialPicker("MAIN",s.main,vm.materials,"main-$i"){v->vm.section(i){it.copy(main=v)}}
                    HorizontalDivider()
                    MaterialPicker("BACKUP",s.backup,vm.materials,"backup-$i"){v->vm.section(i){it.copy(backup=v)}}
                    NumberField("Backup extra ${if(s.extraUnit==ExtraUnit.PERCENT) "(%)" else "(m per prepared section)"}",s.extra,"extra-$i"){v->vm.section(i){it.copy(extra=v)}}
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {for(unit in ExtraUnit.entries)FilterChip(s.extraUnit==unit,{
                        vm.section(i){it.withExtraUnit(unit)}
                    },label={Text(if(unit==ExtraUnit.PERCENT)"%" else "m")})}
                    Text("Extra is converted to the section's local ratio. The backup cut scales with active main length during installation, then stays locked.",style=MaterialTheme.typography.bodySmall)
                    if(d.kind==SetupKind.MIXED && i<sections.lastIndex && d.mode==Mode.ADVANCED) {
                        Text("Connection to section ${i+2} (future sliding)")
                        Row {ConnectionBehavior.entries.forEach{b->FilterChip(s.connection==b,{vm.section(i){it.copy(connection=b)}},label={Text(b.name.lowercase())})}}
                    }
                    if(d.kind==SetupKind.MIXED && sections.size>1)Row {
                        if(i>0)TextButton(onClick={vm.edit{rig->val ordered=rig.sections.toMutableList();val item=ordered.removeAt(i);ordered.add(i-1,item);rig.copy(sections=ordered)}}){Text("Move section earlier")}
                        if(i<sections.lastIndex)TextButton(onClick={vm.edit{rig->val ordered=rig.sections.toMutableList();val item=ordered.removeAt(i);ordered.add(i+1,item);rig.copy(sections=ordered)}}){Text("Move section later")}
                    }
                    if(d.kind==SetupKind.MIXED && sections.size>1)TextButton(onClick={vm.edit{it.copy(sections=it.sections.filterIndexed{j,_->j!=i})}}){Text("Remove section")}
                } }
                if(d.kind==SetupKind.MIXED)OutlinedButton(onClick={vm.edit{it.copy(sections=it.sections+SectionDraft())}},enabled=sections.size<100,modifier=Modifier.fillMaxWidth().testTag("add-section")){Text("Add section")}
                if(DemoBungeePolicy.available && d.showBungeeOptions)BungeeEditor(vm,d){myMaterialsOpen=true}
                else if(!d.bungees?.banks.isNullOrEmpty()) {
                    if(DemoBungeePolicy.blocks(d))Text(DemoBungeePolicy.retainedMessage,modifier=Modifier.testTag("hidden-bungee-summary"))
                    else {
                        Text("This rig contains bungees — enable Show bungee options in Solver Settings to edit. Existing bungees and limiters remain active.",modifier=Modifier.testTag("hidden-bungee-summary"))
                        d.bungees!!.banks.forEach{bank->Text("${bank.cable} · ${bank.placement} · ${bank.assemblies.size} physical bungee(s)")}
                        TextButton(onClick={settingsOpen=true}){Text("Open bungee settings")}
                    }
                }
                Group("Walker") {
                    NumberField("Walker mass (kg)",d.mass,"mass"){v->vm.edit{it.copy(mass=v)}}
                    NumberField("Walker body height (m)",d.walkerHeight,"walker-height"){v->vm.edit{it.copy(walkerHeight=v)}}
                    NumberField("Leash: ring to harness (m)",d.leash,"leash"){v->vm.edit{it.copy(leash=v)}}
                    Text("Starts standing: COM at 55% and harness at 50% of body height above the mainline. The engaged leash is ideal, inextensible and vertical; length is not a COM offset.",style=MaterialTheme.typography.bodySmall)
                    Text("Body height and leash are used for static fall. Values are saved automatically on this device.",style=MaterialTheme.typography.bodySmall)
                    NumberField("Walker reference position (m)",d.position,"position"){v->vm.edit{it.copy(position=v)}}
                    Text("Dragging updates the drawing. Use Solve to calculate the edited rig.",style=MaterialTheme.typography.bodySmall)
                }
                Group("Prescribed mainline failure") {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Calculate static fall");Switch(d.cutEnabled,{v->vm.edit{it.copy(cutEnabled=v)}},enabled=d.bungees?.banks.isNullOrEmpty(),modifier=Modifier.testTag("cut-enabled"))}
                    if(d.cutEnabled) {
                        NumberField("Cut reference position (m)",d.cutPosition,"cut-position"){v->vm.edit{it.copy(cutPosition=v)}}
                        Text("Simple keeps material labels fixed. A cut in the walker section releases its main contact; other intact contacts remain threaded. Catch is an energy turning state, not a time simulation.",style=MaterialTheme.typography.bodySmall)
                    }
                }
                if(validation.errors.isNotEmpty())Text(validation.errors.take(4).joinToString("\n")+if(validation.errors.size>4)"\n… ${validation.errors.size-4} more required inputs" else "",style=MaterialTheme.typography.bodySmall)
                screen.issue?.let { issue->
                    Text("Calculation failed",style=MaterialTheme.typography.titleSmall)
                    InformationDisclosure(issue.title,"solver-issue"){Text(displayWarning(issue.message))}
                }
            }
            screen.report?.let {
                TextButton(onClick={exportReport()},modifier=Modifier.testTag("export-diagnostics")) {
                    Text("Export Diagnostics")
                }
            }
            if(screen.report==null && validation.errors.isNotEmpty() && !screen.busy)
                TextButton(onClick=vm::captureInvalidInput){Text("Diagnose invalid inputs")}
            screen.exportMessage?.let{Text(it,modifier=Modifier.testTag("export-status"))}
            Spacer(Modifier.height(20.dp))
        }
    }
    failureSection?.let { selected ->
        AlertDialog(onDismissRequest={failureSection=null},title={Text("Fail mainline section ${selected+1}?")},
            text={Text("Apply the prescribed cut within section ${selected+1} and calculate Catch. The current standing equilibrium is retained for Reset Failure.")},
            confirmButton={TextButton(onClick={vm.failSection(selected);failureSection=null},modifier=Modifier.testTag("confirm-failure")){Text("Calculate failure")}},
            dismissButton={TextButton(onClick={failureSection=null}){Text("Cancel")}})
    }
    if(mixedStart)AlertDialog(onDismissRequest={mixedStart=false},title={Text("Start a Mixed rig?")},
        text={Text("The incomplete Uniform recipe will be replaced with one editable section. Other inputs are kept. Cancel to save the current recipe first.")},
        confirmButton={TextButton(onClick={vm.startMixed();mixedStart=false}){Text("Start Mixed")}},dismissButton={TextButton(onClick={mixedStart=false}){Text("Cancel")}})
    if(connectionsOpen)ConnectionOverview(d,vm,selectedJoint){connectionsOpen=false;selectedJoint=null}
    if(settingsOpen)SolverSettingsDialog(d,vm::modelSettings){settingsOpen=false}
    if(replaceUniform)AlertDialog(onDismissRequest={replaceUniform=false},title={Text("Replace individual sections?")},
        text={Text("Generate equal prepared sections using the span, section count and first section's materials, extra ratio and connection. Individual settings will be replaced. Cancel to save a copy first.")},
        confirmButton={TextButton(onClick={vm.switchToUniform(true);replaceUniform=false},modifier=Modifier.testTag("confirm-uniform")){Text("Replace sections")}},
        dismissButton={TextButton(onClick={replaceUniform=false},modifier=Modifier.testTag("cancel-uniform")){Text("Cancel")}})
    if(replaceHidden)AlertDialog(onDismissRequest={replaceHidden=false},title={Text("Convert active section?")},
        text={Text("This older Uniform rig has inactive sections. Converting preserves its active physical section and removes the inactive entries. Cancel to save a copy first.")},
        confirmButton={TextButton(onClick={vm.convertToMixed();replaceHidden=false}){Text("Convert")}},dismissButton={TextButton(onClick={replaceHidden=false}){Text("Cancel")}})
}


@Composable private fun SolverSettingsDialog(d:Draft,onChange:(ModelPreferences)->Unit,onClose:()->Unit) {
    var gravity by rememberSaveable{mutableStateOf(d.gravity)}
    var elements by rememberSaveable{mutableStateOf(d.elements)}
    var resistance by rememberSaveable{mutableStateOf(d.resistance)}
    var connections by rememberSaveable{mutableStateOf(d.modelConnections)}
    var bungees by rememberSaveable{mutableStateOf(d.showBungeeOptions)}
    fun applyValid() {
        val value=ModelPreferences(gravity,elements,resistance,connections,bungees)
        if(value.errors().isEmpty())onChange(value)
    }
    val errors=ModelPreferences(gravity,elements,resistance,connections,bungees).errors()
    AlertDialog(onDismissRequest=onClose,title={Text("Solver Settings")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            SolverSettingCheckbox("Model connections","model-connections",connections){connections=it;applyValid()}
            SolverSettingCheckbox("Show bungee options","show-bungee-options",DemoBungeePolicy.available && bungees,enabled=DemoBungeePolicy.available,status=if(!DemoBungeePolicy.available)DemoBungeePolicy.status else null){if(DemoBungeePolicy.available){bungees=it;applyValid()}}
            Text("Connections add hardware mass; individual load sharing is unknown. Joints and weblock stops stay active.")
            Text("Valid preferences save automatically.")
            NumberField("Gravity (m/s²)",gravity,"gravity"){gravity=it;applyValid()}
            Text("Default 9.81 m/s²; greater than 0.",style=MaterialTheme.typography.bodySmall)
            NumberField("Elements per section",elements,"elements"){elements=it;applyValid()}
            Text("Default 8; whole number from 2 to 32. This is a mobile resource bound, not a convergence guarantee.",style=MaterialTheme.typography.bodySmall)
            if(d.mode==Mode.ADVANCED) {
                NumberField("Ring resistance (N)",resistance,"resistance"){resistance=it;applyValid()}
                Text("Default 200 N; at least 0. Effective resistance to material travel, not a Coulomb coefficient. Dynamics is not implemented.")
            }
            if(errors.isNotEmpty())Text(errors.joinToString("\n"),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("settings-errors"))
            TextButton(onClick={
                val defaults=ModelPreferences();gravity=defaults.gravity;elements=defaults.elements;resistance=defaults.resistance;connections=defaults.modelConnections;bungees=defaults.showBungeeOptions
                onChange(defaults)
            },modifier=Modifier.testTag("restore-solver-defaults")){Text("Restore Defaults")}
        }
    },confirmButton={TextButton(onClick=onClose,modifier=Modifier.testTag("close-settings")){Text("Close")}})
}


@Composable private fun WalkerPositionControl(d:Draft,total:Double,onChange:(Double)->Unit,onFinished:((Double)->Unit)?=null,onJointTap:((String)->Unit)?=null,onConnections:(()->Unit)?=null,
    state:StaticState?=null,standing:StaticState?=null,resultMode:String="Standing",failureApplied:Boolean=false,selectedSection:Int?=null,onSectionTap:((Int)->Unit)?=null) {
    var dragging by remember{mutableStateOf(false)}
    var position by remember(d.position){mutableDoubleStateOf(d.position.number()?:total*.5)}
    val displayed=if(dragging)standing?:state else state
    OutlinedCard {RigDrawing(d,state=displayed,previewPositionM=if(dragging && displayed!=null)position else null,selectedSection=selectedSection,onSectionTap=onSectionTap,onJointTap=onJointTap,onConnections=onConnections)}
    if(state!=null && !failureApplied && onSectionTap!=null) {
        Text(stringResource(R.string.segment_failure_hint),style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.fillMaxWidth().testTag("segment-failure-hint"))
    }
    if(total>0 && total.isFinite()) {
        Text("Walker: ${lengthM(position)} of material from left",style=MaterialTheme.typography.bodyMedium,modifier=Modifier.testTag("walker-position"))
        Slider(enabled=walkerRepositionAllowed(failureApplied),value=position.coerceIn(total*.0001,total*.9999).toFloat(),onValueChange={v->dragging=true;position=v.toDouble();if(onFinished==null)onChange(position)},
            onValueChangeFinished={dragging=false;onFinished?.invoke(position)},valueRange=(total*.0001).toFloat()..(total*.9999).toFloat(),
            modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag(if(state==null)"walker-slider" else "equilibrium-walker-slider"))
        Text(walkerPositionInstruction(state!=null,resultMode,failureApplied),style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("walker-instruction"))
    }
}

@Composable private fun SolverSettingCheckbox(label:String,tag:String,checked:Boolean,enabled:Boolean=true,status:String?=null,onChange:(Boolean)->Unit) {
    Column {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Checkbox(checked,onChange,enabled=enabled,modifier=Modifier.testTag(tag))
            Text(label,modifier=Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(enabled)1f else .38f))
        }
        status?.let{Text(it,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(start=56.dp).testTag("$tag-status"))}
    }
}

fun walkerPositionInstruction(hasResult:Boolean,mode:String,failureApplied:Boolean=mode!="Standing"):String = when {
    !hasResult -> "Illustrative preview. Solve to calculate equilibrium."
    failureApplied -> "$mode shown. Reset Failure to reposition the walker."
    else -> "Standing shown. Release to calculate at the new position."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ResultStateControls(count:Int,stage:Int,onStage:(Int)->Unit,failure:Boolean,reset:()->Unit) {
    Column(Modifier.testTag("result-state-controls")) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            repeat(count){i->FilterChip(stage==i,{onStage(i)},label={Text(listOf("Standing","Catch","Rest")[i])},modifier=Modifier.testTag("result-state-$i"))}
            if(failure)OutlinedButton(onClick=reset,modifier=Modifier.testTag("reset-failure")){Text("Reset Failure")}
        }
    }
}

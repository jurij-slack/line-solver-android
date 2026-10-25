package org.linesolver.base.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.linesolver.base.energy.*
import org.linesolver.base.material.catalogue.*
import org.linesolver.base.model.*
import java.util.UUID

private fun newId()=UUID.randomUUID().toString()
fun Draft.withBungeeSectionIds():Draft {
    val d=if(uniform!=null)toMixed() else this
    return d.copy(sections=d.sections.map{if(it.id.isBlank())it.copy(id=newId()) else it})
}
@Composable private fun BungeeChoice(label:String,value:String,choices:List<Pair<String,String>>,change:(String)->Unit) {
    var open by remember{mutableStateOf(false)}
    OutlinedButton(onClick={open=true}){Text("$label: ${choices.firstOrNull{it.first==value}?.second ?: "Choose"}")}
    DropdownMenu(expanded=open,onDismissRequest={open=false}){choices.forEach{(id,name)->DropdownMenuItem(text={Text(name)},onClick={change(id);open=false})}}
}
@Composable private fun BungeeField(label:String,value:String,tag:String,change:(String)->Unit) {
    OutlinedTextField(value,change,label={Text(label)},modifier=Modifier.fillMaxWidth().testTag(tag),singleLine=true)
}

@Composable fun BungeeEditor(vm:SetupViewModel,d:Draft,openMaterials:()->Unit) {
    val repository=vm.materials as? CatalogueMaterialRepository ?: return
    var editBank by remember{mutableStateOf<BungeeBank?>(null)}
    var editingAssembly by remember{mutableStateOf<InstalledBungee?>(null)}
    var dialog by remember{mutableStateOf(false)}
    val canPlace=d.uniform?.errors(d.span).isNullOrEmpty() && d.activeSections.isNotEmpty()
    fun add(placement:BungeePlacement) {
        vm.edit{it.withBungeeSectionIds()}
        val current=vm.state.value.draft
        editBank=BungeeBank(newId(),placement,if(placement==BungeePlacement.series_right)null else current.activeSections.firstOrNull()?.id,Cable.MAIN,emptyList())
        editingAssembly=null;dialog=true
    }
    OutlinedCard(Modifier.fillMaxWidth().testTag("bungee-editor")){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Bungees · static equilibrium",style=MaterialTheme.typography.titleMedium)
        Text("Each assembly has its own measured law, physical cord length, folds and optional ideal length limiter. No shipped rubber curve is invented.")
        Row { TextButton(onClick={add(BungeePlacement.series_before)},enabled=canPlace,modifier=Modifier.testTag("add-left-bungee")){Text("Add left")}
            TextButton(onClick={add(BungeePlacement.series_right)},enabled=canPlace,modifier=Modifier.testTag("add-right-bungee")){Text("Add right")}
            TextButton(onClick=openMaterials){Text("My Materials")}}
        if(d.kind==SetupKind.MIXED)TextButton(onClick={add(BungeePlacement.series_before)},enabled=canPlace,modifier=Modifier.testTag("insert-bungee")){Text("Insert anywhere / parallel")}
        else TextButton(onClick={vm.convertToMixed()},enabled=canPlace){Text("Mixed editor for arbitrary placement")}
        if(!canPlace)Text("Complete the section generator before adding an attachment.")
        if(repository.bungeeModels().isEmpty())Text("No measured bungee model yet. In My Materials choose Bungee, declare measurement basis and enter loading points.")
        d.bungees?.banks.orEmpty().forEachIndexed { index,bank ->
            val target=d.activeSections.indexOfFirst{it.id==bank.sectionId}
            val location=when(bank.placement){BungeePlacement.series_right->"webbing → weblock stop → bungee → right anchor"
                BungeePlacement.series_before->if(target==0)"left anchor → bungee → Section 1" else "joint → bungee → Section ${target+1}"
                BungeePlacement.parallel_section->"parallel to Section ${target+1}"}
            Text("${bank.cable}: $location · ${bank.assemblies.size} independent parallel assemblies",modifier=Modifier.testTag("bungee-bank-$index"))
            BungeeBankSchematic(bank)
            bank.assemblies.forEach { a ->
                val model=repository.document.models.firstOrNull{it.id==a.model.modelId}
                Text("${model?.let{repository.get(it.id).name} ?: "Unavailable model"} · revision ${a.model.revision}")
                Text("Physical L0/m=${uiNumber(a.physicalRestM)}; ${a.equalLegs} equal legs; rest separation/m=${uiNumber(a.physicalRestM/a.equalLegs)}")
                Text("Limiter total physical L/m=${a.limiterPhysicalLengthM?.let(::uiNumber) ?: "none"}; engagement separation/m=${a.limiterPhysicalLengthM?.div(a.equalLegs)?.let(::uiNumber) ?: "none"}; ${if(a.limiterEnabled)"enabled" else "off"}")
                TextButton(onClick={editBank=bank;editingAssembly=a;dialog=true}){Text("Edit / move assembly")}
                TextButton(onClick={vm.edit{rig->rig.copy(bungees=rig.bungees?.copy(banks=rig.bungees.banks.mapNotNull{b->if(b.id!=bank.id)b else b.copy(assemblies=b.assemblies.filterNot{it.id==a.id}).takeIf{it.assemblies.isNotEmpty()}}))}}){Text("Remove assembly")}
            }
            Row { TextButton(onClick={editBank=bank;editingAssembly=null;dialog=true}){Text("Add independent parallel assembly")}
                if(index>0)TextButton(onClick={vm.edit{rig->val banks=rig.bungees!!.banks.toMutableList();val previous=banks[index-1];banks[index-1]=banks[index];banks[index]=previous;rig.copy(bungees=rig.bungees.copy(banks=banks))}}){Text("Move bank earlier")}}
        }
        if(!d.bungees?.banks.isNullOrEmpty())Text("Schematic order above; folds are ideal equal legs with negligible bend length. Catch/failure is disabled. Limiters have unknown strength; no strength sums or working load are calculated.")
    }}
    if(dialog) {
        val bank=editBank!!;val original=editingAssembly
        var modelId by rememberSaveable(bank.id,original?.id){mutableStateOf(original?.model?.modelId.orEmpty())}
        var physical by rememberSaveable(bank.id,original?.id){mutableStateOf(original?.physicalRestM?.toString().orEmpty())}
        var legs by rememberSaveable(bank.id,original?.id){mutableStateOf(original?.equalLegs?.toString() ?: "1")}
        var limit by rememberSaveable(bank.id,original?.id){mutableStateOf(original?.limiterPhysicalLengthM?.toString().orEmpty())}
        var enabled by rememberSaveable(bank.id,original?.id){mutableStateOf(original?.limiterEnabled ?: true)}
        var source by rememberSaveable(bank.id,original?.id){mutableStateOf(original?.source.orEmpty())}
        var uncertainty by rememberSaveable(bank.id,original?.id){mutableStateOf(original?.uncertainty.orEmpty())}
        var placement by rememberSaveable(bank.id,original?.id){mutableStateOf(bank.placement.name)}
        var target by rememberSaveable(bank.id,original?.id){mutableStateOf(bank.sectionId.orEmpty())}
        var cable by rememberSaveable(bank.id,original?.id){mutableStateOf(bank.cable.name)}
        var issue by remember{mutableStateOf<String?>(null)}
        val model=repository.document.models.firstOrNull{it.id==modelId};val law=model?.bungeeLaw()
        AlertDialog(onDismissRequest={dialog=false},title={Text("Configure physical bungee")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            val choices=(repository.bungeeModels()+listOfNotNull(model)).distinctBy{it.id}
            BungeeChoice("Exact model",modelId,choices.map{it.id to "${repository.get(it.id).name} · revision ${it.revision}"}){modelId=it}
            law?.let{Text("${it.source} · ${it.basis} · measured physical L0/m=${uiNumber(it.testedPhysicalLengthM)} · measured legs=${it.testedLegs} · rubber layers=${it.rubberLayers ?: "unknown"} (metadata only)")
                Text(if(it.scalablePerLength)"Length scaling: ${it.scalingEvidence}" else "Restricted to the measured physical length/configuration.")}
            model?.let{repository.limitations(it.id).forEach{warning->Text(warning)}}
            BungeeField("Physical unfolded cord L0/m",physical,"bungee-rest"){physical=it}
            BungeeField("Ideal equal load-bearing legs (1–16)",legs,"bungee-legs"){legs=it}
            Text("Effective rest separation/m=${physical.number()?.let{p->legs.toIntOrNull()?.takeIf{it>0}?.let{uiNumber(p/it)}} ?: "enter lengths"}")
            BungeeField("Limiter total physical length L/m (blank = none)",limit,"bungee-limit"){limit=it}
            Text("Effective limiter separation/m=${limit.number()?.let{l->legs.toIntOrNull()?.takeIf{it>0}?.let{uiNumber(l/it)}} ?: "none"}")
            Row{Checkbox(enabled,{enabled=it});Text("Enable ideal inextensible limiter")}
            BungeeField("Source of installed lengths",source,"bungee-source"){source=it}
            BungeeField("Uncertainty / configuration notes",uncertainty,"bungee-uncertainty"){uncertainty=it}
            BungeeChoice("Placement",placement,BungeePlacement.entries.map{it.name to it.name.replace('_',' ')}){placement=it}
            if(placement!=BungeePlacement.series_right.name)BungeeChoice("Section",target,d.activeSections.mapIndexed{i,s->s.id to "Section ${i+1}"}){target=it}
            BungeeChoice("Cable",cable,Cable.entries.map{it.name to it.name}){cable=it}
            Text("Moving one assembly moves its bank and parallel siblings. Separate series banks retain their order. The right bank sits behind a webbing termination, never through a weblock.")
            issue?.let{Text(it,modifier=Modifier.testTag("bungee-form-error"))}
        }},confirmButton={TextButton(onClick={
            try {
                val selected=requireNotNull(model){"Choose a measured bungee model"}
                val a=InstalledBungee(original?.id ?: newId(),selected.reference(),requireNotNull(physical.number()){ "Enter physical rest length" },requireNotNull(legs.toIntOrNull()){ "Enter integer equal-leg count" },
                    limiterPhysicalLengthM=if(limit.isBlank())null else requireNotNull(limit.number()){ "Enter finite total limiter length" },limiterEnabled=enabled,source=source,uncertainty=uncertainty)
                a.material(repository)
                val changed=bank.copy(placement=BungeePlacement.valueOf(placement),sectionId=if(placement==BungeePlacement.series_right.name)null else target,cable=Cable.valueOf(cable),
                    assemblies=if(original==null)bank.assemblies+a else bank.assemblies.map{if(it.id==a.id)a else it})
                vm.edit{rig->val layout=rig.bungees ?: BungeeLayout();val next=layout.copy(banks=if(layout.banks.any{it.id==bank.id})layout.banks.map{if(it.id==bank.id)changed else it} else layout.banks+changed)
                    require(next.errors(rig.activeSections,repository).isEmpty()){next.errors(rig.activeSections,repository).joinToString("\n")};rig.copy(bungees=next,cutEnabled=false)}
                dialog=false
            }catch(e:Exception){issue=e.message}
        },modifier=Modifier.testTag("save-bungee")){Text("Save assembly")}},dismissButton={TextButton(onClick={dialog=false}){Text("Cancel")}})
    }
}

@Composable private fun BungeeBankSchematic(bank:BungeeBank) {
    val color=MaterialTheme.colorScheme.primary;val ink=MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height((36+24*bank.assemblies.size).dp).testTag("bungee-schematic")) {
        val left=Offset(12.dp.toPx(),size.height/2);val right=Offset(size.width-12.dp.toPx(),size.height/2)
        for((i,a) in bank.assemblies.withIndex()) {
            val y=(24+i*24).dp.toPx()
            for(leg in 0 until a.equalLegs) {
                val yy=y+(leg-(a.equalLegs-1)/2f)*2.dp.toPx()
                val start=Offset(size.width*.2f,yy);val end=Offset(size.width*.8f,yy)
                drawLine(ink,left,start,1.dp.toPx());drawLine(color,start,end,2.dp.toPx());drawLine(ink,end,right,1.dp.toPx())
            }
            if(a.limiterEnabled && a.limiterPhysicalLengthM!=null)drawLine(ink,Offset(size.width*.2f,y+8.dp.toPx()),Offset(size.width*.8f,y+8.dp.toPx()),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(5f,4f)))
        }
        drawCircle(ink,5.dp.toPx(),left);drawCircle(ink,5.dp.toPx(),right)
    }
    Text("Schematic: shared terminal nodes; colored equal legs; dashed limiter. Not the equilibrium shape.")
}

package org.linesolver.base.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.linesolver.base.model.*
import java.util.UUID

fun componentLabel(d:Draft,end:ComponentEnd):String {
    val section=StructuralConnections.sectionIds(d).indexOf(end.instanceId)
    val bungee=d.bungees?.banks.orEmpty().flatMap{it.assemblies}.indexOfFirst{it.id==end.instanceId}
    return "${if(section>=0)"Section ${section+1}" else "Bungee ${bungee+1}"} · ${end.cable} · ${end.port}"
}

@Composable fun ConnectionOverview(d:Draft,vm:SetupViewModel,selectedId:String?=null,onClose:()->Unit) {
    val labels=remember(d){StructuralConnections.labeled(d).filter{it.joint.kind!=JointKind.anchor}}
    var query by remember{mutableStateOf("")}
    var selected by remember(selectedId){mutableStateOf(selectedId?.takeIf{id->labels.any{it.joint.id==id}})}
    LaunchedEffect(labels,selected) { if(selected!=null && labels.none{it.joint.id==selected})selected=null }
    val layout=StructuralConnections.reconcile(d)
    if(selected==null)AlertDialog(onDismissRequest=onClose,title={Text("Connections")},text={Column {
        Text("Mainline and Backup: idealized or one hardware item each. Anchors are ideal boundaries.",style=MaterialTheme.typography.bodySmall)
        if(d.hasInactiveConnectionMetadata())Text("Some saved assemblies are retained for future Dynamics.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(query,{query=it},label={Text("Search connections or components")},modifier=Modifier.fillMaxWidth().testTag("connection-search"))
        LazyColumn(Modifier.heightIn(max=360.dp)) {
            items(labels.filter{connectionOverviewLabel(d,it).contains(query,true)},key={it.joint.id}) { l ->
                TextButton(onClick={selected=l.joint.id},modifier=Modifier.fillMaxWidth().testTag("joint-${l.label}")) {
                    Text(connectionOverviewLabel(d,l))
                }
            }
            items(layout.detached,key={"detached-${it.id}"}) { j ->
                Column {
                    Text("Detached connection retained for compatibility.",style=MaterialTheme.typography.bodySmall)

                }
            }
        }
    }},confirmButton={TextButton(onClick=onClose,modifier=Modifier.testTag("close-connections")){Text("Close")}})
    labels.firstOrNull{it.joint.id==selected}?.let { l -> ConnectionDialog(d,l,vm,{selected=null}) }
}

fun connectionEndpoints(d:Draft,j:StructuralJoint)="Between "+connectionComponentNames(d,j).joinToString(" and ")

@Composable private fun ConnectionDialog(d:Draft,l:LabeledJoint,vm:SetupViewModel,close:()->Unit) {
    val j=l.joint
    val editable=simpleConnectionEditable(j)
    var hardware by remember(j.id){mutableStateOf(j.details.hardware)}
    AlertDialog(onDismissRequest=close,title={Text(l.label)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(connectionEndpoints(d,j),style=MaterialTheme.typography.bodySmall)
            if(!editable)Text("Saved assembly retained. Editing requires future Dynamics.",style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("saved-assembly"))
            for(role in listOf(HardwareRole.main,HardwareRole.backup)) {
                Text(if(role==HardwareRole.main)"Mainline connector" else "Backup connector",style=MaterialTheme.typography.titleSmall)
                val items=regularConnectorItems(j.copy(details=j.details.copy(hardware=hardware))).filter{it.role==role}
                if(editable) {
                    val candidate=remember(j.id,role){HardwareItem(UUID.randomUUID().toString(),role=role)}
                    Column(Modifier.testTag("simple-${role.name}-selector")) {
                    ConnectorFields(items.singleOrNull() ?: candidate,j,vm,advanced=false,simple=true,
                        emptyLabel="Idealized connection",onIdealized={hardware=hardware.filterNot{it.role==role}}) { changed ->
                        hardware=hardware.filterNot{it.role==role}+changed
                    }
                    }
                    Text(simpleHardwareSummary(items.singleOrNull()),style=MaterialTheme.typography.bodySmall)
                } else if(items.isEmpty())Text("Retained configuration",style=MaterialTheme.typography.bodySmall)
                else if(items.size==1) {
                    Text(runCatching{ConnectorLibrary.resolve(items.single())?.name}.getOrNull() ?: "Retained hardware")
                    Text(simpleHardwareSummary(items.single()),style=MaterialTheme.typography.bodySmall)
                } else Text("Saved assembly · ${items.size} items",style=MaterialTheme.typography.bodySmall)
            }
            if(!editable)Text("Known active hardware masses remain included when Model connections is on. Full records are available in Detailed Results and diagnostics.",style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick={
        if(editable)vm.edit { rig ->
            val layout=StructuralConnections.reconcile(rig)
            rig.copy(connections=layout.copy(joints=layout.joints.map{old->if(old.id==j.id)old.copy(details=old.details.copy(hardware=hardware)) else old}))
        }
        close()
    },modifier=Modifier.testTag("save-connection")){Text(if(editable)"Save" else "Close")}},dismissButton={if(editable)TextButton(onClick=close){Text("Cancel")}})
}

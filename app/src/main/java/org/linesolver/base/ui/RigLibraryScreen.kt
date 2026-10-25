package org.linesolver.base.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.linesolver.base.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun RigLibraryControls(vm:SetupViewModel) {
    val screen by vm.state.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val exportRigs=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"),vm::exportRigs)
    val importRigs=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(),vm::importRigs)
    val selected=library.rigs.firstOrNull{it.id==library.selectedId}
    val dirty=rigHasChanges(screen.draft,selected)
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    var open by rememberSaveable{mutableStateOf(false)}
    var query by rememberSaveable{mutableStateOf("")}
    var action by rememberSaveable{mutableStateOf("")}
    var target by rememberSaveable{mutableStateOf<String?>(null)}
    var name by rememberSaveable{mutableStateOf("")}
    var resetConfirm by remember{mutableStateOf(false)}
    var confirmUpdate by rememberSaveable{mutableStateOf(false)}
    var deleteId by rememberSaveable{mutableStateOf<String?>(null)}
    fun editName(kind:String,id:String?=null,suggestion:String="") {
        vm.clearLibraryError();action=kind;target=id;name=suggestion
        focus.clearFocus();keyboard?.hide()
    }
    var handledRevision by rememberSaveable{mutableIntStateOf(library.revision)}
    LaunchedEffect(library.revision) {if(handledRevision!=library.revision){action="";confirmUpdate=false;deleteId=null;handledRevision=library.revision}}
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(selected?.name?:"Unsaved rig",style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("current-rig"))
            Text(if(dirty)"Unsaved changes" else if(selected!=null)"Saved" else "Working configuration",modifier=Modifier.testTag("rig-dirty"))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={focus.clearFocus();keyboard?.hide();open=true},modifier=Modifier.testTag("saved-rigs")){Text("Saved Rigs")}
                Button(onClick={editName("save",suggestion=selected?.name?:"Rig ${screen.draft.span.ifBlank{"new"}} m")},
                    enabled=!library.busy && !library.blocked,modifier=Modifier.testTag("save-rig")){Text("Save Rig")}
            }
            TextButton(onClick={if(dirty)resetConfirm=true else vm.resetRig()},modifier=Modifier.testTag("reset-rig")){Text("Reset Rig")}
            library.message?.let{Text(it,modifier=Modifier.testTag("library-message"))}
            if(!open && action.isEmpty())library.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
    if(resetConfirm)AlertDialog(onDismissRequest={resetConfirm=false},title={Text("Reset working rig?")},
        text={Text("Discard unsaved rig edits and restore the default working rig? Saved rigs, walker preferences and solver settings are kept.")},
        confirmButton={TextButton(onClick={vm.resetRig();resetConfirm=false},modifier=Modifier.testTag("confirm-reset-rig")){Text("Reset Rig")}},dismissButton={TextButton(onClick={resetConfirm=false}){Text("Cancel")}})
    if(open)AlertDialog(modifier=Modifier.imePadding(),onDismissRequest={if(!library.busy)open=false},title={Text("Saved Rigs")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            val searchFocus=LocalFocusManager.current
            val searchKeyboard=LocalSoftwareKeyboardController.current
            OutlinedTextField(query,{query=it},label={Text("Find a rig")},singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={searchFocus.clearFocus();searchKeyboard?.hide()}),modifier=Modifier.fillMaxWidth().testTag("rig-search"))
            Row {
                TextButton(onClick={exportRigs.launch("linesolver-rigs.json")},enabled=!library.busy && !library.blocked,modifier=Modifier.testTag("export-rigs")){Text("Export JSON")}
                TextButton(onClick={importRigs.launch(arrayOf("application/json","text/plain"))},enabled=!library.busy && !library.blocked,modifier=Modifier.testTag("import-rigs")){Text("Import JSON")}
            }
            Text("Loads the saved rig, connections and prescribed cut. Walker position and other global preferences stay current.",style=MaterialTheme.typography.bodySmall)
            library.error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("library-error"))}
            val matches=library.rigs.filter{it.name.contains(query,ignoreCase=true)}.sortedByDescending{it.modifiedAt}
            if(matches.isEmpty())Text(if(library.rigs.isEmpty())"No saved rigs yet." else "No matching rigs.")
            LazyColumn(Modifier.weight(1f,fill=false).heightIn(max=420.dp).testTag("rig-list"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(matches,key={it.id}) { entry ->
                    OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(entry.name,style=MaterialTheme.typography.titleSmall)
                        Text("Span ${entry.configuration.span.ifBlank{"—"}} m · ${entry.configuration.activeSections.size} section(s) · ${entry.configuration.kind.name.lowercase()}")
                        Text("Main: ${entry.configuration.materialSummary(vm.materials,true)}",maxLines=3)
                        Text("Backup: ${entry.configuration.materialSummary(vm.materials,false)}",maxLines=3)
                        Text("Modified "+runCatching{DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(entry.modifiedAt))}.getOrDefault("unknown"),style=MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick={if(vm.loadRig(entry.id))open=false},enabled=!library.busy && !library.blocked,modifier=Modifier.testTag("load-${entry.id}")){Text("Load")}
                            var more by remember{mutableStateOf(false)}
                            Box {
                                TextButton(onClick={more=true},enabled=!library.busy && !library.blocked,modifier=Modifier.testTag("more-${entry.id}")){Text("More")}
                                DropdownMenu(more,{more=false}) {
                                    DropdownMenuItem(text={Text("Rename")},onClick={more=false;editName("rename",entry.id,entry.name)})
                                    DropdownMenuItem(text={Text("Duplicate")},onClick={more=false;editName("duplicate",entry.id,"${entry.name} copy")})
                                    DropdownMenuItem(text={Text("Delete")},onClick={more=false;deleteId=entry.id})
                                }
                            }
                        }
                    }}
                }
            }
        }
    },confirmButton={TextButton(onClick={open=false},enabled=!library.busy,modifier=Modifier.testTag("close-library")){Text("Close")}})
    if(action.isNotEmpty())AlertDialog(modifier=Modifier.imePadding(),onDismissRequest={if(!library.busy)action=""},title={Text(when(action){"rename"->"Rename rig";"duplicate"->"Duplicate rig";else->"Save Rig"})},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name,{name=it},label={Text("Rig name")},singleLine=true,modifier=Modifier.testTag("rig-name"))
            Text("Saves rig geometry, sections and prescribed cut. Incomplete inputs may be saved; Solve still validates them.",style=MaterialTheme.typography.bodySmall)
            library.error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("save-rig-error"))}
            if(action=="save" && selected!=null)TextButton(onClick={confirmUpdate=true},enabled=!library.busy && name.isNotBlank(),modifier=Modifier.testTag("update-rig")){Text("Update ${selected.name}")}
        }
    },confirmButton={TextButton(onClick={when(action){
        "rename"->vm.renameRig(target!!,name)
        "duplicate"->vm.duplicateRig(target!!,name)
        else->vm.saveRig(name)
    }},enabled=!library.busy && name.isNotBlank(),modifier=Modifier.testTag("confirm-rig-name")){Text(if(action=="save")"Save new rig" else "Save")}},
        dismissButton={TextButton(onClick={action="";vm.clearLibraryError()},enabled=!library.busy,modifier=Modifier.testTag("cancel-save-rig")){Text("Cancel")}})
    if(confirmUpdate)AlertDialog(onDismissRequest={confirmUpdate=false},title={Text("Replace saved rig?")},text={Column {Text("Update ${selected?.name} with the current rig inputs? Its previous saved configuration will be replaced.");library.error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("update-rig-error"))}}},
        confirmButton={TextButton(onClick={vm.saveRig(name,update=true)},enabled=!library.busy,modifier=Modifier.testTag("confirm-update-rig")){Text("Update rig")}},
        dismissButton={TextButton(onClick={confirmUpdate=false},enabled=!library.busy,modifier=Modifier.testTag("cancel-update-rig")){Text("Cancel")}})
    library.pendingLoad?.let { id ->
        AlertDialog(onDismissRequest=vm::cancelRigLoad,title={Text("Confirm load")},text={Text(library.loadWarning)},
            confirmButton={TextButton(onClick={if(vm.loadRig(id,discardChanges=true))open=false},modifier=Modifier.testTag("discard-and-load")){Text("Load saved rig")}},
            dismissButton={TextButton(onClick=vm::cancelRigLoad,modifier=Modifier.testTag("cancel-rig-load")){Text("Cancel")}})
    }
    deleteId?.let { id ->
        val entry=library.rigs.firstOrNull{it.id==id}
        AlertDialog(onDismissRequest={deleteId=null},title={Text("Delete saved rig?")},text={Column {Text("Delete ${entry?.name}? Current working inputs will be retained.");library.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},
            confirmButton={TextButton(onClick={vm.deleteRig(id)},enabled=!library.busy,modifier=Modifier.testTag("confirm-delete-rig")){Text("Delete")}},
            dismissButton={TextButton(onClick={deleteId=null},enabled=!library.busy){Text("Cancel")}})
    }
}

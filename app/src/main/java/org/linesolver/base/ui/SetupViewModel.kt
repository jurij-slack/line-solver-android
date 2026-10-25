package org.linesolver.base.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.linesolver.base.model.*
import org.linesolver.base.material.*
import org.linesolver.base.material.catalogue.CatalogueMaterialRepository
import org.linesolver.base.material.catalogue.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import org.linesolver.base.energy.LocatedMaterialLimit
import org.linesolver.base.solver.*
import org.linesolver.base.diagnostics.*

data class LibraryState(val rigs:List<SavedRig> = emptyList(),val selectedId:String?=null,
    val busy:Boolean=false,val error:String?=null,val blocked:Boolean=false,val message:String?=null,
    val revision:Int=0,val pendingLoad:String?=null,val loadWarning:String="")
data class Issue(val title:String,val message:String,val physicalLimit:Boolean=false)
data class ScreenState(val draft:Draft=Draft(),val busy:Boolean=false,val result:StaticResult?=null,val issue:Issue?=null,val report:DiagnosticReport?=null,val exportMessage:String?=null)
class SetupViewModel(app:Application,private val saved:SavedStateHandle):AndroidViewModel(app) {
    private val legacyMaterials = JsonMaterialRepository(app.assets.open("webbing_material_models_v1.json").bufferedReader().use{it.readText()})
    private val shippedMaterials = CatalogueMaterialRepository(legacyMaterials,
        app.assets.open("webbing-catalogue-v1.json").bufferedReader().use{it.readText()})
    private val overlayFile = android.util.AtomicFile(java.io.File(app.filesDir,"my_materials_v1.json"))
    val materialStore = MaterialOverlayStore(shippedMaterials.document,
        read = { if (overlayFile.baseFile.exists() || java.io.File(overlayFile.baseFile.path+".bak").exists()) overlayFile.readFully().toString(Charsets.UTF_8) else null },
        write = { text ->
            val next = MaterialPackages.decode(text, shippedMaterials.document)
            CatalogueMaterialRepository(legacyMaterials, CatalogueCodec.encode(MaterialPackages.combine(shippedMaterials.document, next)), next.retiredModelIds.toSet())
            val stream = overlayFile.startWrite()
            try { stream.write(text.toByteArray(Charsets.UTF_8)); overlayFile.finishWrite(stream) }
            catch (e: Exception) { overlayFile.failWrite(stream); throw e }
        })
    var materials: MaterialRepository by mutableStateOf(buildMaterials()); private set
    private fun buildMaterials(): CatalogueMaterialRepository = if (materialStore.value.catalogue.products.isEmpty() && materialStore.value.catalogue.datasets.isEmpty()) shippedMaterials else
        CatalogueMaterialRepository(legacyMaterials, CatalogueCodec.encode(MaterialPackages.combine(shippedMaterials.document,materialStore.value)), materialStore.value.retiredModelIds.toSet())
    var materialMessage: String? by mutableStateOf(materialStore.readError); private set
    var materialBusy by mutableStateOf(false); private set
    private val connectorFile=android.util.AtomicFile(java.io.File(app.filesDir,"custom_connectors_v1.json"))
    val connectorStore=CustomConnectorStore(
        {if(connectorFile.baseFile.exists() || java.io.File(connectorFile.baseFile.path+".bak").exists())connectorFile.readFully().toString(Charsets.UTF_8) else null},
        {text->val stream=connectorFile.startWrite();try{stream.write(text.toByteArray(Charsets.UTF_8));connectorFile.finishWrite(stream)}catch(e:Exception){connectorFile.failWrite(stream);throw e}})
    var customConnectors by mutableStateOf(connectorStore.value.products);private set
    fun addCustomConnector(spec:ConnectorSpec) { connectorStore.add(spec);customConnectors=connectorStore.value.products }
    private val json=Json{ignoreUnknownKeys=true}
    private val local=app.getSharedPreferences("line_solver_preferences",android.content.Context.MODE_PRIVATE)
    var materialEdit by mutableStateOf(local.getString("material_editor_draft",null)?.let { runCatching { Json.decodeFromString<MaterialEdit>(it) }.getOrNull() } ?: MaterialEdit()); private set
    fun editMaterial(value: MaterialEdit) {
        materialEdit = value
        local.edit().putString("material_editor_draft",Json.encodeToString(value)).apply()
    }
    fun materialNotice(text: String) { materialMessage = text }
    fun previewMaterial(edit: MaterialEdit): Pair<EditorPreview,Webbing?> {
        val preview = MaterialEditing.preview(edit)
        val model = preview.model ?: return preview to null
        val combined = (materials as CatalogueMaterialRepository).document
        val product = combined.products.firstOrNull { it.id == model.productId } ?: Product(model.productId,edit.category,
            edit.brand.takeIf { it.isNotBlank() },edit.name.ifBlank { "Draft webbing" })
        val document = combined.copy(products = if (combined.products.any { it.id == product.id }) combined.products else combined.products + product,
            datasets = combined.datasets + preview.dataset, models = combined.models + model)
        return preview to CatalogueMaterialRepository(legacyMaterials,CatalogueCodec.encode(document)).get(model.id)
    }
    private fun changeMaterials(onSuccess: () -> Unit = {}, action: () -> String) {
        if (materialBusy) return
        materialBusy = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { action() to buildMaterials() }
                materials = result.second; materialMessage = result.first
                onSuccess()
            } catch (e: Exception) { if (e is CancellationException) throw e; materialMessage = "${e.message}. Library changes were not applied." }
            finally { materialBusy = false }
        }
    }
    fun saveMaterial() {
        val edit = materialEdit
        var loaded: MaterialEdit? = null
        changeMaterials(onSuccess = { if(materialEdit==edit)loaded?.let(::editMaterial) }) {
            val previousCount = materialStore.value.catalogue.models.size
            val message = MaterialEditing.save(materialStore, edit)
            val c = materialStore.value.catalogue
            val model = c.models.lastOrNull()?.takeIf { c.models.size > previousCount }
            val dataset = if (model != null) c.datasets.single { it.id == model.datasetIds.single() } else c.datasets.last()
            loaded = MaterialEditing.load(MaterialPackages.combine(materialStore.shipped,materialStore.value),dataset,model?.id)
            message
        }
    }
    fun retireMaterial(id: String) = changeMaterials { materialStore.retire(id); "Model retired from new choices. Source and exact revision retained for existing rigs and backups." }
    fun deleteMaterialDataset(id: String) = changeMaterials { materialStore.deleteDataset(id); "Unreferenced user dataset deleted." }
    fun deleteMaterialProduct(id: String) = changeMaterials { materialStore.deleteProduct(id); "Unreferenced user product deleted." }
    fun importMaterials(uri: android.net.Uri?, distinct: Boolean, restore: Boolean = false, revisions: Boolean = false) {
        if (uri == null) return
        changeMaterials {
            val text = getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val text = reader.readText(); require(text.length <= 10_000_000) { "Package exceeds 10 MB" }; text
            } ?: error("Cannot open selected document")
            if (restore) materialStore.restoreUnreadable(text) else materialStore.import(text, when { revisions -> ImportPolicy.create_new_revisions; distinct -> ImportPolicy.import_as_distinct; else -> ImportPolicy.skip_identical })
            "Material package imported. Identical records skipped; existing rig choices retained."
        }
    }
    fun exportMaterials(uri: android.net.Uri?, csvDatasetId: String? = null) {
        if (uri == null || materialBusy) return
        val text = runCatching { if (csvDatasetId == null) materialStore.export() else MeasurementCsv.export(materialStore.value.catalogue.datasets.single { it.id == csvDatasetId }) }
            .getOrElse { materialMessage = it.message; return }
        materialBusy = true
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(uri,"wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("Cannot write selected document") }; materialMessage = "Export saved." }
            catch (e: Exception) { if(e is CancellationException)throw e; materialMessage = "Export failed: ${e.message}" }
            finally { materialBusy = false }
        }
    }
    fun importMeasurementCsv(uri: android.net.Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Cannot read CSV") }
                require(text.length <= 1_000_000) { "CSV exceeds 1 MB" }
                val detected = MeasurementCsv.detect(text)
                editMaterial(materialEdit.copy(table=text,forceUnit=detected?.first ?: materialEdit.forceUnit,
                    strainUnit=detected?.second ?: materialEdit.strainUnit,forceColumn=detected?.third?.first ?: 0,strainColumn=detected?.third?.second ?: 1,csvHasHeader=detected!=null,importedCsvText=text))
                materialMessage = if (detected == null) "Nonstandard headings: confirm force/elongation column numbers and units before saving." else "CSV loaded into the draft. Confirm units, reference length and provenance; then Save."
            } catch(e:Exception) { if(e is CancellationException)throw e; materialMessage="CSV import failed: ${e.message}" }
        }
    }
    private val preferences=AppPreferences(
        read={key->local.getString(key,null)},
        write={key,value->if(local.getString(key,null)!=value)local.edit().putString(key,value).apply()})
    private val mutable=MutableStateFlow(ScreenState(preferences.restore(
        (saved.get<String>("draft") ?: local.getString("working_rig_draft",null))?.let{runCatching{json.decodeFromString<Draft>(it)}.getOrNull()}?:freshGeneratorDraft()).let { d ->
        val ids=StructuralConnections.sectionIds(d)
        val identified=if(d.uniform!=null)d.copy(uniform=d.uniform.copy(instanceIds=ids)) else d.copy(sections=d.sections.mapIndexed{i,s->s.copy(id=s.id.ifBlank{ids.getOrNull(i) ?: java.util.UUID.randomUUID().toString()})})
        identified.copy(connections=StructuralConnections.reconcile(identified))
    }))
    val state=mutable.asStateFlow()
    private val libraryFile=android.util.AtomicFile(java.io.File(app.filesDir,"rig_library.json"))
    private val rigStore=RigLibraryStore(
        read={if(libraryFile.baseFile.exists() || java.io.File(libraryFile.baseFile.path+".bak").exists())libraryFile.readFully().toString(Charsets.UTF_8) else null},
        write={text->
            val stream=libraryFile.startWrite()
            try {stream.write(text.toByteArray(Charsets.UTF_8));libraryFile.finishWrite(stream)}
            catch(e:Exception){libraryFile.failWrite(stream);throw e}
        })
    private val libraryMutable=MutableStateFlow(LibraryState(rigStore.document.rigs,
        selectedId=saved.get<String>("loadedRigId")?.takeIf{id->rigStore.document.rigs.any{it.id==id}},
        error=rigStore.readError,blocked=rigStore.readError!=null))
    val library=libraryMutable.asStateFlow()
    private var job:Job?=null
    private fun selectRig(id:String?) {saved["loadedRigId"]=id;libraryMutable.value=libraryMutable.value.copy(selectedId=id)}
    fun clearLibraryError() {if(!libraryMutable.value.blocked)libraryMutable.value=libraryMutable.value.copy(error=null)}
    private fun mutateLibrary(message:String,action:()->String?) {
        if(libraryMutable.value.busy || libraryMutable.value.blocked)return
        libraryMutable.value=libraryMutable.value.copy(busy=true,error=null,message=null)
        viewModelScope.launch {
            try {
                val selected=withContext(Dispatchers.IO){action()}
                selectRig(selected)
                libraryMutable.value=libraryMutable.value.copy(rigs=rigStore.document.rigs,busy=false,
                    message=message,revision=libraryMutable.value.revision+1)
            } catch(e:Exception) {
                if(e is CancellationException)throw e
                libraryMutable.value=libraryMutable.value.copy(busy=false,error=if(e is IllegalArgumentException)e.message else "Could not save the library. Existing rigs are unchanged; try again.")
            }
        }
    }
    fun saveRig(name:String,update:Boolean=false) {
        val config=RigConfiguration.from(mutable.value.draft)
        val id=libraryMutable.value.selectedId
        if(update && id==null){libraryMutable.value=libraryMutable.value.copy(error="Load a saved rig before updating it.");return}
        mutateLibrary("Rig saved: ${name.trim()}") {rigStore.save(name,config,config.fingerprints(materials),if(update)id else null).id}
    }
    fun exportRigs(uri:android.net.Uri?) {
        if(uri==null || libraryMutable.value.busy || libraryMutable.value.blocked)return
        val text=rigStore.exportJson()
        mutateLibrary("Rig library exported. Custom models require a separate My Materials JSON backup.") {
            getApplication<Application>().contentResolver.openOutputStream(uri,"wt")?.use{it.write(text.toByteArray(Charsets.UTF_8))} ?: error("Cannot write export")
            libraryMutable.value.selectedId
        }
    }
    fun importRigs(uri:android.net.Uri?) {
        if(uri==null)return
        mutateLibrary("Rig library imported. Existing entries and the working rig were retained. Restore missing custom models through My Materials.") {
            val text=getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()} ?: error("Cannot read import")
            require(text.length<=10_000_000) { "Rig library exceeds 10 MB" }
            rigStore.importJson(text);libraryMutable.value.selectedId
        }
    }
    fun renameRig(id:String,name:String) {
        val selected=libraryMutable.value.selectedId
        mutateLibrary("Rig renamed."){rigStore.rename(id,name);selected}
    }
    fun duplicateRig(id:String,name:String) {
        val selected=libraryMutable.value.selectedId
        mutateLibrary("Rig copy saved: ${name.trim()}"){rigStore.duplicate(id,name);selected}
    }
    fun deleteRig(id:String) {
        val selected=libraryMutable.value.selectedId
        mutateLibrary("Rig deleted. The current working inputs are retained."){rigStore.delete(id);selected?.takeUnless{it==id}}
    }
    fun cancelRigLoad() {libraryMutable.value=libraryMutable.value.copy(pendingLoad=null)}
    fun loadRig(id:String,discardChanges:Boolean=false):Boolean {
        if(libraryMutable.value.busy || libraryMutable.value.blocked)return false
        val entry=libraryMutable.value.rigs.firstOrNull{it.id==id}?:return false
        val problems=entry.materialProblems(materials)
        if(problems.isNotEmpty()) {
            libraryMutable.value=libraryMutable.value.copy(error=problems.joinToString("\n")+"\nRig not loaded; no materials were substituted. For a missing custom model, use My Materials → Import JSON to restore its exact backup revision.",pendingLoad=null)
            return false
        }
        val current=libraryMutable.value.rigs.firstOrNull{it.id==libraryMutable.value.selectedId}
        val unversioned=entry.configuration.materialSections.flatMap{listOf(it.main,it.backup)}
            .any{it.materialId.isNotEmpty() && it.materialId !in entry.materialFingerprints}
        val revisions=entry.materialRevisionWarnings(materials)
        val dirty=rigHasChanges(mutable.value.draft,current)
        if(!discardChanges && (dirty || unversioned || revisions.isNotEmpty())) {
            val warning=listOfNotNull(
                if(dirty)"Loading will discard current rig edits. Cancel to save them first." else null,
                revisions.takeIf{it.isNotEmpty()}?.joinToString("\n"),
                if(unversioned)"This older rig has no material revision record. Loading uses the installed material data." else null,
                "Walker position and global preferences will be kept.").joinToString("\n\n")
            libraryMutable.value=libraryMutable.value.copy(pendingLoad=id,loadWarning=warning);return false
        }
        edit{entry.configuration.applyTo(it).let { d ->
            val ids=StructuralConnections.sectionIds(d)
            if(d.uniform!=null)d.copy(uniform=d.uniform.copy(instanceIds=ids)) else d.copy(sections=d.sections.mapIndexed{i,s->s.copy(id=s.id.ifBlank{ids.getOrNull(i).orEmpty()})})
        }}
        selectRig(id)
        libraryMutable.value=libraryMutable.value.copy(pendingLoad=null,error=null,message="Loaded ${entry.name}. Walker and solver preferences are unchanged.")
        return true
    }
    private var beforeFailure:ScreenState?=null
    fun resetFailure() { job?.cancel();beforeFailure?.let{mutable.value=it;saved["draft"]=json.encodeToString(it.draft);local.edit().putString("working_rig_draft",json.encodeToString(it.draft)).apply()};beforeFailure=null }
    fun failSection(section:Int) {
        if(blockDemoBungees())return
        val current=mutable.value
        if(!current.draft.bungees?.banks.isNullOrEmpty()) { mutable.value=current.copy(issue=Issue("Static bungee limitation","Catch/failure is unavailable: transient limiter engagement is not modeled."));return }
        val result=current.result?:return
        val base=beforeFailure?:current.copy(draft=current.draft.copy(cutEnabled=false))
        val q=failureCoordinate(result.installedLengths,section,current.draft.position.number()?:return)
        val request=current.draft.copy(cutEnabled=true,cutPosition=q.toString())
        beforeFailure=base;mutable.value=current.copy(draft=request);solve(base.result)
    }
    fun moveWalker(position:Double) {
        if(blockDemoBungees())return
        if(!walkerRepositionAllowed(mutable.value.draft.cutEnabled || mutable.value.result?.catch!=null))return
        edit{it.copy(position=position.toString())};solve()
    }
    fun resetRig() { edit{resetWorkingRig(it)};selectRig(null) }
    fun startMixed()=edit{it.copy(kind=SetupKind.MIXED,uniform=null,sections=listOf(SectionDraft()))}
    fun edit(transform:(Draft)->Draft) {
        val previous=mutable.value
        val transformed=try {transform(previous.draft)} catch(e:IllegalArgumentException) { mutable.value=previous.copy(issue=Issue("Edit not applied",e.message ?: "Invalid structural edit"));return }
        val identified=if(transformed.uniform!=null) {
            val count=transformed.uniform.count.toIntOrNull()?.takeIf{it in 1..100} ?: 0
            transformed.copy(uniform=transformed.uniform.copy(instanceIds=List(count){i->transformed.uniform.instanceIds.getOrNull(i) ?: java.util.UUID.randomUUID().toString()}))
        } else transformed.copy(sections=transformed.sections.map{if(it.id.isBlank())it.copy(id=java.util.UUID.randomUUID().toString()) else it})
        val draft=try {identified.copy(connections=StructuralConnections.reconcile(identified))} catch(e:IllegalArgumentException) { mutable.value=previous.copy(issue=Issue("Edit not applied",e.message ?: "Move or remove affected bungees first"));return }
        val onlyConnections=draft.copy(connections=previous.draft.connections,modelConnections=previous.draft.modelConnections,showBungeeOptions=previous.draft.showBungeeOptions)==previous.draft && draft.connectorMassSignature()==previous.draft.connectorMassSignature()
        if(!onlyConnections)beforeFailure=null else beforeFailure=beforeFailure?.let{it.copy(draft=it.draft.copy(connections=draft.connections,modelConnections=draft.modelConnections,showBungeeOptions=draft.showBungeeOptions))}
        job?.cancel()
        preferences.save(draft)
        saved["draft"]=json.encodeToString(draft)
        mutable.value=if(onlyConnections)previous.copy(draft=draft,issue=null,report=previous.result?.let{DiagnosticRunner.report(draft,materials,SolverSettings(),SolverTrace(),it,null)}) else ScreenState(draft)
        local.edit().putString("working_rig_draft",json.encodeToString(draft)).apply()
    }
    fun modelSettings(settings:ModelPreferences) {
        require(settings.errors().isEmpty())
        edit{settings.applyTo(it)}
    }
    fun generator(transform:(UniformGenerator)->UniformGenerator)=edit{d->d.withUniform(transform(d.uniform?:d.uniformProposal()))}
    fun convertToMixed()=edit{it.toMixed()}
    fun switchToUniform(confirmReplacement:Boolean=false):Boolean {
        val d=mutable.value.draft
        if(!d.bungees?.banks.isNullOrEmpty())return false
        if(d.kind==SetupKind.UNIFORM && d.uniform!=null)return true
        if(!confirmReplacement && d.uniformReplacementNeeded())return false
        edit{it.withUniform(it.uniformProposal())};return true
    }
    fun section(index:Int,transform:(SectionDraft)->SectionDraft)=edit{d->d.copy(sections=d.sections.mapIndexed{i,s->if(i==index)transform(s) else s})}
    private fun blockDemoBungees():Boolean {
        val current=mutable.value
        if(!DemoBungeePolicy.blocks(current.draft))return false
        job?.cancel()
        beforeFailure=null
        mutable.value=ScreenState(current.draft,issue=Issue("Bungee currently unavailable",DemoBungeePolicy.retainedMessage))
        return true
    }
    fun solve(previous:StaticResult?=null) {
        if(blockDemoBungees())return
        val request=mutable.value.draft
        job?.cancel();mutable.value=ScreenState(request,busy=true,result=previous)
        job=viewModelScope.launch {
            val run=withContext(Dispatchers.Default) {
                val context=currentCoroutineContext()
                val app=getApplication<Application>()
                val version=app.packageManager.getPackageInfo(app.packageName,0).versionName?:"unknown"
                DiagnosticRunner.run(request,materials,appVersion=version,checkCancelled={context.ensureActive()},standing=previous)
            }
            currentCoroutineContext().ensureActive()
            if(beforeFailure==null && run.result?.catch!=null) {
                val result=run.result
                val baseline=StaticResult(result.installedLengths,result.achievedPretensionN,result.standing)
                val draft=request.copy(cutEnabled=false)
                beforeFailure=ScreenState(draft,result=baseline,report=DiagnosticRunner.report(draft,materials,SolverSettings(),SolverTrace(),baseline,null))
            }
            if(mutable.value.draft==request)mutable.value=ScreenState(request,result=run.result?:previous,
                issue=run.failure?.let{Issue("Calculation failed",failureMessage(failureCategory(it)))},report=run.report)
        }
    }
    private var pendingExport:String?=null
    fun prepareExport(stage:String) { pendingExport=mutable.value.report?.withDisplayedState(stage)?.text() }
    fun exportFailed() { pendingExport=null;mutable.value=mutable.value.copy(exportMessage="Could not open the file picker. Try again.") }
    fun exportTo(uri:android.net.Uri?) {
        val text=pendingExport;pendingExport=null
        if(uri==null || text==null)return
        viewModelScope.launch {
            val outcome=withContext(Dispatchers.IO) {
                writeDebugReport(text){getApplication<Application>().contentResolver.openOutputStream(uri,"wt")}
            }
            mutable.value=mutable.value.copy(exportMessage=if(outcome.isSuccess)"Debug report saved." else "Could not save the report. Choose another location and try again.")
        }
    }
    fun captureInvalidInput() { solve() }
    fun cancel(){job?.cancel();mutable.value=mutable.value.copy(busy=false)}
}

package org.linesolver.base.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.linesolver.base.material.MaterialRepository
import org.linesolver.base.material.Webbing
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.util.UUID

/** Physical rig plus the explicitly saved prescribed-cut scenario; no person/global settings. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class RigConfiguration(
    val span:String,val leftHeight:String,val rightHeight:String,val pretensionKn:String,
    val sections:List<SectionDraft>,val kind:SetupKind=SetupKind.UNIFORM,val mode:Mode=Mode.SIMPLE,
    val cutEnabled:Boolean=false,val cutPosition:String="",val uniform:UniformGenerator?=null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val bungees:BungeeLayout?=null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val connections:ConnectionLayout?=null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val modelConnections:Boolean=false,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val showBungeeOptions:Boolean=false
) {
    fun applyTo(current:Draft)=current.copy(span=span,leftHeight=leftHeight,rightHeight=rightHeight,
        pretensionKn=pretensionKn,sections=sections,kind=kind,mode=mode,cutEnabled=cutEnabled,cutPosition=cutPosition,uniform=uniform,bungees=bungees,connections=connections,modelConnections=modelConnections,showBungeeOptions=showBungeeOptions)
    val activeSections get()=resolveSections(kind,sections,uniform,span)
    val materialSections get()=if(kind==SetupKind.UNIFORM && uniform!=null)
        listOf(SectionDraft(main=uniform.main,backup=uniform.backup)) else sections
    companion object {
        fun from(d:Draft)=RigConfiguration(d.span,d.leftHeight,d.rightHeight,d.pretensionKn,d.sections,d.kind,d.mode,d.cutEnabled,d.cutPosition,d.uniform,d.bungees,d.connections,d.modelConnections,d.showBungeeOptions)
    }
}
@Serializable data class SavedRig(val id:String,val name:String,val modifiedAt:Long,val configuration:RigConfiguration,
    val materialFingerprints:Map<String,String> = emptyMap())
@Serializable data class RigLibraryDocument(val schemaVersion:Int=2,val rigs:List<SavedRig> = emptyList())
fun rigHasChanges(d:Draft,saved:SavedRig?)=RigConfiguration.from(d)!=(saved?.configuration?:RigConfiguration.from(if(d.uniform!=null)freshGeneratorDraft() else Draft()))

/** Strict decoding protects unknown future mechanical fields from being discarded on a rewrite. */
object RigLibraryCodec {
    private val json=Json{encodeDefaults=true}
    fun decode(text:String):RigLibraryDocument {
        val doc=json.decodeFromString<RigLibraryDocument>(text)
        require(doc.schemaVersion in 1..6){"This rig library version is not supported. Stored data has been left unchanged."}
        require(doc.schemaVersion>=3 || doc.rigs.all{it.configuration.bungees==null}) { "Bungee rigs require library schema 3" }
        require(doc.schemaVersion>=4 || doc.rigs.all{it.configuration.connections==null && !it.configuration.modelConnections}) { "Connections require rig library schema 4" }
        require(doc.schemaVersion>=5 || doc.rigs.none{it.configuration.phase6Fields()}) { "Connector specifications/load states and bungee visibility require rig schema 5" }
        require(doc.schemaVersion>=6 || doc.rigs.none{it.configuration.uiRefinementFields()}) { "TypeX seam records require rig schema 6" }
        doc.rigs.forEach { val errors=StructuralConnections.errors(it.configuration.applyTo(Draft()));require(errors.isEmpty()){errors.joinToString("\n")} }
        require(doc.rigs.map{it.id}.distinct().size==doc.rigs.size){"Duplicate rig identifiers in library."}
        require(doc.rigs.map{it.name.lowercase(java.util.Locale.ROOT)}.distinct().size==doc.rigs.size){"Duplicate rig names in library."}
        doc.rigs.forEach { require(it.id.isNotBlank() && it.name.isNotBlank() && it.modifiedAt>=0){"Invalid rig metadata."} }
        require(doc.rigs.all{it.configuration.uniform==null ||
            (doc.schemaVersion>=2 && it.configuration.kind==SetupKind.UNIFORM && it.configuration.sections.isEmpty())}){"Invalid generator representation."}
        // v1 retains legacy first-section-only Uniform behavior. Migration changes no inputs.
        return doc.copy(schemaVersion=maxOf(doc.schemaVersion,2))
    }
    fun encode(doc:RigLibraryDocument):String {
        val version=when {doc.rigs.any{it.configuration.uiRefinementFields()}->6;doc.rigs.any{it.configuration.phase6Fields()}->5;doc.rigs.any{it.configuration.connections!=null || it.configuration.modelConnections}->4;doc.rigs.any{it.configuration.bungees!=null}->maxOf(3,doc.schemaVersion);else->doc.schemaVersion}
        val text=json.encodeToString(doc.copy(schemaVersion=version));decode(text);return text
    }
}

/** Mutations become visible only after the atomic disk writer succeeds. Failed reads never overwrite a library. */
class RigLibraryStore(read:()->String?,private val write:(String)->Unit,
    private val now:()->Long=System::currentTimeMillis,private val newId:()->String={UUID.randomUUID().toString()}) {
    var document:RigLibraryDocument=RigLibraryDocument();private set
    var readError:String?=null;private set
    init {
        try {document=read()?.let(RigLibraryCodec::decode)?:RigLibraryDocument()}
        catch(e:Exception){readError="Could not read the saved rig library. Stored data has been left unchanged. ${e.message}. Restore the exact rig/connector/material backup; no products were substituted."}
    }
    private fun name(value:String,except:String?=null):String {
        val clean=value.trim()
        require(clean.isNotEmpty() && clean.length<=80){"Use a rig name with 1–80 characters."}
        require(document.rigs.none{it.id!=except && it.name.equals(clean,ignoreCase=true)}){"That name already exists. Choose another name or update the loaded rig."}
        return clean
    }
    private fun persist(rigs:List<SavedRig>) {
        check(readError==null){readError!!}
        val next=document.copy(rigs=rigs,schemaVersion=when {rigs.any{it.configuration.uiRefinementFields()}->6;rigs.any{it.configuration.phase6Fields()}->5;rigs.any{it.configuration.connections!=null || it.configuration.modelConnections}->4;rigs.any{it.configuration.bungees!=null}->maxOf(3,document.schemaVersion);else->document.schemaVersion})
        write(RigLibraryCodec.encode(next));document=next
    }
    fun get(id:String)=document.rigs.firstOrNull{it.id==id}?:throw IllegalArgumentException("Saved rig no longer exists.")
    fun save(name:String,config:RigConfiguration,fingerprints:Map<String,String>,updateId:String?=null):SavedRig {
        val clean=name(name,updateId)
        if(updateId!=null)get(updateId)
        val entry=SavedRig(updateId?:newId(),clean,now(),config,fingerprints)
        require(updateId!=null || document.rigs.none{it.id==entry.id}){"Rig identifier collision. Try saving again."}
        persist(if(updateId==null)document.rigs+entry else document.rigs.map{if(it.id==updateId)entry else it})
        return entry
    }
    fun rename(id:String,value:String):SavedRig {
        val entry=get(id).copy(name=name(value,id),modifiedAt=now())
        persist(document.rigs.map{if(it.id==id)entry else it});return entry
    }
    fun duplicate(id:String,value:String):SavedRig {
        val original=get(id)
        return save(value,original.configuration,original.materialFingerprints)
    }
    fun exportJson()=RigLibraryCodec.encode(document)
    /** Additive import only: never overwrite a different revision of a saved rig. */
    fun importJson(text:String):Int {
        val incoming=RigLibraryCodec.decode(text)
        val added=mutableListOf<SavedRig>()
        for(entry in incoming.rigs) {
            val existing=document.rigs.firstOrNull{it.id==entry.id}
            require(existing==null || existing==entry) { "Rig ${entry.name} has a conflicting saved revision. Existing rigs were retained; import an independently named/identified copy." }
            if(existing!=null)continue
            require(document.rigs.none{it.name.equals(entry.name,true)}) { "Rig name ${entry.name} already exists. Rename the existing rig or import an independently named copy." }
            added+=entry
        }
        if(added.isNotEmpty())persist(document.rigs+added)
        return added.size
    }
    fun delete(id:String) {get(id);persist(document.rigs.filterNot{it.id==id})}
}

internal fun materialFingerprint(metadata:String)=MessageDigest.getInstance("SHA-256").digest(metadata.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
/** Imported records already carry the Phase 1 content identity, excluding catalogue default choice. */
internal fun selectionFingerprint(material:Webbing):String =
    if(material.metadata["model_origin"]?.jsonPrimitive?.contentOrNull=="imported")
        requireNotNull(material.metadata["catalogue_fingerprint"]?.jsonPrimitive?.contentOrNull).also {
            require(Regex("[0-9a-f]{64}").matches(it)) {"Invalid catalogue content identity"}
        }
    else materialFingerprint(material.metadata.toString())
fun RigConfiguration.fingerprints(materials:MaterialRepository):Map<String,String> = materialSections
    .flatMap{listOf(it.main,it.backup)}.filter{it.materialId.isNotEmpty()}.mapNotNull{selection->
        runCatching{selection.materialId to selectionFingerprint(materials.get(selection.materialId))}.getOrNull()
    }.toMap()
fun SavedRig.materialProblems(materials:MaterialRepository):List<String> = configuration.materialSections.flatMapIndexed { index,section ->
    listOf("main" to section.main,"backup" to section.backup).mapNotNull { (cable,selection) ->
        if(selection.materialId.isEmpty())null else {
            val material=runCatching{materials.get(selection.materialId)}.getOrNull()
            when {
                material==null || material.manufacturer!=selection.manufacturer -> "Section ${index+1} $cable material is unavailable: ${selection.materialId}."
                materialFingerprints[selection.materialId]?.let{it!=selectionFingerprint(material) && materials.previousRevisions(selection.materialId).none{old->materialFingerprint(old.toString())==it}}==true -> "Section ${index+1} $cable material data changed since saving: ${material.product}."
                else -> null
            }
        }
    }
}
fun RigConfiguration.materialSummary(materials:MaterialRepository,main:Boolean):String {
    val selections=(if(uniform!=null)materialSections else activeSections).map{if(main)it.main else it.backup}.distinct()
    return selections.joinToString(" / "){s->runCatching{materials.get(s.materialId).product}.getOrDefault(if(s.materialId.isBlank())"Not selected" else "Unavailable (${s.materialId})")}
}

/** Recognized source updates still require user confirmation; unknown revisions remain blocked. */
fun SavedRig.materialRevisionWarnings(materials:MaterialRepository):List<String> = materialFingerprints.mapNotNull{(id,hash)->
    val current=runCatching{materials.get(id)}.getOrNull()?:return@mapNotNull null
    if(hash!=selectionFingerprint(current) && materials.previousRevisions(id).any{materialFingerprint(it.toString())==hash})
        "${current.name}: verified material specifications were updated. Loading uses the revised mass/strength metadata; numerical results may change. The saved rig is not overwritten." else null
}

fun RigConfiguration.phase6Fields()=showBungeeOptions || connections?.let{(it.joints+it.detached).any{j->j.details.hardware.any{h->h.specification!=null || h.fingerprint!=null || h.role!=HardwareRole.unspecified || h.standingLoad!=LoadAssumption() || h.failedLoad!=LoadAssumption() || h.ratedInstallationConfirmed}}}==true

fun RigConfiguration.uiRefinementFields()=connections?.let{(it.joints+it.detached).any{j->j.details.typeX!=null}}==true

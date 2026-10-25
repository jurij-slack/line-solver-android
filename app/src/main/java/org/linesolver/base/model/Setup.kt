package org.linesolver.base.model

import kotlinx.serialization.Serializable
import org.linesolver.base.contact.*
import org.linesolver.base.material.MaterialRepository

@Serializable enum class Mode { SIMPLE, ADVANCED }
@Serializable enum class SetupKind { UNIFORM, MIXED }
@Serializable enum class ExtraUnit { PERCENT, METRES }
@Serializable data class Selection(val manufacturer:String="",val materialId:String="")
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class SectionDraft(val length:String="",val main:Selection=Selection(),val backup:Selection=Selection(),
    val extra:String="",val extraUnit:ExtraUnit=ExtraUnit.PERCENT,val connection:ConnectionBehavior=ConnectionBehavior.STOP,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val id:String="")
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class Draft(val mode:Mode=Mode.SIMPLE,val kind:SetupKind=SetupKind.UNIFORM,
    val span:String="",val leftHeight:String="",val rightHeight:String="",val mass:String="",val position:String="",
    val pretensionKn:String="",val sections:List<SectionDraft> = listOf(SectionDraft()),
    val cutEnabled:Boolean=false,val cutPosition:String="",val walkerHeight:String="",val leash:String="",
    val resistance:String="200",val gravity:String="9.81",val elements:String="8",val uniform:UniformGenerator?=null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val bungees:BungeeLayout?=null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val connections:ConnectionLayout?=null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val modelConnections:Boolean=false,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val showBungeeOptions:Boolean=false)
data class Geometry(val spanM:Double,val leftHeightM:Double,val rightHeightM:Double)
data class Section(val preparedLengthM:Double,val mainId:String,val backupId:String,val extraRatio:Double)
data class Walker(val massKg:Double,val referencePositionM:Double)
data class Cut(val referencePositionM:Double,val walkerHeightM:Double,val leashLengthM:Double) {
    // Preserve saved body-height / physical leash-to-harness semantics.
    val walkerGeometry get()=WalkerGeometry(walkerHeightM,leashLengthM)
}
data class LineSetup(val geometry:Geometry,val sections:List<Section>,val pretensionN:Double,val walker:Walker,
    val cut:Cut?,val contacts:ContactOptions,val gravity:Double,val elements:Int,
    val bungees:BungeeLayout?=null,val sectionIds:List<String> = emptyList(),val connections:ConnectionLayout?=null,val modelConnections:Boolean=false)
data class Validation(val setup:LineSetup?,val errors:List<String>)
fun String.number():Double?=trim().replace(',','.').toDoubleOrNull()?.takeIf{it.isFinite()}
fun SectionDraft.withExtraUnit(unit:ExtraUnit):SectionDraft {
    if(unit==extraUnit)return this
    val length=length.number();val amount=extra.number()
    val converted=if(length!=null && length>0 && amount!=null)
        if(unit==ExtraUnit.METRES) amount/100*length else amount/length*100 else null
    return copy(extraUnit=unit,extra=converted?.takeIf{it.isFinite()}?.toString()?:"")
}
fun Draft.validate(materials:MaterialRepository):Validation {
    val errors=mutableListOf<String>()
    errors+=StructuralConnections.errors(this)
    if(modelConnections)connections?.joints.orEmpty().flatMap{it.details.hardware}.forEach { h ->
        runCatching{ConnectorLibrary.resolve(h)}.getOrNull()?.let{if(!ConnectorLibrary.inactive(it) && it.massKg==null)errors+="${it.name}: physical mass unknown; supply a sourced custom revision or switch Model connections off"}
    }
    fun value(text:String,label:String,minimum:Double?=null,strict:Boolean=false):Double {
        val n=text.number();if(n==null || (minimum!=null && (if(strict) n<=minimum else n<minimum))) {
            errors+="$label: ${if(n==null) "enter a finite number" else "must be ${if(strict) "greater than" else "at least"} $minimum"}"; return Double.NaN
        };return n
    }
    val span=value(span,"Span (m)",0.0,true);val left=value(leftHeight,"Left anchor height (m)");val right=value(rightHeight,"Right anchor height (m)")
    val mass=value(mass,"Walker mass (kg)",0.0,true);val pos=value(position,"Walker reference position (m)",0.0,true)
    val tension=value(pretensionKn,"Main pretension (kN)",0.0,true)*1000
    if(!tension.isFinite()) errors+="Main pretension cannot be represented in N"
    if(uniform!=null) {
        if(kind!=SetupKind.UNIFORM || sections.isNotEmpty())errors+="Generator and explicit sections cannot both be active."
        errors+=uniform.errors(this.span)
    }
    val active=activeSections
    bungees?.let {
        errors+=it.errors(active,materials)
        if(cutEnabled && it.banks.isNotEmpty()) errors+="Catch/failure calculations are unavailable for bungee rigs: transient limiter engagement is not modeled"
    }
    if(active.isEmpty()) errors+="At least one main/backup section is required"
    val resolved=active.mapIndexed { i,s ->
        val length=value(s.length,"Section ${i+1} prepared length (m)",0.0,true)
        fun selection(sel:Selection,kind:String):String {
            val m=runCatching {materials.get(sel.materialId)}.getOrNull()
            if(m==null || m.manufacturer!=sel.manufacturer) errors+="Section ${i+1} $kind: choose manufacturer and webbing"
            else if((materials as? org.linesolver.base.material.catalogue.CatalogueMaterialRepository)?.document?.let{doc->doc.models.firstOrNull{it.id==m.id}?.let{model->doc.products.first{it.id==model.productId}.category}}==org.linesolver.base.material.catalogue.ProductCategory.bungee)
                errors+="Section ${i+1} $kind: insert this bungee through the bungee editor; it is not a webbing section"
            else if(m.density==null || m.density!!<0 || !m.density!!.isFinite()) errors+="${m.name}: density is missing from the source data; cannot solve"
            return sel.materialId
        }
        val main=selection(s.main,"main");val backup=selection(s.backup,"backup")
        val extra=value(s.extra,"Section ${i+1} backup extra",0.0)
        val ratio=if(s.extraUnit==ExtraUnit.PERCENT) extra/100 else extra/length
        if(!ratio.isFinite() || !(length*(1+ratio)).isFinite()) errors+="Section ${i+1} backup length cannot be represented in m"
        Section(length,main,backup,ratio)
    }
    if(!resolved.sumOf{it.preparedLengthM}.isFinite()) errors+="Total prepared length cannot be represented in m"
    if(pos>=resolved.sumOf{it.preparedLengthM}) errors+="Walker must be inside the prepared line"
    val cut=if(cutEnabled) {
        val q=value(cutPosition,"Cut reference position (m)",0.0,true)
        val height=value(walkerHeight,"Walker height (m)",0.0,true);val leash=value(leash,"Leash length (m)",0.0)
        if(q>=resolved.sumOf{it.preparedLengthM}) errors+="Cut must be inside the prepared line"
        if(q==pos) errors+="Cut exactly at the walker is unsupported"
        if(leash<.5*height) errors+="Leash must reach the standing harness (at least half walker height)"
        Cut(q,height,leash)
    } else null
    val r=value(resistance,"Contact resistance (N)",0.0);val g=value(gravity,"Gravity (m/s²)",0.0,true)
    val mesh=elements.toIntOrNull();if(mesh==null || mesh<2 || mesh>32) errors+="Mesh: enter 2–32 elements per section (mobile resource bound)"
    if(resolved.size>100) errors+="Milestone mobile solver supports at most 100 sections"
    return Validation(if(errors.isEmpty()) LineSetup(Geometry(span,left,right),resolved,tension,Walker(mass,pos),cut,
        ContactOptions(r,active.dropLast(1).map{it.connection}),g,mesh!!,bungees,StructuralConnections.sectionIds(this),connections,modelConnections) else null,errors)
}

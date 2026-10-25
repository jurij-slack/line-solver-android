package org.linesolver.base.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Strings retain partial edits and the existing body-height / physical leash convention. */
@Serializable data class WalkerPreferences(val mass:String="",val height:String="",val leash:String="",val position:String="") {
    fun applyTo(d:Draft)=d.copy(mass=mass,walkerHeight=height,leash=leash,position=position)
    companion object { fun from(d:Draft)=WalkerPreferences(d.mass,d.walkerHeight,d.leash,d.position) }
}

/** Only the three previously exposed global controls. Numerical algorithm constants stay unchanged. */
@Serializable data class ModelPreferences(val gravity:String="9.81",val elements:String="8",val resistance:String="200",val modelConnections:Boolean=false,val showBungeeOptions:Boolean=false) {
    fun errors():List<String> = buildList {
        if(gravity.number()?.let{it>0}!=true)add("Gravity: enter a finite number greater than 0 m/s².")
        if(elements.toIntOrNull() !in 2..32)add("Mesh: enter 2–32 whole elements per section (mobile resource bound).")
        if(resistance.number()?.let{it>=0}!=true)add("Ring resistance: enter a finite number at least 0 N.")
    }
    fun applyTo(d:Draft):Draft { require(errors().isEmpty());return d.copy(gravity=gravity,elements=elements,resistance=resistance,modelConnections=modelConnections,showBungeeOptions=showBungeeOptions) }
    companion object { fun from(d:Draft)=ModelPreferences(d.gravity,d.elements,d.resistance,d.modelConnections,d.showBungeeOptions) }
}

/** Separate keys keep global preferences independent of the activity's rig draft. */
class AppPreferences(private val read:(String)->String?,private val write:(String,String)->Unit) {
    private val json=Json{ignoreUnknownKeys=true;encodeDefaults=true}
    fun restore(legacy:Draft):Draft {
        val walker=read("walker_v1")?.let{runCatching{json.decodeFromString<WalkerPreferences>(it)}.getOrNull()}
            ?:WalkerPreferences.from(legacy)
        val model=read("model_v1")?.let{runCatching{json.decodeFromString<ModelPreferences>(it)}.getOrNull()}
            ?:ModelPreferences.from(legacy)
        // Preserve invalid legacy text visibly; ordinary Draft validation prevents a solve.
        val restored=walker.applyTo(legacy).copy(gravity=model.gravity,elements=model.elements,resistance=model.resistance,modelConnections=if(legacy.connections!=null || legacy.span.isNotBlank())legacy.modelConnections else model.modelConnections,showBungeeOptions=if(legacy.connections!=null || legacy.span.isNotBlank())legacy.showBungeeOptions else model.showBungeeOptions)
        save(restored)
        return restored
    }
    fun save(d:Draft) {
        write("walker_v1",json.encodeToString(WalkerPreferences.from(d)))
        val model=ModelPreferences.from(d)
        if(model.errors().isEmpty())write("model_v1",json.encodeToString(model))
    }
}

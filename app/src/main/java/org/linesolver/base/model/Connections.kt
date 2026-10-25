package org.linesolver.base.model

import kotlinx.serialization.Serializable
import org.linesolver.base.energy.*
import java.util.UUID

@Serializable enum class EndPort { start, end }
@Serializable enum class JointKind { serial, branch, weblock_termination, anchor, explicit_cross_line }
@Serializable data class ComponentEnd(val instanceId:String, val cable:Cable, val port:EndPort)
/** Future product references require an immutable revision; empty slots have no mechanical effect. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class HardwareItem(val id:String, val productId:String?=null, val revision:String?=null,
    val configuration:String="", val attachmentPorts:List<ComponentEnd> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val specification:ConnectorSpec?=null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val fingerprint:String?=null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val role:HardwareRole=HardwareRole.unspecified,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val standingLoad:LoadAssumption=LoadAssumption(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val failedLoad:LoadAssumption=LoadAssumption(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val ratedInstallationConfirmed:Boolean=false)
@Serializable enum class SeamState { intact, broken }
/** Transverse annotation only: never compared with longitudinal tension or webbing MBS. */
@Serializable data class TypeXSeam(val state:SeamState=SeamState.intact,
    val assembly:String="", val source:String="", val strengthN:Double?=null, val lowerBound:Boolean=false)
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class JointDetails(val notes:String="", val hardware:List<HardwareItem> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val typeX:TypeXSeam?=null)
@Serializable data class StructuralJoint(val id:String, val ends:List<ComponentEnd>, val kind:JointKind,
    val details:JointDetails=JointDetails(), val declaredCrossLine:Boolean=false)
@Serializable data class ConnectionLayout(val schemaVersion:Int=1, val joints:List<StructuralJoint> = emptyList(),
    val detached:List<StructuralJoint> = emptyList())
data class PhysicalInterface(val key:String, val ends:List<ComponentEnd>, val kind:JointKind)
data class LabeledJoint(val joint:StructuralJoint, val label:String)

object StructuralConnections {
    private fun opaque(text:String)=UUID.nameUUIDFromBytes(text.toByteArray(Charsets.UTF_8)).toString()
    fun sectionIds(d:Draft)=d.activeSections.mapIndexed { i,s -> s.id.ifBlank { opaque("legacy-section:$i") } }
    /** Symbolic equivalent of the Phase 4 endpoint graph, independent of any numerical mesh. */
    fun interfaces(d:Draft):List<PhysicalInterface> {
        val ids=sectionIds(d);if(ids.isEmpty())return emptyList()
        require(ids.distinct().size==ids.size) { "Duplicate section instance IDs" }
        val banks=d.bungees?.banks.orEmpty()
        require(d.bungees==null || d.bungees.schemaVersion==1) { "Unsupported bungee schema" }
        require(banks.all{it.assemblies.isNotEmpty()}) { "Each bungee bank requires physical assemblies" }
        val bankIds=banks.map{it.id}+banks.flatMap{it.assemblies.map{a->a.id}}
        require(bankIds.all{it.isNotBlank()} && bankIds.distinct().size==bankIds.size && bankIds.none{it in ids}) { "Invalid/duplicate component IDs" }
        val groups=linkedMapOf<String,MutableList<ComponentEnd>>()
        fun add(key:String,id:String,cable:Cable,port:EndPort) { groups.getOrPut(key){mutableListOf()}.add(ComponentEnd(id,cable,port)) }
        fun split(i:Int)=when(i){0->"anchor:left";ids.size->"anchor:right";else->"split:${ids[i-1]}:${ids[i]}"}
        val starts=mutableMapOf<Pair<Int,Cable>,String>();val ends=mutableMapOf<Pair<Int,Cable>,String>()
        for(i in ids.indices) for(c in Cable.entries) { starts[i to c]=split(i);ends[i to c]=split(i+1) }
        val serial=banks.filter{it.placement!=BungeePlacement.parallel_section}
        for((key,chain) in serial.groupBy{Triple(it.placement,it.sectionId,it.cable)}) {
            val (placement,sectionId,cable)=key
            val i=if(placement==BungeePlacement.series_right)ids.lastIndex else ids.indexOf(sectionId)
            require(i>=0) { "Bungee references a missing section; move or remove it" }
            val web="termination:${chain.last().id}:${ids[i]}:$cable:$placement"
            if(placement==BungeePlacement.series_right)ends[i to cable]=web else starts[i to cable]=web
            var previous=if(placement==BungeePlacement.series_right)web else split(i)
            chain.forEachIndexed { j,b ->
                val next=if(j==chain.lastIndex) { if(placement==BungeePlacement.series_right)split(ids.size) else web }
                    else "bank:${b.id}:${chain[j+1].id}"
                for(a in b.assemblies) { add(previous,a.id,cable,EndPort.start);add(next,a.id,cable,EndPort.end) }
                previous=next
            }
        }
        for(i in ids.indices) for(c in Cable.entries) { add(starts.getValue(i to c),ids[i],c,EndPort.start);add(ends.getValue(i to c),ids[i],c,EndPort.end) }
        for(b in banks.filter{it.placement==BungeePlacement.parallel_section}) {
            val i=ids.indexOf(b.sectionId);require(i>=0) { "Parallel bungee references a missing section" }
            for(a in b.assemblies) { add(starts.getValue(i to b.cable),a.id,b.cable,EndPort.start);add(ends.getValue(i to b.cable),a.id,b.cable,EndPort.end) }
        }
        // Traverse main then backup, start then end, in installed section/bank order. Shared vertices appear once.
        val order=mutableListOf<String>()
        for(c in Cable.entries) for(i in ids.indices) {
            serial.filter{it.cable==c && it.placement==BungeePlacement.series_before && it.sectionId==ids[i]}.forEach { b ->
                for(port in EndPort.entries) groups.entries.firstOrNull{(_,v)->v.any{it.instanceId==b.assemblies.first().id && it.port==port}}?.key?.let{order+=it}
            }
            order+=starts.getValue(i to c);order+=ends.getValue(i to c)
            if(i==ids.lastIndex)serial.filter{it.cable==c && it.placement==BungeePlacement.series_right}.forEach { b ->
                for(port in EndPort.entries) groups.entries.firstOrNull{(_,v)->v.any{it.instanceId==b.assemblies.first().id && it.port==port}}?.key?.let{order+=it}
            }
        }
        return (order+groups.keys).distinct().map { key ->
            val ports=groups.getValue(key).distinct()
            val kind=when {key.startsWith("anchor:")->JointKind.anchor;key.startsWith("termination:")->JointKind.weblock_termination
                ports.size>2->JointKind.branch;else->JointKind.serial}
            PhysicalInterface(key,ports,kind)
        }
    }
    /** Exact incidences first. Parallel membership changes can reuse a unique containing interface.
     * Replaced/ambiguous interfaces NEVER inherit assignments. Annotated old records are retained detached.
     */
    fun reconcile(d:Draft):ConnectionLayout {
        val previous=d.connections ?: ConnectionLayout()
        require(previous.schemaVersion==1) { "Unsupported connection schema" }
        val stored=previous.joints+previous.detached
        require(stored.all{it.id.isNotBlank() && it.ends.size>=2 && it.ends.distinct().size==it.ends.size} && stored.map{it.id}.distinct().size==stored.size) { "Invalid/duplicate stored joint identity or incidence" }
        val remaining=previous.joints.toMutableList()
        val joints=interfaces(d).map { p ->
            val exact=remaining.filter{it.ends.toSet()==p.ends.toSet()}
            val subset=remaining.filter { old -> val a=old.ends.toSet();val b=p.ends.toSet();
                (!old.declaredCrossLine || p.ends.map{it.cable}.distinct().size==2) && (old.details.typeX==null || typeXEligible(d,p.ends,p.kind)) && old.details.hardware.all{b.containsAll(it.attachmentPorts)} && a.size>=2 && b.size>=2 && (a.containsAll(b) || b.containsAll(a)) && (old.kind==JointKind.anchor)==(p.kind==JointKind.anchor) }
            val found=exact.singleOrNull() ?: subset.singleOrNull()
            if(found!=null)remaining.remove(found)
            val declared=found?.declaredCrossLine==true && p.ends.map{it.cable}.distinct().size==2
            StructuralJoint(found?.id ?: if(d.connections==null)opaque("joint:"+p.ends.sortedWith(compareBy({it.instanceId},{it.cable.name},{it.port.name})).joinToString()) else UUID.randomUUID().toString(),
                p.ends,if(declared)JointKind.explicit_cross_line else p.kind,found?.details ?: JointDetails(),declared)
        }
        val detached=(previous.detached+remaining.filter{it.details!=JointDetails() || it.declaredCrossLine}).distinctBy{it.id}
        return ConnectionLayout(joints=joints,detached=detached)
    }
    fun labeled(d:Draft):List<LabeledJoint> {
        var index=0
        return reconcile(d).joints.map { j -> LabeledJoint(j,if(j.kind==JointKind.anchor) {
            if(j.ends.any{it.port==EndPort.start})"Left attachment" else "Right attachment"
        } else "C${++index}") }
    }
    fun typeXEligible(d:Draft,ends:List<ComponentEnd>,kind:JointKind):Boolean {
        val ids=sectionIds(d)
        return kind!=JointKind.anchor && Cable.entries.all { cable ->
            ids.zipWithNext().any { (left,right) ->
                ComponentEnd(left,cable,EndPort.end) in ends && ComponentEnd(right,cable,EndPort.start) in ends
            }
        }
    }
    fun errors(d:Draft):List<String> = buildList {
        runCatching{interfaces(d)}.exceptionOrNull()?.let{add(it.message ?: "Invalid physical graph");return@buildList}
        val layout=d.connections ?: return@buildList
        if(layout.schemaVersion!=1)add("Unsupported connection schema")
        val all=layout.joints+layout.detached
        if(layout.joints.map{it.ends.toSet()}.distinct().size!=layout.joints.size)add("Duplicate physical interface records")
        if(all.any{it.id.isBlank()} || all.map{it.id}.distinct().size!=all.size)add("Joint IDs must be unique and nonempty")
        val slots=all.flatMap{it.details.hardware}
        if(slots.any{it.id.isBlank()} || slots.map{it.id}.distinct().size!=slots.size)add("Hardware item IDs must be unique and nonempty")
        for(j in all) {
            j.details.typeX?.let { seam ->
                if(!typeXEligible(d,j.ends,j.kind) && j in layout.joints)add("${j.id}: TypeX requires a shared main/backup section boundary")
                if(seam.strengthN?.let{!it.isFinite() || it<=0}==true)add("${j.id}: seam strength must be finite and positive")
                if(seam.strengthN!=null && (seam.assembly.isBlank() || seam.source.isBlank()))add("${j.id}: seam strength requires assembly and source")
            }
            if(j.ends.size<2 || j.ends.distinct().size!=j.ends.size)add("${j.id}: invalid/duplicate incident ports")
            for(h in j.details.hardware) {
                if((h.productId==null)!=(h.revision==null))add("${j.id}: hardware reference needs both product ID and revision")
                runCatching{ConnectorLibrary.resolve(h)}.fold({spec->
                    if(spec?.category==ConnectorCategory.sewn_transverse && !j.declaredCrossLine && j.details.typeX==null)add("${j.id}: transverse sewing requires an explicit main-to-backup joint")
                    if(spec?.category==ConnectorCategory.rigid_anchor_shackle && j.kind !in listOf(JointKind.anchor,JointKind.weblock_termination))add("${j.id}: rigid shackle is anchor/weblock hardware, not an intermediate selector item")
                    if(spec?.category==ConnectorCategory.accessory && j.details.hardware.count{it.productId=="bc_4mm_soft_double_wrap"}!=2)add("${j.id}: protector pack requires exactly two matching kit shackles")
                },{add("${j.id}: ${it.message}")})
                if(listOf(h.standingLoad,h.failedLoad).any{it.status!=HardwareLoad.unknown && it.source.isBlank()})add("${j.id}: explicit load status requires an assumption/source")
                if(h.role==HardwareRole.backup && j.ends.none{it.cable==Cable.BACKUP})add("${j.id}: backup role requires an incident backup end")
                if(h.attachmentPorts.distinct().size!=h.attachmentPorts.size || !j.ends.containsAll(h.attachmentPorts))add("${j.id}: hardware attachment port is not incident to this joint")
            }
        }
        runCatching { interfaces(d) }.fold({ physical ->
            for(j in layout.joints) {
                val p=physical.singleOrNull{it.ends.toSet()==j.ends.toSet()}
                if(p==null)add("${j.id}: connection incidence is stale; reconcile edits before saving")
                else if(j.declaredCrossLine) {
                    if(p.kind==JointKind.anchor || p.ends.map{it.cable}.distinct().size!=2 || j.kind!=JointKind.explicit_cross_line)
                        add("${j.id}: cross-line declarations require an existing shared internal structural junction")
                } else if(j.kind!=p.kind)add("${j.id}: joint kind does not match physical topology")
            }
            if(layout.joints.size!=physical.size)add("Connection graph is incomplete")
        },{add(it.message ?: "Invalid connection graph")})
    }
    /** Map named physical ports to solver-owned endpoint nodes, never mesh interior/contact nodes. */
    fun node(j:StructuralJoint,d:Draft,n:Network):Int {
        return node(j,sectionIds(d),n)
    }
    fun node(j:StructuralJoint,ids:List<String>,n:Network):Int {
        val rubber=n.bungeePaths.flatMap{it.elementIndices}.toSet()
        val nodes=j.ends.map { end ->
            val path=n.bungeePaths.firstOrNull{it.instance.id==end.instanceId}
            val candidates=path?.elementIndices ?: n.elements.indices.filter{it !in rubber && n.elements[it].section==ids.indexOf(end.instanceId) && n.elements[it].cable==end.cable}
            require(candidates.isNotEmpty()) { "Missing physical endpoint ${end.instanceId}" }
            if(end.port==EndPort.start)n.elements[candidates.minBy{n.elements[it].from}].a else n.elements[candidates.maxBy{n.elements[it].to}].b
        }.distinct()
        require(nodes.size==1) { "Unsupported cross-line seam: endpoints are not the same structural node" }
        return nodes.single()
    }
    fun declareCrossLine(d:Draft,id:String):Draft {
        val layout=reconcile(d);val joint=layout.joints.single{it.id==id}
        require(joint.kind!=JointKind.anchor && joint.ends.map{it.cable}.distinct().size==2) { "Arbitrary internal seams are unsupported; choose an existing shared main/backup split" }
        return d.copy(connections=layout.copy(joints=layout.joints.map{if(it.id==id)it.copy(kind=JointKind.explicit_cross_line,declaredCrossLine=true) else it}))
    }
}


data class NetworkJoint(val joint:StructuralJoint,val node:Int)
fun Network.withStructuralJoints(setup:LineSetup):Network {
    val layout=setup.connections ?: return this
    require(layout.schemaVersion==1) { "Unsupported structural connection schema" }
    val joints=layout.joints.map{NetworkJoint(it,StructuralConnections.node(it,setup.sectionIds,this))}
    val masses=if(!setup.modelConnections)emptyList() else joints.flatMap { j -> j.joint.details.hardware.mapNotNull { h ->
        val spec=ConnectorLibrary.resolve(h) ?: return@mapNotNull null
        if(ConnectorLibrary.inactive(spec))return@mapNotNull null
        val mass=spec.massKg ?: error("${spec.name}: physical mass unknown; provide a sourced custom revision or switch Model connections off")
        require(spec.category!=ConnectorCategory.accessory || j.joint.details.hardware.count{it.productId=="bc_4mm_soft_double_wrap"}==2) { "Kit protector pack requires exactly two matching BC 4 mm double-wrap items" }
        ConcentratedMass(h.id,j.node,mass)
    } }
    return copy(physicalJoints=joints,connectorMasses=masses)
}

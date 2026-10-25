package org.linesolver.base.model

import org.linesolver.base.energy.*
import org.linesolver.base.solver.StaticState
import kotlin.math.*

data class ConnectorDiagnostic(val jointId:String,val itemId:String,val name:String,val role:HardwareRole,val assumedLoad:LoadAssumption,
    val associatedPortTensionN:Double?,val individualLoadN:Double?,val comparison:String,val warning:String)
/** No inferred load sharing: all vectors are the existing material endpoint forces. */
fun StaticState.connectorDiagnostics(d:Draft):List<ConnectorDiagnostic> {
    if(!d.modelConnections)return emptyList()
    val rubber=network.bungeePaths.flatMap{it.elementIndices}.toSet()
    val current=StructuralConnections.reconcile(d).joints.associateBy{it.id}
    return network.physicalJoints.flatMap { location ->
        val j=current[location.joint.id] ?: location.joint
        if(j.kind==JointKind.anchor)return@flatMap emptyList<ConnectorDiagnostic>()
        j.details.hardware.mapNotNull { h ->
            val spec=runCatching{ConnectorLibrary.resolve(h)}.getOrNull() ?: return@mapNotNull null
            if(ConnectorLibrary.inactive(spec))return@mapNotNull null
            val load=if(network.broken==null)h.standingLoad else h.failedLoad
            val cable=when(h.role){HardwareRole.main->Cable.MAIN;HardwareRole.backup->Cable.BACKUP;else->null}
            val incident=network.elements.indices.filter{val e=network.elements[it];(e.a==location.node || e.b==location.node) && (cable==null || e.cable==cable)}
            val tension=incident.maxOfOrNull{value.tensions[it]}
            // Strict axial two-ended single-item limiting case. Shared four-port/parallel/taut rubber paths remain unknown.
            val all=network.elements.indices.filter{val e=network.elements[it];e.a==location.node || e.b==location.node}
            var individual:Double?=null
            if(j.ends.size==2 && j.details.hardware.size==1 && all.size==2 && all.none{it in rubber} && j.kind!=JointKind.explicit_cross_line && h.ratedInstallationConfirmed && spec.loadingDirection=="axial") {
                val vectors=all.map { i -> val e=network.elements[i];val other=if(e.a==location.node)e.b else e.a
                    val dx=r[2*other]-r[2*location.node];val dy=r[2*other+1]-r[2*location.node+1];val L=hypot(dx,dy)
                    if(L>0)Point(dx/L,dy/L) else Point(0.0,0.0) }
                val first=value.tensions[all[0]];val second=value.tensions[all[1]]
                if(vectors[0].x*vectors[1].x+vectors[0].y*vectors[1].y < -1+1e-10 && abs(first-second)<=max(1e-6,1e-8*max(first,second)))individual=max(first,second)
            }
            val warning=if(load.status==HardwareLoad.unloaded && (tension ?: 0.0)>1.0)"User-assumed unloaded item has appreciable associated ${cable ?: "joint"} port tension. Common-node mechanics cannot prove it is unloaded." else ""
            val rating=spec.mbsN ?: spec.blN
            val term=if(spec.mbsN!=null)"MBS" else "BL (published breaking load; not statistical MBS)"
            val comparison=when {
                load.status==HardwareLoad.unloaded->"User-assumed unloaded in this state; no strength comparison. This is not a disconnected or proven unloaded webbing."
                individual==null->"Individual connector load unknown; no strength margin or aggregate capacity. Associated port tension is not an individual hardware force."
                rating==null->"Axial connector load known; matching exact breaking rating unknown. Lower bounds are not exact failure strengths."
                else->"Axial limiting case load ${individual/1000} kN ${if(individual>rating)"exceeds" else "is below"} published $term ${rating/1000} kN; no safety factor or certification inferred."
            }
            ConnectorDiagnostic(j.id,h.id,spec.name,h.role,load,tension,individual,comparison,warning)
        }
    }
}
fun Draft.connectorMassSignature()=if(!modelConnections)emptyList() else connections?.joints.orEmpty().flatMap{j->j.details.hardware.mapNotNull{h->
    runCatching{ConnectorLibrary.resolve(h)}.getOrNull()?.takeUnless(ConnectorLibrary::inactive)?.let{Triple(j.id,h.id,it.massKg)} }}

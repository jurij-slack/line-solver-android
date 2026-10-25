package org.linesolver.base.energy

import org.linesolver.base.material.MaterialRepository
import org.linesolver.base.model.*
import org.linesolver.base.solver.StaticState
import kotlin.math.*

data class BungeePath(val instance:InstalledBungee,val elementIndices:List<Int>,val limitM:Double?) {
    fun length(n:Network,r:DoubleArray)=elementIndices.sumOf { i -> val e=n.elements[i];hypot(r[2*e.b]-r[2*e.a],r[2*e.b+1]-r[2*e.a+1]) }
    /** c=Lpath-Llimit <= 0. One constraint for the WHOLE folded assembly path. */
    fun constraint(n:Network,r:DoubleArray,hessian:Boolean=true):PathConstraint {
        val grad=DoubleArray(r.size);val h=if(hessian)Array(r.size){DoubleArray(r.size)} else emptyArray()
        for(i in elementIndices) {
            val e=n.elements[i];val dx=r[2*e.b]-r[2*e.a];val dy=r[2*e.b+1]-r[2*e.a+1];val l=hypot(dx,dy)
            require(l>1e-12) { "Collapsed bungee segment cannot define a limiter direction" }
            val u=doubleArrayOf(dx/l,dy/l)
            for(p in 0..1) { grad[2*e.a+p]-=u[p];grad[2*e.b+p]+=u[p]
                if(hessian)for(q in 0..1) { val v=((if(p==q)1.0 else 0.0)-u[p]*u[q])/l
                    h[2*e.a+p][2*e.a+q]+=v;h[2*e.b+p][2*e.b+q]+=v
                    h[2*e.a+p][2*e.b+q]-=v;h[2*e.b+p][2*e.a+q]-=v
                }
            }
        }
        return PathConstraint(length(n,r)-requireNotNull(limitM),grad,h)
    }
}
data class PathConstraint(val excessM:Double,val gradient:DoubleArray,val hessian:Array<DoubleArray>)
data class BungeeResult(val id:String,val pathLengthM:Double,val strain:Double,val rubberMinN:Double,val rubberMaxN:Double,
    val limiterActive:Boolean,val limiterReactionN:Double?,val massKg:Double,val elasticJ:Double)
fun StaticState.bungeeResults()=network.bungeePaths.map { p ->
    val indices=p.elementIndices;val length=p.length(network,r)
    BungeeResult(p.instance.id,length,length/(p.instance.physicalRestM/p.instance.equalLegs)-1,
        indices.minOf{value.tensions[it]},indices.maxOf{value.tensions[it]},p.limitM?.let{abs(length-it)<=1e-8}?:false,
        limiterReactionsN[p.instance.id],indices.sumOf{network.elements[it].mass},indices.sumOf { network.elements[it].let{e->e.rest*e.material.energy(value.strains[it])} })
}

/** Inserts physical banks without transporting any webbing inventory through their endpoints.
 * Each independent rubber path is meshed and has one whole-path length constraint.
 * Existing shared section joints remain; main/backup receive separate cable-specific insertions.
 */
fun buildBungeeNetwork(setup:LineSetup,materials:MaterialRepository,active:List<Double>,loaded:Boolean):Network {
    val layout=requireNotNull(setup.bungees)
    require(layout.schemaVersion==1)
    val identities=layout.banks.map{it.id}+layout.banks.flatMap{it.assemblies.map{a->a.id}}
    require(identities.all{it.isNotBlank()} && identities.distinct().size==identities.size) { "Bungee banks/instances require unique stable IDs" }
    require(setup.sectionIds.size==setup.sections.size && setup.sectionIds.distinct().size==setup.sectionIds.size) { "Bungee placement requires stable section IDs" }
    require(layout.banks.size<=32 && layout.banks.sumOf{it.assemblies.size}<=64) { "Static bungee resource limit exceeded" }
    val base=buildNetwork(setup.copy(bungees=null,connections=null),materials,active,loaded)
    val r=base.coordinates.toMutableList();val elements=base.elements.toMutableList();val paths=mutableListOf<BungeePath>()
    fun node(x:Double,y:Double):Int { val id=r.size/2;r+=x;r+=y;return id }
    fun addBank(bank:BungeeBank,a:Int,b:Int,section:Int) {
        require(bank.assemblies.isNotEmpty())
        for(instance in bank.assemblies) {
            val material=instance.material(materials)
            val grid=materialGrid(setup.elements)
            val chord=hypot(r[2*b]-r[2*a],r[2*b+1]-r[2*a+1]);val rest=material.effectiveRestM
            val sag=max(chord*.01,sqrt(max(rest*rest-chord*chord,0.0))*.4)
            val ids=grid.map { f -> when(f) { 0.0->a;1.0->b;else->node(r[2*a]*(1-f)+r[2*b]*f,r[2*a+1]*(1-f)+r[2*b+1]*f-4*sag*f*(1-f)) } }
            val indices=mutableListOf<Int>()
            for(j in 0 until grid.lastIndex) { val f=grid[j+1]-grid[j];indices+=elements.size
                elements+=Element(ids[j],ids[j+1],rest*f,material.physicalMassKg*f,material,section,bank.cable,grid[j],grid[j+1]) }
            paths+=BungeePath(instance,indices,instance.limiterPhysicalLengthM?.takeIf{instance.limiterEnabled}?.div(instance.equalLegs))
        }
    }
    val serial=layout.banks.filter{it.placement!=BungeePlacement.parallel_section}
    for((key,banks) in serial.groupBy { Triple(it.placement,it.sectionId,it.cable) }) {
        val (placement,sectionId,cable)=key
        val section=if(placement==BungeePlacement.series_right)active.lastIndex else setup.sectionIds.indexOf(sectionId).also { require(it>=0){"Referenced bungee section is missing"} }
        val candidates=elements.indices.filter { elements[it].section==section && elements[it].cable==cable }
        val end=if(placement==BungeePlacement.series_right) elements[candidates.maxBy{elements[it].to}].b else elements[candidates.minBy{elements[it].from}].a
        val other=if(placement==BungeePlacement.series_right) base.splits[section] else base.splits[section+1]
        val total=banks.sumOf { bank -> bank.assemblies.maxOf { it.physicalRestM/it.equalLegs } }
        val chord=hypot(r[2*end]-r[2*other],r[2*end+1]-r[2*other+1])
        require(chord>0)
        // Start guess only; all inserted nodes are free in equilibrium. Never clip solved geometry.
        val ratio=min(total/chord,.8)
        val x=r[2*end]*(1-ratio)+r[2*other]*ratio;val y=r[2*end+1]*(1-ratio)+r[2*other+1]*ratio
        val webbingEndpoint=node(x,y)
        for(i in candidates) { val e=elements[i];elements[i]=if(placement==BungeePlacement.series_right && e.b==end)e.copy(b=webbingEndpoint)
            else if(placement==BungeePlacement.series_before && e.a==end)e.copy(a=webbingEndpoint) else e }
        var start=if(placement==BungeePlacement.series_right)webbingEndpoint else end
        val finish=if(placement==BungeePlacement.series_right)end else webbingEndpoint
        var accumulated=0.0
        for((j,bank) in banks.withIndex()) { accumulated+=bank.assemblies.maxOf{it.physicalRestM/it.equalLegs}
            val f=accumulated/total
            val next=if(j==banks.lastIndex)finish else node(r[2*start]*(1-f)+r[2*finish]*f,r[2*start+1]*(1-f)+r[2*finish+1]*f)
            addBank(bank,start,next,section);start=next
        }
    }
    for(bank in layout.banks.filter{it.placement==BungeePlacement.parallel_section}) {
        val section=setup.sectionIds.indexOf(bank.sectionId);require(section>=0)
        val candidates=base.elements.indices.filter { base.elements[it].section==section && base.elements[it].cable==bank.cable }
        addBank(bank,elements[candidates.minBy{elements[it].from}].a,elements[candidates.maxBy{elements[it].to}].b,section)
    }
    return base.copy(coordinates=r.toDoubleArray(),elements=elements,bungeePaths=paths).withStructuralJoints(setup)
}

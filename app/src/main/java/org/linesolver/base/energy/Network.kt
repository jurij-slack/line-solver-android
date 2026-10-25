package org.linesolver.base.energy

import org.linesolver.base.material.*
import org.linesolver.base.model.*
import org.linesolver.base.contact.*
import kotlin.math.*

data class ConcentratedMass(val itemId:String,val node:Int,val massKg:Double)
data class Point(val x:Double,val y:Double)
enum class Cable { MAIN, BACKUP }
data class Element(val a:Int,val b:Int,val rest:Double,val mass:Double,val material:Material,val section:Int,val cable:Cable,val from:Double,val to:Double)
data class Network(val coordinates:DoubleArray,val elements:List<Element>,val splits:List<Int>,val attachment:LeashAttachment?,val walkerMass:Double,
    val gravity:Double,val span:Double,val walkerGeometry:WalkerGeometry?=null,
    val posture:WalkerPosture=WalkerPosture.STANDING,val broken:Pair<Int,Int>?=null,
    val bungeePaths:List<BungeePath> = emptyList(),val physicalJoints:List<NetworkJoint> = emptyList(),val connectorMasses:List<ConcentratedMass> = emptyList()) {
    /** Compatibility accessor: node of the ring/load, never the walker COM. */
    val walker get()=attachment?.node
    val personOffset get()=walkerGeometry?.comOffset(posture)?:0.0
    val structuralConnections get()=splits.drop(1).dropLast(1)
    /** Ideal point ring: separate material nodes, a removable contact constraint, never a split. */
    val ringContactPair get()=attachment?.takeIf{posture==WalkerPosture.LEASH_SUPPORTED && it.contacts.main.threaded && it.contacts.backup.threaded && it.mainNode!=it.backupNode}?.let{it.mainNode to it.backupNode}
    fun evaluate(r:DoubleArray,trial:Boolean=false,hessian:Boolean=false):Evaluation {
        val grad=DoubleArray(r.size);val sparse=if(hessian && r.size>512)Array(r.size){mutableMapOf<Int,Double>()} else null
        val H=if(hessian && sparse==null) Array(r.size){DoubleArray(r.size)} else emptyArray()
        fun add(i:Int,j:Int,value:Double){if(sparse!=null)sparse[i][j]=(sparse[i][j]?:0.0)+value else H[i][j]+=value}
        val tensions=DoubleArray(elements.size);val strains=DoubleArray(elements.size)
        var elastic=0.0;var webbingGravity=0.0;var personGravity=0.0
        for((i,e) in elements.withIndex()) {
            val dx=r[2*e.b]-r[2*e.a];val dy=r[2*e.b+1]-r[2*e.a+1]
            val length=hypot(dx,dy);val ux=dx/max(length,1e-14);val uy=dy/max(length,1e-14)
            val strain=length/e.rest-1;strains[i]=strain
            if(!trial && e.material.breakingStrain?.let{strain>=it}==true) throw LocatedMaterialLimit(e,MaterialLimit(e.material.name,strain,true))
            if(!strain.isFinite())throw org.linesolver.base.solver.SolverWarning("Non-finite element strain",org.linesolver.base.solver.FailureCategory.NON_FINITE)
            val clipped=if(trial) min(strain,e.material.maxStrain) else strain
            val t:Double;val k:Double;val w:Double
            try { val delta=strain-clipped;k=e.material.tangent(clipped)
                t=e.material.tension(clipped)+k*delta
                w=e.material.energy(clipped)+e.material.tension(clipped)*delta+.5*k*delta*delta
            } catch(ex:MaterialLimit){throw LocatedMaterialLimit(e,ex)}
            catch(ex:Exception){throw MaterialEvaluationFailure("Material evaluation failed for ${e.material.name}; ${e.cable}, section ${e.section+1}",ex)}
            tensions[i]=t;elastic+=e.rest*w;webbingGravity+=gravity*e.mass*(r[2*e.a+1]+r[2*e.b+1])/2
            grad[2*e.a]-=t*ux;grad[2*e.a+1]-=t*uy;grad[2*e.b]+=t*ux;grad[2*e.b+1]+=t*uy
            grad[2*e.a+1]+=gravity*e.mass/2;grad[2*e.b+1]+=gravity*e.mass/2
            if(hessian) {
                val u=doubleArrayOf(ux,uy)
                for(p in 0..1) for(q in 0..1) {
                    val block=k/e.rest*u[p]*u[q]+t/max(length,1e-14)*((if(p==q)1.0 else 0.0)-u[p]*u[q])
                    add(2*e.a+p,2*e.a+q,block);add(2*e.b+p,2*e.b+q,block)
                    add(2*e.a+p,2*e.b+q,-block);add(2*e.b+p,2*e.a+q,-block)
                }
            }
        }
        walker?.let {grad[2*it+1]+=gravity*walkerMass;personGravity=gravity*walkerMass*(r[2*it+1]+personOffset)}
        var connectorGravity=0.0
        for(item in connectorMasses) { require(item.massKg.isFinite() && item.massKg>=0 && item.node in 0 until r.size/2)
            grad[2*item.node+1]+=gravity*item.massKg;connectorGravity+=gravity*item.massKg*r[2*item.node+1] }
        return Evaluation(elastic,webbingGravity+personGravity+connectorGravity,grad,H,tensions,strains,webbingGravity,personGravity,sparse,connectorGravity)
    }
}
class LocatedMaterialLimit(val element:Element,val limit:MaterialLimit):RuntimeException("${limit.message}; ${element.cable}, section ${element.section+1}, material interval ${element.from}–${element.to}")
data class Evaluation(val elastic:Double,val gravitational:Double,val gradient:DoubleArray,val hessian:Array<DoubleArray>,val tensions:DoubleArray,val strains:DoubleArray,
    val webbingGravitational:Double,val walkerGravitational:Double,val sparseHessian:Array<MutableMap<Int,Double>>?=null,val connectorGravitational:Double=0.0) {val total get()=elastic+gravitational}

/** Physical-point-first contact-v1 allocation, meshing.material_grid. */
fun materialGrid(count:Int,mandatory:List<Double> = emptyList()):List<Double> {
    require(count>=1 && mandatory.all{it.isFinite() && it in 0.0..1.0})
    val points=(listOf(0.0,1.0)+mandatory).distinct().sorted();val n=max(count,points.size-1)
    val quota=DoubleArray(points.size-1){n*(points[it+1]-points[it])};val allocation=IntArray(quota.size){max(1,floor(quota[it]).toInt())}
    while(allocation.sum()<n) {val i=quota.indices.maxBy{quota[it]-allocation[it]};allocation[i]++}
    while(allocation.sum()>n) {val i=quota.indices.filter{allocation[it]>1}.maxBy{allocation[it]-quota[it]};allocation[i]--}
    val grid=mutableListOf<Double>()
    for(i in allocation.indices) for(j in 0 until allocation[i]) grid+=points[i]+j*(points[i+1]-points[i])/allocation[i]
    grid+=1.0;require(grid.zipWithNext().all{(a,b)->b>a}){"Unrepresentable contact mesh interval"};return grid
}

/** Complete main/backup model, free internal physical splits, fixed anchors. */
fun buildNetwork(setup:LineSetup,materials:MaterialRepository,active:List<Double>,loaded:Boolean):Network {
    if(!setup.bungees?.banks.isNullOrEmpty()) return buildBungeeNetwork(setup,materials,active,loaded)
    val total=active.sum();val r=mutableListOf<Double>();var s=0.0
    for(i in 0..active.size) {
        val f=s/total;r+=setup.geometry.spanM*f;r+=setup.geometry.leftHeightM*(1-f)+setup.geometry.rightHeightM*f
        if(i<active.size) s+=active[i]
    }
    if(loaded) require(setup.walker.referencePositionM.isFinite() && setup.walker.referencePositionM>0 && setup.walker.referencePositionM<total){"Walker must be inside the installed main material"}
    var section=0;var q=setup.walker.referencePositionM
    while(section<active.lastIndex && q>=active[section]){q-=active[section];section++}
    val fraction=q/active[section];var walker:Int?=null
    val elements=mutableListOf<Element>()
    for(i in active.indices) for(kind in Cable.entries) {
        val grid=materialGrid(setup.elements,if(loaded && i==section) listOf(fraction) else emptyList())
        val material=materials.get(if(kind==Cable.MAIN) setup.sections[i].mainId else setup.sections[i].backupId)
        val rest=active[i]*(if(kind==Cable.MAIN) 1.0 else 1+setup.sections[i].extraRatio)
        val ids=grid.map{f ->
            val node=when {
                f==0.0->i;f==1.0->i+1
                loaded && i==section && f==fraction && walker!=null->walker!!
                else->{
                    val node=r.size/2;val dx=r[2*(i+1)]-r[2*i];val dy=r[2*(i+1)+1]-r[2*i+1];val chord=hypot(dx,dy)
                    val sag=if(kind==Cable.MAIN) chord*.015 else max(chord*.015,sqrt(max(rest*rest-chord*chord,0.0))*.45)
                    r+=r[2*i]*(1-f)+r[2*(i+1)]*f;r+=r[2*i+1]*(1-f)+r[2*(i+1)+1]*f-4*sag*f*(1-f);node
                }
            }
            if(loaded && i==section && f==fraction && kind==Cable.MAIN) walker=node
            node
        }
        for(j in 0 until grid.lastIndex) {
            val l=rest*(grid[j+1]-grid[j]);elements+=Element(ids[j],ids[j+1],l,l*requireNotNull(material.density),material,i,kind,grid[j],grid[j+1])
        }
    }
    // Duplicate only an interior computational contact DOF. The old shared ID
    // enforced a mechanical tie even though it was absent from `splits`.
    val attachment=walker?.let { mainNode ->
        val backupNode=if(mainNode in 0..active.size)mainNode else r.size/2
        if(backupNode!=mainNode) {
            r+=r[2*mainNode];r+=r[2*mainNode+1]
            for(j in elements.indices) {
                val e=elements[j]
                if(e.cable==Cable.BACKUP)elements[j]=e.copy(a=if(e.a==mainNode)backupNode else e.a,b=if(e.b==mainNode)backupNode else e.b)
            }
        }
        LeashAttachment(mainNode,backupNode,ContactState(LineContact(section,q),LineContact(section,q*(1+setup.sections[section].extraRatio))))
    }
    return Network(r.toDoubleArray(),elements,(0..active.size).toList(),attachment,if(loaded) setup.walker.massKg else 0.0,setup.gravity,setup.geometry.spanM,
        walkerGeometry=if(loaded)setup.cut?.walkerGeometry else null).withStructuralJoints(setup)
}

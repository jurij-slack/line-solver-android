package org.linesolver.base.solver

import org.linesolver.base.energy.*
import org.linesolver.base.model.WalkerElevations
import kotlin.math.*

data class StaticState(val network:Network,val r:DoubleArray,val value:Evaluation,val residualN:Double,val iterations:Int,
    val limiterReactionsN:Map<String,Double?> = emptyMap(),val constraintViolationM:Double=0.0,
    val limiterStationarityMultipliersN:Map<String,Double> = emptyMap()) {
    fun totalGradient():DoubleArray {
        val g=value.gradient.copyOf()
        for(p in network.bungeePaths)limiterStationarityMultipliersN[p.instance.id]?.let{R->
            val c=p.constraint(network,r,hessian=false);for(i in g.indices)g[i]+=R*c.gradient[i]
        }
        return g
    }
    val attachmentPosition get()=network.attachment?.node?.let{Point(r[2*it],r[2*it+1])}
    val walker get()=attachmentPosition // legacy name; never a COM coordinate
    val walkerElevations:WalkerElevations? get()=attachmentPosition?.let{network.walkerGeometry?.atAttachment(it.y,network.posture)}
}

/** rig_solver.solve_network: same gradient, Hessian, regularization, Armijo rule. Dense mobile implementation. */
class Equilibrium(private val checkCancelled:()->Unit={},private val trace:SolverTrace?=null) {

    fun solve(n:Network,initial:DoubleArray=n.coordinates,fixedExtra:Map<Int,Double> = emptyMap(),tolerance:Double=2e-6,maxIterations:Int=800):StaticState {

        if(n.bungeePaths.any{it.limitM!=null}) return ConstrainedEquilibrium(checkCancelled,trace).solve(n,initial,fixedExtra,tolerance,maxIterations)
        require(initial.size==n.coordinates.size && initial.all{it.isFinite()}){"Initial network geometry must be finite and dimensionally valid"}
        require(tolerance.isFinite() && tolerance>0){"Force tolerance must be finite and positive"}
        require(maxIterations>0){"Iteration budget must be positive"}
        require(fixedExtra.all{(i,v)->i in initial.indices && v.isFinite()}){"Invalid prescribed coordinate"}

        if(initial.size>14000) throw SolverWarning("Static network exceeds the 14,000-coordinate resource bound",FailureCategory.UNSUPPORTED_MODEL)

        val alias=IntArray(initial.size){it}

        n.ringContactPair?.let{(main,backup)->for(k in 0..1)alias[2*main+k]=2*backup+k}

        fun project(r:DoubleArray){for(i in r.indices)r[i]=r[alias[i]]}

        var r=initial.copyOf();val fixed=mutableMapOf<Int,Double>()

        for(a in listOf(n.splits.first(),n.splits.last())) for(k in 0..1) fixed[2*a+k]=n.coordinates[2*a+k]
        fixed.putAll(fixedExtra)

        val fixedReduced=fixed.mapKeys{alias[it.key]};fixedReduced.forEach{(i,v)->r[i]=v};project(r)
        val free=r.indices.filter{alias[it]==it && it !in fixedReduced}
        val indices=IntArray(r.size){-1};free.forEachIndexed{i,j->indices[j]=i}

        for(i in indices.indices)indices[i]=indices[alias[i]]

        for(iteration in 0 until maxIterations) {
            checkCancelled();val v=n.evaluate(r,trial=true,hessian=true);val g=DoubleArray(free.size);for(i in r.indices)if(indices[i]>=0)g[indices[i]]+=v.gradient[i]
            val residual=g.maxOfOrNull{abs(it)}?:0.0
            trace?.trial(n,r,v,iteration,residual)
            if(!v.total.isFinite() || !residual.isFinite() || g.any{!it.isFinite()})throw SolverWarning("Non-finite equilibrium energy or gradient",FailureCategory.NON_FINITE)
            if(g.maxOfOrNull{abs(it)}?:0.0 < tolerance) {
                val strict=n.evaluate(r);val state=StaticState(n,r,strict,residual,iteration)
                return trace?.accepted(state)?:state
            }
            var reg=1e-9;var accepted:DoubleArray?=null

            for(attempt in 0 until 18) {
                val rhs=DoubleArray(g.size){-g[it]}
                val step=if(v.sparseHessian!=null) {
                    val rows=Array(free.size){mutableMapOf<Int,Double>()}
                    for(i in r.indices)if(indices[i]>=0)for((j,value) in v.sparseHessian[i])if(indices[j]>=0) {
                        val row=rows[indices[i]];row[indices[j]]=(row[indices[j]]?:0.0)+value
                    }
                    sparseSolve(rows,rhs,reg,checkCancelled)
                } else {
                    val A=Array(free.size){DoubleArray(free.size)}
                    for(i in r.indices)if(indices[i]>=0)for(j in r.indices)if(indices[j]>=0)A[indices[i]][indices[j]]+=v.hessian[i][j]
                    for(i in A.indices)A[i][i]+=reg
                    linearSolve(A,rhs)
                }
                if(step!=null && step.indices.sumOf{g[it]*step[it]}<0) {
                    val limit=max(n.span*.1,1.0);val largest=step.maxOf{abs(it)}
                    if(largest>limit) for(i in step.indices) step[i]*=limit/largest
                    val slope=g.indices.sumOf{g[it]*step[it]};var alpha=1.0
                    for(search in 0 until 35) {
                        val trial=r.copyOf();for(i in free.indices) trial[free[i]]+=alpha*step[i]
                        project(trial)
                        if(n.evaluate(trial,trial=true).total<=v.total+1e-4*alpha*slope+1e-10){accepted=trial;break}
                        alpha*=.5
                    }
                }
                if(accepted!=null) break
                reg=max(reg*10,1e-6)
            }
            r=accepted?:throw SolverWarning("Network Newton step failed; no physical failure established")
        }
        throw SolverWarning("Network equilibrium did not converge within $maxIterations iterations (Python default: 800)")
    }
}
internal fun linearSolve(a:Array<DoubleArray>,rhs:DoubleArray):DoubleArray? {
    val n=rhs.size;val b=rhs.copyOf()
    for(i in 0 until n) {
        var p=i;for(j in i+1 until n) if(abs(a[j][i])>abs(a[p][i]))p=j
        if(!a[p][i].isFinite() || abs(a[p][i])<1e-30)return null
        val tmp=a[p];a[p]=a[i];a[i]=tmp;val t=b[p];b[p]=b[i];b[i]=t
        for(j in i+1 until n) {
            val f=a[j][i]/a[i][i];if(f==0.0)continue
            a[j][i]=0.0;for(k in i+1 until n)a[j][k]-=f*a[i][k];b[j]-=f*b[i]
        }
    }
    val x=DoubleArray(n)
    for(i in n-1 downTo 0){var v=b[i];for(j in i+1 until n)v-=a[i][j]*x[j];x[i]=v/a[i][i]}
    return x.takeIf{it.all{v->v.isFinite()}}
}

/** Reverse Cuthill–McKee ordering keeps the cable graph band narrow; exact banded
 * Cholesky solves the same regularized Newton system without allocating a dense matrix.
 */
internal fun sparseSolve(rows:Array<MutableMap<Int,Double>>,b:DoubleArray,reg:Double,check:()->Unit):DoubleArray? {
    val n=b.size
    val seen=BooleanArray(n);val order=mutableListOf<Int>();val queue=ArrayDeque<Int>()
    for(seed in rows.indices.sortedBy{rows[it].size})if(!seen[seed]) {
        seen[seed]=true;queue.add(seed)
        while(queue.isNotEmpty()) {
            val i=queue.removeFirst();order.add(i)
            rows[i].keys.filter{!seen[it]}.sortedBy{rows[it].size}.forEach{j->if(!seen[j]){seen[j]=true;queue.add(j)}}
        }
    }
    order.reverse();val index=IntArray(n);order.forEachIndexed{i,j->index[j]=i}
    var width=0
    for(i in rows.indices)for(j in rows[i].keys)width=max(width,abs(index[i]-index[j]))
    val l=Array(n){DoubleArray(width+1)}
    for(i in 0 until n) {
        if(i%32==0)check()
        for(j in max(0,i-width)..i) {
            var v=(rows[order[i]][order[j]]?:0.0)+(if(i==j)reg else 0.0)
            for(k in max(0,i-width) until j)v-=l[i][i-k]*l[j][j-k]
            if(i==j){if(v<=0 || !v.isFinite())return null;l[i][0]=sqrt(v)}
            else l[i][i-j]=v/l[j][0]
        }
    }
    val y=DoubleArray(n)
    for(i in 0 until n){var v=b[order[i]];for(j in max(0,i-width) until i)v-=l[i][i-j]*y[j];y[i]=v/l[i][0]}
    val x=DoubleArray(n)
    for(i in n-1 downTo 0){var v=y[i];for(j in i+1..min(n-1,i+width))v-=l[j][j-i]*x[j];x[i]=v/l[i][0]}
    return DoubleArray(n){x[index[it]]}.takeIf{it.all{v->v.isFinite()}}
}

/** Cable resultant at the fixed ring label, including that label's lumped webbing weight.
 * Positive vertical reaction supports the person. A threaded slack cable can return zero.
 */
fun StaticState.ringReaction(cable:Cable):Point? {
    val a=network.attachment?:return null
    val engaged=if(network.posture==org.linesolver.base.model.WalkerPosture.STANDING)cable==Cable.MAIN
        else if(cable==Cable.MAIN)a.contacts.main.threaded else a.contacts.backup.threaded
    if(!engaged)return null
    val node=if(cable==Cable.MAIN)a.mainNode else a.backupNode
    var x=0.0;var y=0.0
    network.elements.forEachIndexed { i,e -> if(e.cable==cable && (e.a==node || e.b==node)) {
        val other=if(e.a==node)e.b else e.a
        val dx=r[2*other]-r[2*node];val dy=r[2*other+1]-r[2*node+1];val length=hypot(dx,dy)
        if(length>0){x+=value.tensions[i]*dx/length;y+=value.tensions[i]*dy/length}
        y-=e.mass*network.gravity/2
    } }
    return Point(x,y)
}

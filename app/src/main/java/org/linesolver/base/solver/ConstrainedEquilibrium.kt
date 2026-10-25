package org.linesolver.base.solver

import org.linesolver.base.energy.*
import kotlin.math.*

/** Active-set SQP for c=Lpath-Llimit<=0. Multipliers are tensile reactions (N).
 * Merit globalization restores infeasible initial guesses; final acceptance uses
 * strict material evaluation, primal/dual residuals and complementarity, never clipping.
 */
internal class ConstrainedEquilibrium(private val check:()->Unit,private val trace:SolverTrace?) {
    fun solve(n:Network,initial:DoubleArray,fixedExtra:Map<Int,Double>,tolerance:Double,maxIterations:Int):StaticState {
        require(initial.size==n.coordinates.size && initial.all{it.isFinite()})
        require(tolerance.isFinite() && tolerance>0 && maxIterations>0)
        require(n.ringContactPair==null) { "Constrained bungee catch/contact equilibrium is unsupported" }
        if(initial.size>512) throw SolverWarning("Ideal bungee limiter solver supports at most 512 coordinates; reduce mesh/sections",FailureCategory.UNSUPPORTED_MODEL)
        val fixed=mutableMapOf<Int,Double>()
        for(a in listOf(n.splits.first(),n.splits.last())) for(k in 0..1)fixed[2*a+k]=n.coordinates[2*a+k]
        require(fixedExtra.all{(i,v)->i in initial.indices && v.isFinite()})
        fixed.putAll(fixedExtra)
        val free=initial.indices.filter{it !in fixed};var r=initial.copyOf();fixed.forEach{(i,v)->r[i]=v}
        val paths=n.bungeePaths.filter{it.limitM!=null};var lambda=DoubleArray(paths.size)
        val primalTol=1e-9;val dualTol=max(tolerance,2e-6);val active=mutableSetOf<Int>()
        val redundant=mutableSetOf<Int>()
        val released=mutableSetOf<Int>()
        for(iter in 0 until maxIterations) {
            check()
            val v=n.evaluate(r,trial=true,hessian=true)
            val constraints=paths.map{it.constraint(n,r,hessian=false)}
            val g=DoubleArray(free.size){v.gradient[free[it]]}
            val rows=constraints.map{c->DoubleArray(free.size){c.gradient[free[it]]}}
            for(i in paths.indices) {
                if(rows[i].sumOf{it*it}<1e-24) {
                    if(constraints[i].excessM>primalTol) throw SolverWarning("Prescribed geometry exceeds ideal limiter ${paths[i].instance.id}",FailureCategory.INVALID_CONFIGURATION)
                    active.remove(i);lambda[i]=0.0
                } else if(constraints[i].excessM>primalTol || (i !in released && constraints[i].excessM>=-1e-8) || lambda[i]>dualTol) active+=i
            }
            // Drop dependent rows; retain their constraints in the primal acceptance check.
            // A redundant taut limiter has indeterminate individual reaction, reported as null.
            redundant.clear();val basis=mutableListOf<DoubleArray>()
            val selected=mutableListOf<Int>()
            for(i in active.sortedByDescending{constraints[it].excessM}) {
                val row=rows[i].copyOf()
                for(b in basis) { val d=row.indices.sumOf{row[it]*b[it]};for(j in row.indices)row[j]-=d*b[j] }
                val norm=sqrt(row.sumOf{it*it})
                if(norm<1e-10) { redundant+=i;lambda[i]=0.0 } else { for(j in row.indices)row[j]/=norm;basis+=row;selected+=i }
            }
            val stationarity=DoubleArray(g.size){j->g[j]+paths.indices.sumOf{i->lambda[i]*rows[i][j]}}
            val residual=stationarity.maxOfOrNull{abs(it)}?:0.0
            val violation=constraints.maxOfOrNull{max(0.0,it.excessM)}?:0.0
            val complement=paths.indices.maxOfOrNull{abs(lambda[it]*constraints[it].excessM)}?:0.0
            trace?.trial(n,r,v,iter,residual)
            if(!v.total.isFinite() || !residual.isFinite())throw SolverWarning("Nonfinite constrained equilibrium",FailureCategory.NON_FINITE)
            if(residual<=dualTol && violation<=primalTol && complement<=max(1e-7,dualTol*1e-3) && lambda.all{it>=-dualTol}) {
                val undetermined=redundant.toMutableSet()
                if(redundant.isNotEmpty())undetermined.addAll(selected) // conservative: never assign a fictitious individual load share
                val reactions=paths.indices.associate { i -> paths[i].instance.id to
                    if(i in undetermined || (rows[i].sumOf{it*it}<1e-24 && abs(constraints[i].excessM)<=primalTol)) null else max(0.0,lambda[i]) }
                val state=StaticState(n,r,n.evaluate(r),residual,iter,reactions,violation,paths.indices.associate{paths[it].instance.id to max(0.0,lambda[it])})
                return trace?.accepted(state)?:state
            }
            val size=free.size+selected.size
            if(size==0) throw SolverWarning("Unresolved limiter equilibrium",FailureCategory.INVALID_CONFIGURATION)
            var accepted:DoubleArray?=null;var nextLambda=lambda.copyOf();var reg=1e-8
            var changedActiveSet=false
            val lagrangianH=Array(free.size){i->DoubleArray(free.size){j->v.hessian[free[i]][free[j]]}}
            for(k in paths.indices)if(lambda[k]>0) {
                val h=paths[k].constraint(n,r).hessian
                for(i in free.indices)for(j in free.indices)lagrangianH[i][j]+=lambda[k]*h[free[i]][free[j]]
            }
            for(attempt in 0 until 16) {
                val A=Array(size){DoubleArray(size)};val rhs=DoubleArray(size)
                for(i in free.indices) { rhs[i]=-g[i]
                    for(j in free.indices) A[i][j]=lagrangianH[i][j]
                    A[i][i]+=reg
                }
                for((k,i) in selected.withIndex()) {
                    rhs[free.size+k]=-constraints[i].excessM
                    for(j in free.indices) { A[j][free.size+k]=rows[i][j];A[free.size+k][j]=rows[i][j] }
                }
                val step=linearSolve(A,rhs)
                if(step!=null) {
                    val candidates=DoubleArray(paths.size);for((k,i) in selected.withIndex()) candidates[i]=step[free.size+k]
                    val release=selected.filter{candidates[it]<-dualTol && constraints[it].excessM<=primalTol}
                    if(release.isNotEmpty()) { active.removeAll(release.toSet());released.addAll(release);for(i in release)lambda[i]=0.0;changedActiveSet=true;break }
                    val penalty=max(1000.0,2*(candidates.maxOfOrNull{abs(it)}?:0.0)+1)
                    val largest=free.indices.maxOfOrNull{abs(step[it])}?:0.0
                    val scale=if(largest>max(n.span*.1,1.0))max(n.span*.1,1.0)/largest else 1.0
                    for(j in free.indices)step[j]*=scale
                    val merit=v.total+penalty*constraints.sumOf{max(0.0,it.excessM)}
                    val predicted=g.indices.sumOf{g[it]*step[it]}-penalty*scale*constraints.sumOf{max(0.0,it.excessM)}
                    var alpha=1.0
                    for(search in 0 until 40) {
                        val trial=r.copyOf();for(j in free.indices) trial[free[j]]+=alpha*step[j]
                        val tv=n.evaluate(trial,trial=true)
                        val excess=paths.sumOf{max(0.0,it.length(n,trial)-requireNotNull(it.limitM))}
                        val multipliers=DoubleArray(paths.size){lambda[it]+alpha*scale*(candidates[it]-lambda[it])}
                        // Close to feasibility, energy subtraction can lose the final Newton
                        // decrease. Check the KKT equations directly without weakening final gates.
                        var localNewton=false
                        if(violation<1e-7 && excess<=max(primalTol,violation*1.2)) {
                            val tc=paths.map{it.constraint(n,trial,hessian=false)}
                            val tr=free.maxOfOrNull{j->abs(tv.gradient[j]+paths.indices.sumOf{k->multipliers[k]*tc[k].gradient[j]})}?:0.0
                            localNewton=tr<residual*(1-1e-4*alpha)
                        }
                        if(tv.total.isFinite() && (localNewton || tv.total+penalty*excess<=merit+1e-4*alpha*min(predicted,-1e-12)+1e-10)) {
                            accepted=trial;nextLambda=multipliers;break
                        }
                        alpha*=.5
                    }
                }
                if(accepted!=null)break
                reg=max(1e-6,reg*10)
            }
            if(accepted!=null) { r=accepted;lambda=nextLambda;released.clear() }
            else if(!changedActiveSet) throw SolverWarning("Ideal limiter active set failed at iteration $iter (stationarity $residual N; violation $violation m; complementarity $complement J); no admissible result",FailureCategory.ROOT_FINDING)
        }
        throw SolverWarning("Ideal limiter equilibrium exceeded $maxIterations iterations",FailureCategory.ROOT_FINDING)
    }
}

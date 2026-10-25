package org.linesolver.base.solver

import kotlinx.serialization.Serializable
import org.linesolver.base.energy.*

@Serializable data class SolverSettings(val forceToleranceN:Double=2e-6,val maxIterations:Int=800,
    val catchRootToleranceJ:Double=1e-6,val energyAuditToleranceJ:Double=1e-5,
    val catchStepM:Double=.5,val maxCatchSteps:Int=1000,val maxRootIterations:Int=60) {
    fun validate() {
        require(listOf(forceToleranceN,catchRootToleranceJ,energyAuditToleranceJ,catchStepM).all{it.isFinite() && it>0}){"Invalid numerical tolerances"}
        require(maxIterations>0 && maxCatchSteps>0 && maxRootIterations>0 && catchRootToleranceJ<=energyAuditToleranceJ){"Invalid solver iteration/root settings"}
    }
}
enum class FailureCategory { INVALID_CONFIGURATION, NON_CONVERGENCE, ROOT_FINDING, NON_FINITE, MATERIAL_EVALUATION, MATERIAL_LIMIT, ENERGY_CONSISTENCY, UNSUPPORTED_MODEL, UNEXPECTED_EXCEPTION }
open class SolverWarning(message:String,val category:FailureCategory=FailureCategory.NON_CONVERGENCE):RuntimeException(message)
data class IterationRecord(val stage:String,val iteration:Int,val residualN:Double,val energyJ:Double)
/** Observations only: never changes step acceptance, physics or tolerances. */
class SolverTrace {
    var stage="validation"
    var lastValid:StaticState?=null
    var lastTrial:StaticState?=null
    val states=linkedMapOf<String,StaticState>()
    val history=ArrayDeque<IterationRecord>()
    var bracket:Pair<Double,Double>?=null
    var installedLengths:List<Double>?=null
    var achievedPretensionN:Double?=null
    var evaluations=0
    fun trial(n:Network,r:DoubleArray,v:Evaluation,iteration:Int,residual:Double) {
        evaluations++
        lastTrial=StaticState(n,r.copyOf(),v.copy(hessian=emptyArray(),sparseHessian=null),residual,iteration)
        history.addLast(IterationRecord(stage,iteration,residual,v.total))
        if(history.size>64)history.removeFirst()
    }
    fun accepted(s:StaticState):StaticState {lastValid=s;return s}
}

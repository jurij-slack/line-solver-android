package org.linesolver.base.energy

/** Potentials at actual configurations, in J; y is upwards in the same datum.
 * No independent mg*(height+leash) or sag correction may be added to this ledger.
 */
data class MechanicalEnergyState(val mainElasticJ:Double,val backupElasticJ:Double,
    val webbingGravityJ:Double,val walkerGravityJ:Double,val walkerComYM:Double,val connectorGravityJ:Double=0.0) {
    val elasticJ get()=mainElasticJ+backupElasticJ
    val totalJ get()=elasticJ+webbingGravityJ+walkerGravityJ+connectorGravityJ
}
data class FallEnergyLedger(val initial:MechanicalEnergyState,val turning:MechanicalEnergyState) {
    val walkerGravityReleasedJ get()=initial.walkerGravityJ-turning.walkerGravityJ
    val webbingGravityReleasedJ get()=initial.webbingGravityJ-turning.webbingGravityJ
    val connectorGravityReleasedJ get()=initial.connectorGravityJ-turning.connectorGravityJ
    val elasticIncreaseJ get()=turning.elasticJ-initial.elasticJ
    val residualJ get()=turning.totalJ-initial.totalJ
    // Fixed material labels and ideal inextensible leash: no sliding/engagement loss.
    val dissipatedJ get()=0.0
}

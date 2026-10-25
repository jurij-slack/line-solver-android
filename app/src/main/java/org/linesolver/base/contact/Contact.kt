package org.linesolver.base.contact
import kotlinx.serialization.Serializable

/** Python highliner.RingFriction: effective N conjugate to reference travel, NOT capstan mu. */
object ContactDefaults { const val RESISTANCE_N=200.0 }
@Serializable enum class ConnectionBehavior { STOP, PASSABLE }
@Serializable data class ContactOptions(val resistanceN:Double=ContactDefaults.RESISTANCE_N,
    val connections:List<ConnectionBehavior> = emptyList())
data class LineContact(val section:Int,val materialCoordinateM:Double,val threaded:Boolean=true)
data class ContactState(val main:LineContact,val backup:LineContact)

/** Fixed material labels on independent cables. Only genuine splits share a node.
 * support names the mass coordinate. Engaged threaded contacts may constrain
 * positions through the ideal ring without merging material IDs or adding a split.
 */
data class LeashAttachment(val mainNode:Int,val backupNode:Int,val contacts:ContactState,
    val support:org.linesolver.base.energy.Cable=org.linesolver.base.energy.Cable.MAIN) {
    val node get()=if(support==org.linesolver.base.energy.Cable.MAIN)mainNode else backupNode
}

package org.linesolver.base.model

/** SI, y positive upwards. Legacy input is body height and physical ring-to-harness
 * leash length, NOT ring-to-COM length. Fractions match Python highliner.Highliner.
 * The Simple reduction keeps the body upright and the engaged leash vertical.
 */
data class WalkerGeometry(val heightM:Double,val leashToHarnessM:Double) {
    val standingComHeightM get()=.55*heightM
    val harnessHeightM get()=.5*heightM
    val harnessToComM get()=standingComHeightM-harnessHeightM
    val initialSlackM get()=leashToHarnessM-harnessHeightM
    val engagementDropM get()=harnessHeightM+leashToHarnessM
    init {
        require(heightM.isFinite() && heightM>0){"Walker body height must be finite and positive"}
        require(leashToHarnessM.isFinite() && leashToHarnessM>=harnessHeightM){"Physical leash must reach the standing harness (at least half body height)"}
    }
    fun comOffset(posture:WalkerPosture):Double=when(posture) {
        WalkerPosture.STANDING -> standingComHeightM
        WalkerPosture.LEASH_SUPPORTED -> -leashToHarnessM+harnessToComM
    }
    fun atAttachment(y:Double,posture:WalkerPosture):WalkerElevations {
        require(y.isFinite())
        val feet=if(posture==WalkerPosture.STANDING)y else y-leashToHarnessM-harnessHeightM
        return WalkerElevations(y,feet,feet+harnessHeightM,feet+standingComHeightM,
            if(posture==WalkerPosture.STANDING)initialSlackM else 0.0)
    }
    /** Geometry check only, not a time trajectory: fall relative to a fixed attachment.
     * At engagement this joins atAttachment(LEASH_SUPPORTED) without an energy jump.
     * A taut leash has no stiffness, stored energy or arbitrary engagement loss here.
     */
    fun duringSlackFall(attachmentY:Double,dropM:Double):WalkerElevations {
        require(attachmentY.isFinite() && dropM.isFinite() && dropM in 0.0..engagementDropM)
        val feet=attachmentY-dropM;val harness=feet+harnessHeightM
        return WalkerElevations(attachmentY,feet,harness,feet+standingComHeightM,
            (leashToHarnessM-kotlin.math.abs(harnessHeightM-dropM)).coerceAtLeast(0.0))
    }
}
enum class WalkerPosture { STANDING, LEASH_SUPPORTED }
data class WalkerElevations(val attachmentM:Double,val feetM:Double,val harnessM:Double,val comM:Double,val slackM:Double)

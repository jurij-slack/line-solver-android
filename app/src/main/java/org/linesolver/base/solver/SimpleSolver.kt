package org.linesolver.base.solver

import org.linesolver.base.energy.*
import org.linesolver.base.model.*
import org.linesolver.base.material.*
import kotlin.math.*

data class StaticResult(val installedLengths:List<Double>,val achievedPretensionN:Double,val standing:StaticState,
    val catch:StaticState?=null,val rest:StaticState?=null,val initialEnergyJ:Double?=null,val catchResidualJ:Double?=null,
    val relaxationLossJ:Double?=null,val warnings:List<String> = emptyList(),val fallEnergy:FallEnergyLedger?=null)
interface BaseSolver { fun solve(setup:LineSetup):StaticResult }
class AdvancedSolver:BaseSolver {
    override fun solve(setup:LineSetup):StaticResult=throw SolverWarning("Advanced sliding is not yet ported. The rig is retained; use Simple for fixed material coordinates.")
}

/** Python rig_solver + failure energy sequence, with contact material labels explicitly fixed. */
class SimpleSolver internal constructor(private val materials:MaterialRepository,private val checkCancelled:()->Unit={},
    // Test seam retains historical Python shared-ring oracles without exposing a legacy UI mode.
    private val networkBuilder:(LineSetup,MaterialRepository,List<Double>,Boolean)->Network=::buildNetwork,
    private val settings:SolverSettings=SolverSettings(),private val trace:SolverTrace?=null):BaseSolver {
    private val equilibrium=Equilibrium(checkCancelled,trace)
    private fun at(n:Network,r:DoubleArray=n.coordinates,fixed:Map<Int,Double> = emptyMap(),tolerance:Double=settings.forceToleranceN)=
        equilibrium.solve(n,r,fixed,tolerance,settings.maxIterations)
    fun calibrate(setup:LineSetup):Pair<List<Double>,Double> {
        val prepared=setup.sections.map{it.preparedLengthM};val total=prepared.sum();val terminal=prepared.last();val full=total-terminal
        val chord=hypot(setup.geometry.spanM,setup.geometry.rightHeightM-setup.geometry.leftHeightM)
        val extension=setup.sections.mapIndexed{i,s->val m=materials.get(s.mainId);m.strainAt(if(i==prepared.lastIndex) setup.pretensionN else min(setup.pretensionN,m.tension(m.maxStrain)))}
        val stretched=prepared.indices.sumOf{prepared[it]*(1+extension[it])}
        val shortest=full+max(1e-5,1e-5*terminal)
        val guess=(full+(chord-stretched+terminal*(1+extension.last()))/(1+extension.last())).coerceIn(shortest,total)
        fun evaluate(length:Double):Double {
            checkCancelled();val active=prepared.dropLast(1)+(length-full)
            val state=at(networkBuilder(setup,materials,active,false),tolerance=if(abs(length-shortest)<1e-10)1e-4 else settings.forceToleranceN)
            val i=state.network.elements.indexOfLast{it.cable==Cable.MAIN && it.material !is org.linesolver.base.material.catalogue.FoldedBungeeMaterial};return state.value.tensions[i]
        }
        var previous=guess;var residual=evaluate(guess)-setup.pretensionN
        if(abs(residual)<1e-7)return (prepared.dropLast(1)+(guess-full)) to (residual+setup.pretensionN)
        val direction=if(residual>0)1 else -1;val boundary=if(direction>0)total else shortest
        var step=max(.001*setup.geometry.spanM,.02*terminal)
        repeat(60) {
            val candidate=if(direction>0)min(previous+step,boundary) else max(previous-step,boundary)
            val value=evaluate(candidate)-setup.pretensionN
            if(value*residual<=0) {
                var a=min(previous,candidate);var b=max(previous,candidate);var fa=evaluate(a)-setup.pretensionN;var root=(a+b)/2;var achieved=0.0
                repeat(60) {
                    root=(a+b)/2;achieved=evaluate(root);val f=achieved-setup.pretensionN
                    if(abs(f)<1e-7 || b-a<1e-10)return (prepared.dropLast(1)+(root-full)) to achieved
                    if(f*fa<=0)b=root else {a=root;fa=f}
                }
                throw SolverWarning("Installation calibration did not close",FailureCategory.ROOT_FINDING)
            }
            if(candidate==boundary)throw SolverWarning(if(direction<0) "Pretension would pull the terminal section through its previous split; change prepared lengths" else "More prepared main material is required for this pretension",FailureCategory.INVALID_CONFIGURATION)
            previous=candidate;residual=value;step*=1.4
        }
        throw SolverWarning("Could not bracket installed weblock coordinate",FailureCategory.ROOT_FINDING)
    }
    override fun solve(setup:LineSetup):StaticResult = solveFrom(setup,null)
    fun solveFrom(setup:LineSetup,previous:StaticResult?):StaticResult {
        if(!setup.bungees?.banks.isNullOrEmpty() && setup.cut!=null) throw SolverWarning("Catch/failure is unavailable for bungee rigs: transient limiter engagement is not modeled",FailureCategory.UNSUPPORTED_MODEL)
        settings.validate()
        require(setup.geometry.spanM.isFinite() && setup.geometry.spanM>0 &&
            setup.geometry.leftHeightM.isFinite() && setup.geometry.rightHeightM.isFinite()){"Invalid anchor geometry"}
        require(listOf(setup.walker.massKg,setup.gravity,setup.pretensionN).all{it.isFinite() && it>0}){"Mass, gravity and main pretension must be finite and positive"}
        require(setup.sections.isNotEmpty() && setup.sections.all{it.preparedLengthM.isFinite() && it.preparedLengthM>0 && it.extraRatio.isFinite() && it.extraRatio>=0}){"Invalid prepared main/backup lengths"}
        setup.cut?.walkerGeometry // validate physical leash/standing geometry before calibration
        trace?.stage="installation"
        val prior=previous?.takeIf{setup.bungees?.banks.isNullOrEmpty() && it.standing.network.bungeePaths.isEmpty()}
        val (lengths,tension)=prior?.let{it.installedLengths to it.achievedPretensionN}?:calibrate(setup)
        trace?.installedLengths=lengths;trace?.achievedPretensionN=tension
        trace?.stage="standing"
        val standing=prior?.standing?.let { old ->
            val network=old.network.copy(walkerGeometry=setup.cut?.walkerGeometry?:old.network.walkerGeometry)
            old.copy(network=network,value=network.evaluate(old.r))
        }?:at(networkBuilder(setup,materials,lengths,true))
        trace?.states?.set("standing",standing)
        val cut=setup.cut
        var result=StaticResult(lengths,tension,standing)
        if(cut!=null) {
            trace?.stage="cut"
            val n=breakMain(standing,lengths,cut)
            val attachmentY=2*requireNotNull(n.attachment).node+1
            // Standing state itself owns COM potential; never add that offset twice.
            val initialEnergy=standing.value.total
            val height=standing.walker!!.y;var low=0.0
            trace?.stage="catch_bracketing"
            var state=at(n,hangingGuess(n),mapOf(attachmentY to height))
            if(state.value.total>initialEnergy+1e-6)throw SolverWarning("Post-cut fixed contact requires more energy than the standing state provides",FailureCategory.ENERGY_CONSISTENCY)
            var upper:Double?=null
            for(i in 1..settings.maxCatchSteps) {
                checkCancelled();val drop=i*settings.catchStepM
                trace?.bracket=low to drop
                val next=at(n,state.r,mapOf(attachmentY to height-drop))
                if(next.value.total>=initialEnergy){upper=drop;break}
                low=drop;state=next
            }
            var hi=upper?:throw SolverWarning("No fixed-label turning state bracketed within ${settings.maxCatchSteps*settings.catchStepM} m search bound",FailureCategory.ROOT_FINDING)
            var caught=state
            trace?.stage="catch_root"
            for(i in 0 until settings.maxRootIterations) {
                trace?.bracket=low to hi
                val mid=(low+hi)/2
                caught=at(n,state.r,mapOf(attachmentY to height-mid))
                val residual=caught.value.total-initialEnergy
                if(abs(residual)<settings.catchRootToleranceJ)break
                if(residual>=0)hi=mid else low=mid
            }
            if(abs(caught.value.total-initialEnergy)>settings.energyAuditToleranceJ)throw SolverWarning("Catch energy root did not close",FailureCategory.ROOT_FINDING)
            trace?.states?.set("catch",caught)
            trace?.stage="rest"
            val rest=at(n,caught.r)
            trace?.states?.set("rest",rest)
            if(rest.value.total>caught.value.total+1e-7)throw SolverWarning("Resting continuation increased energy",FailureCategory.ENERGY_CONSISTENCY)
            val ledger=FallEnergyLedger(standing.energySnapshot(),caught.energySnapshot())
            if(abs(ledger.residualJ)>=settings.energyAuditToleranceJ)throw SolverWarning("Walker/line energy ledger does not close",FailureCategory.ENERGY_CONSISTENCY)
            result=StaticResult(lengths,tension,standing,caught,rest,initialEnergy,caught.value.total-initialEnergy,caught.value.total-rest.value.total,fallEnergy=ledger)
        }
        val extrapolated=listOfNotNull(result.standing,result.catch,result.rest).any{s->s.network.elements.indices.any{s.value.strains[it]>s.network.elements[it].material.measuredMax}}
        trace?.stage="complete"
        return result.copy(warnings=if(extrapolated) listOf("Unmeasured material continuation used within MBS; not an experimentally validated force prediction") else emptyList())
    }
}

/** failure.break_mainline: late-cut interpolation and duplicated free endpoint, no deleted mass. */
fun breakMain(pre:StaticState,lengths:List<Double>,cut:Cut):Network {
    require(cut.referencePositionM>0 && cut.referencePositionM<lengths.sum()){"Cut is outside installed material"}
    var section=0;var q=cut.referencePositionM
    while(section<lengths.lastIndex && q>=lengths[section]){q-=lengths[section];section++}
    val fraction=q/lengths[section];require(fraction>0 && fraction<1){"Cut exactly at a section connection is unsupported"}
    val elements=pre.network.elements.toMutableList();val r=pre.r.toMutableList();var node:Int?=null
    for((i,e) in elements.withIndex()) if(e.cable==Cable.MAIN && e.section==section) {
        if(e.from==fraction){node=e.a;break};if(e.to==fraction){node=e.b;break}
        if(e.from<fraction && fraction<e.to) {
            val a=(fraction-e.from)/(e.to-e.from);node=r.size/2
            r+=pre.r[2*e.a]*(1-a)+pre.r[2*e.b]*a;r+=pre.r[2*e.a+1]*(1-a)+pre.r[2*e.b+1]*a
            elements[i]=e.copy(b=node,rest=e.rest*a,mass=e.mass*a,to=fraction)
            elements.add(i+1,e.copy(a=node,rest=e.rest*(1-a),mass=e.mass*(1-a),from=fraction));break
        }
    }
    val left=requireNotNull(node);require(left!=pre.network.walker){"Cut exactly at ring is unsupported"}
    val right=r.size/2;r+=r[2*left];r+=r[2*left+1]
    val changed=elements.map{if(it.cable==Cable.MAIN && it.section==section && it.a==left && it.from>=fraction-1e-12)it.copy(a=right) else it}
    return pre.network.copy(coordinates=r.toDoubleArray(),elements=changed,broken=left to right,
        walkerGeometry=cut.walkerGeometry,posture=WalkerPosture.LEASH_SUPPORTED,
        attachment=pre.network.attachment?.let{a->a.copy(support=Cable.BACKUP,
            contacts=a.contacts.copy(main=a.contacts.main.copy(threaded=a.mainNode in pre.network.splits || a.contacts.main.section!=section)))})
}

private fun StaticState.energySnapshot():MechanicalEnergyState {
    fun elastic(cable:Cable)=network.elements.indices.filter{network.elements[it].cable==cable}.sumOf {
        network.elements[it].let{e->e.rest*e.material.energy(value.strains[it])}
    }
    return MechanicalEnergyState(elastic(Cable.MAIN),elastic(Cable.BACKUP),value.webbingGravitational,
        value.walkerGravitational,requireNotNull(walkerElevations).comM,value.connectorGravitational)
}
fun hangingGuess(n:Network):DoubleArray {
    val r=n.coordinates.copyOf();val (left,right)=n.broken?:return r
    val e=n.elements.first{it.cable==Cable.MAIN && it.b==left};val section=e.section;val cut=e.to
    for(isLeft in listOf(true,false)) {
        var es=n.elements.filter{it.cable==Cable.MAIN && it.section==section && if(isLeft)it.to<=cut+1e-12 else it.from>=cut-1e-12}
        if(!isLeft)es=es.reversed()
        val contact=es.indexOfFirst{(if(isLeft)it.a else it.b)==n.walker};if(contact>=0)es=es.drop(contact)
        var mass=es.sumOf{it.mass}
        for(tail in es) {
            val length=tail.rest*(1+tail.material.strainAt(n.gravity*(mass-tail.mass/2)))
            val a=if(isLeft)tail.a else tail.b;val b=if(isLeft)tail.b else tail.a
            r[2*b]=r[2*a];r[2*b+1]=r[2*a+1]-length;mass-=tail.mass
        }
    }
    return r
}

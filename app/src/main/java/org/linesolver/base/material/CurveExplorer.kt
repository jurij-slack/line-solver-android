package org.linesolver.base.material

import kotlinx.serialization.json.*
import kotlin.math.*

/** Display units only. Every force evaluation delegates to the solver's Webbing. */
data class CurvePoint(val percent:Double,val kn:Double)
enum class CurveRegion { SUPPLIED, EXTRAPOLATED, UNKNOWN }
data class CurveSegment(val region:CurveRegion,val points:List<CurvePoint>)
data class CurvePlot(val segments:List<CurveSegment>,val samples:List<CurvePoint>,val boundaries:List<CurvePoint>,val error:String?=null)
data class SuppliedDomain(val lower:Double,val upper:Double) {
    fun contains(strain:Double)=strain>=lower && strain<=upper
}
fun Webbing.suppliedDomain():SuppliedDomain? = runCatching {
    val range=metadata["valid_strain_range"]?.jsonArray?:return null
    require(range.size==2)
    var lo=range[0].jsonPrimitive.double;val hi=range[1].jsonPrimitive.double
    val xs=metadata.getValue("strain_fraction").jsonArray.map{it.jsonPrimitive.double}
    if(hasImposedOrigin)lo=max(lo,xs.first{it>0})
    require(lo.isFinite() && hi.isFinite() && lo>=0 && hi>=lo && lo>=xs.first() && hi<=xs.last())
    SuppliedDomain(lo,hi)
}.getOrNull()
fun Webbing.curveRegion(strain:Double):CurveRegion = suppliedDomain()?.let{
    if(it.contains(strain))CurveRegion.SUPPLIED else CurveRegion.EXTRAPOLATED
}?:CurveRegion.UNKNOWN
fun Webbing.plotForce(percent:Double):Double {
    require(percent.isFinite() && percent>=0)
    // The displayed endpoint can round one ULP upward on conversion back to a fraction.
    // Clamp only values at/below the actual displayed limit; never extend the model domain.
    val strain=if(percent<=maxStrain*100)min(percent/100,maxStrain) else percent/100
    return tension(strain)/1000
}
fun Webbing.curvePlot(minPercent:Double,maxPercent:Double):CurvePlot = try {
    require(minPercent.isFinite() && maxPercent.isFinite() && minPercent>=0 && maxPercent>minPercent)
    require(maxStrain.isFinite() && maxStrain>0)
    require(metadata["independent_variable"]?.jsonPrimitive?.contentOrNull=="strain_fraction" &&
        metadata["tension_unit"]?.jsonPrimitive?.contentOrNull=="N" &&
        metadata["interpolation"]?.jsonPrimitive?.contentOrNull in listOf("PCHIP","LINEAR")){"Unsupported curve units or model metadata"}
    val low=minPercent/100;val high=min(maxPercent/100,maxStrain)
    val domain=suppliedDomain()
    fun point(e:Double):CurvePoint {
        val f=tension(e)/1000
        require(f.isFinite() && f>=0){"Non-finite or negative force"}
        return CurvePoint(e*100,f)
    }
    val breaks=(listOf(low,high)+listOfNotNull(domain?.lower,domain?.upper,measuredMax).filter{it>low && it<high}).distinct().sorted()
    val segments=if(low>=high)emptyList() else breaks.zipWithNext().map{(a,b)->
        CurveSegment(curveRegion((a+b)/2), (0..100).map{i->point(if(i==100)b else a+(b-a)*i/100)})
    }
    val method=metadata["source_method"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val xs=metadata.getValue("strain_fraction").jsonArray;val ys=metadata.getValue("tension_N").jsonArray
    require(xs.size==ys.size)
    // Source knots, not resampled interpolant points. A supplied fit is not raw observations.
    val samples=if(domain==null || method.contains("fit",ignoreCase=true))emptyList() else xs.indices.mapNotNull{i->
        val e=xs[i].jsonPrimitive.double;val f=ys[i].jsonPrimitive.double
        if(e>=low && e<=high && domain.contains(e) && f.isFinite() && f>=0 &&
            !(i==0 && hasImposedOrigin))CurvePoint(e*100,f/1000) else null
    }
    CurvePlot(segments,samples,listOfNotNull(domain?.lower,domain?.upper).distinct().filter{it>=low && it<=high}.map(::point))
} catch(e:Exception) {CurvePlot(emptyList(),emptyList(),emptyList(),"Curve unavailable: ${e.message?:"invalid model data"}")}
fun selectedCurves(all:List<Webbing>,ids:List<String>)=all.filter{it.id in ids}
fun toggleCurve(ids:List<String>,id:String)=if(id in ids)ids-id else ids+id
fun automaticCurveLimit(materials:List<Webbing>)=materials.map{it.maxStrain*100}.filter{it.isFinite() && it>0}.maxOrNull()?:10.0
fun curveRangeError(min:String,max:String):String? {
    val a=min.replace(',','.').toDoubleOrNull();val b=max.replace(',','.').toDoubleOrNull()
    return if(a==null || b==null || !a.isFinite() || !b.isFinite() || a<0 || b<=a || b>1e6)"Enter 0 ≤ minimum < maximum ≤ 1,000,000 %." else null
}

/** Presentation warning only; never changes force evaluation or solver acceptance. */
fun curveUseWarnings(values:List<Pair<Material,Double>>):List<String> = values.mapNotNull{(material,strain)->
    val m=material as? Webbing?:return@mapNotNull null
    if(strain<=0)return@mapNotNull null
    val domain=m.suppliedDomain()
    when {
        m.provisionalLinear -> "${m.name}: ${m.modelWarning}"
        domain==null -> "${m.name}: supplied-data range unknown; source support cannot be established."
        !domain.contains(strain) -> "${m.name}: calculation uses force values outside the supplied-data range (${domain.lower*100}–${domain.upper*100} %). See Material Curves."
        else -> null
    }
}.distinct()

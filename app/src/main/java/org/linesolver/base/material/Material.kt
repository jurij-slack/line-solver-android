package org.linesolver.base.material

import kotlinx.serialization.json.*
import kotlin.math.*

/** SI: engineering strain, tension N, energy J/m of reference length. Python materials.py. */
interface Material {
    val name: String
    val density: Double?
    val maxStrain: Double
    val measuredMax: Double
    val breakingStrain: Double?
    fun tension(strain: Double): Double
    fun energy(strain: Double): Double
    fun tangent(strain: Double): Double
    fun strainAt(force: Double): Double {
        require(force.isFinite() && force >= 0)
        require(force <= tension(maxStrain)) { "Pretension exceeds material domain: $name" }
        var lo=0.0; var hi=maxStrain
        repeat(70) { val mid=(lo+hi)/2; if(tension(mid)<force) lo=mid else hi=mid }
        return (lo+hi)/2
    }
}
class MaterialEvaluationFailure(message:String,cause:Exception):RuntimeException(message,cause)

class MaterialLimit(val materialName: String, val strain: Double, val rupture: Boolean):
    IllegalArgumentException("$materialName: ${if(rupture) "MBS reached; no post-rupture law" else "measured material domain exceeded"} (strain $strain)")

class LinearMaterial(val ea:Double, override val density:Double): Material {
    init { require(ea.isFinite() && ea>0 && density.isFinite() && density>=0) }
    override val name="Linear EA"
    override val maxStrain=Double.POSITIVE_INFINITY
    override val measuredMax=Double.POSITIVE_INFINITY
    override val breakingStrain:Double?=null
    override fun tension(strain:Double)=ea*max(0.0,strain)
    override fun energy(strain:Double)=.5*ea*max(0.0,strain).pow(2)
    override fun tangent(strain:Double)=if(strain>=0) ea else 0.0
    override fun strainAt(force:Double):Double { require(force.isFinite() && force>=0);return force/ea }
}

/** Fritsch–Butland PCHIP slopes and analytic antiderivative, matching SciPy. */
class Pchip(val x:DoubleArray,val y:DoubleArray) {
    private val h=DoubleArray(x.size-1){x[it+1]-x[it]}
    private val d=DoubleArray(h.size){(y[it+1]-y[it])/h[it]}
    private val m=DoubleArray(x.size)
    private val coefficients:Array<DoubleArray>
    private val integral:DoubleArray
    init {
        require(x.size==y.size && x.size>=2 && x[0]==0.0 && y[0]==0.0)
        require(x.all{it.isFinite()} && y.all{it.isFinite()} && h.all{it>0} && d.all{it>0})
        if(x.size==2) {m[0]=d[0];m[1]=d[0]} else {
            for(i in 1 until x.lastIndex) {
                val w1=2*h[i]+h[i-1];val w2=h[i]+2*h[i-1]
                m[i]=(w1+w2)/(w1/d[i-1]+w2/d[i])
            }
            fun end(h0:Double,h1:Double,d0:Double,d1:Double):Double {
                val v=((2*h0+h1)*d0-h0*d1)/(h0+h1)
                return if(sign(v)!=sign(d0)) 0.0 else if(sign(d0)!=sign(d1) && abs(v)>3*abs(d0)) 3*d0 else v
            }
            m[0]=end(h[0],h[1],d[0],d[1])
            m[m.lastIndex]=end(h.last(),h[h.lastIndex-1],d.last(),d[d.lastIndex-1])
        }
        coefficients=Array(h.size){i -> doubleArrayOf(y[i],m[i],(3*d[i]-2*m[i]-m[i+1])/h[i],(m[i]+m[i+1]-2*d[i])/(h[i]*h[i]))}
        integral=DoubleArray(x.size)
        for(i in h.indices) integral[i+1]=integral[i]+primitive(i,h[i])
    }
    private fun segment(e:Double)=x.binarySearch(e).let{if(it>=0) min(it,x.size-2) else (-it-2).coerceIn(0,x.size-2)}
    private fun primitive(i:Int,z:Double):Double {val c=coefficients[i];return z*(c[0]+z*(c[1]/2+z*(c[2]/3+z*c[3]/4)))}
    fun value(e:Double):Double {val i=segment(e);val z=e-x[i];val c=coefficients[i];return c[0]+z*(c[1]+z*(c[2]+z*c[3]))}
    fun slope(e:Double):Double {val i=segment(e);val z=e-x[i];val c=coefficients[i];return c[1]+z*(2*c[2]+z*3*c[3])}
    fun energy(e:Double):Double {val i=segment(e);return integral[i]+primitive(i,e-x[i])}
}

class Webbing(val id:String,val metadata:JsonObject):Material {
    // Carried in diagnostic snapshots too: replay must not infer legacy continuation for imports.
    private val catalogueMaxStrain=metadata["catalogue_max_strain"]?.jsonPrimitive?.doubleOrNull
    val manufacturer=metadata.getValue("manufacturer").jsonPrimitive.content
    val product=metadata.getValue("product").jsonPrimitive.content
    override val name="$manufacturer $product"
    override val density=metadata["mass_per_m"]?.jsonPrimitive?.doubleOrNull
    val isDefault=metadata["default_for_webbing"]?.jsonPrimitive?.booleanOrNull==true
    private val x=metadata.getValue("strain_fraction").jsonArray.map{it.jsonPrimitive.double}.toDoubleArray()
    private val y=metadata.getValue("tension_N").jsonArray.map{it.jsonPrimitive.double}.toDoubleArray()
    val provisionalLinear=metadata["interpolation"]?.jsonPrimitive?.contentOrNull=="LINEAR"
    val modelWarning:String? get()=if(provisionalLinear)"Linear approximation — insufficient force–elongation data." else null
    init {
        if(metadata["model_origin"]?.jsonPrimitive?.contentOrNull=="imported" || metadata.containsKey("catalogue_max_strain")) {
            require(catalogueMaxStrain!=null && catalogueMaxStrain.isFinite() && catalogueMaxStrain>0) {"Imported model requires a finite explicit domain"}
        }
        require(!provisionalLinear || (x.size==2 && metadata["model_type"]?.jsonPrimitive?.contentOrNull=="provisional_linear_one_point")) {"Provisional linear model requires exactly one positive source point"}
        require(metadata["independent_variable"]?.jsonPrimitive?.contentOrNull=="strain_fraction" &&
            metadata["tension_unit"]?.jsonPrimitive?.contentOrNull=="N" &&
            metadata["interpolation"]?.jsonPrimitive?.contentOrNull in listOf("PCHIP","LINEAR")) {"Unsupported material units or interpolation"}
        require(x.size==y.size && x.size>=2) {"At least origin and one force point required"}
        for(key in listOf("mass_per_m","breaking_strength_N","breaking_strain")) {
            metadata[key]?.takeUnless{it is JsonNull}?.let {
                val n=it.jsonPrimitive.doubleOrNull
                require(n!=null && n.isFinite() && if(key=="mass_per_m")n>=0 else n>0){"Invalid $key"}
            }
        }
    }
    private val curve=Pchip(x,y)
    override val measuredMax=x.last()
    val mbs=metadata["breaking_strength_N"]?.jsonPrimitive?.doubleOrNull
    val hasImposedOrigin get()=metadata["imposed_origin"]?.jsonPrimitive?.booleanOrNull
        ?: (id in AuditedImposedOrigins.ids)
    val continuation:Double?
    val extrapolationReason:String
    override val breakingStrain:Double?
    override val maxStrain:Double
    init {
        val documented=metadata["breaking_strain"]?.jsonPrimitive?.doubleOrNull
        if(documented!=null) require(!metadata["breaking_strain_source"]?.jsonPrimitive?.contentOrNull.isNullOrBlank())
        if(provisionalLinear && documented!=null)require(mbs!=null && abs(documented-mbs/(y[1]/x[1]))<=1e-12+1e-8*abs(documented)){"Documented endpoint conflicts with provisional linear slope"}
        var k:Double?=null;var breaking:Double?=null
        if(catalogueMaxStrain!=null) {
            require(catalogueMaxStrain.isFinite() && catalogueMaxStrain>0 && catalogueMaxStrain<=measuredMax)
            require(documented==null) {"Catalogue models cannot invent a rupture endpoint"}
            require(mbs==null || y.last()<=mbs) {"Catalogue source exceeds declared MBS; review required"}
            if(mbs==y.last() && catalogueMaxStrain==measuredMax)breaking=measuredMax
        } else if(mbs!=null) {
            require(mbs>0 && mbs.isFinite())
            if(mbs<=y.last()) {
                var lo=0.0;var hi=measuredMax
                repeat(70){val mid=(lo+hi)/2;if(curve.value(mid)<mbs) lo=mid else hi=mid}
                breaking=if(mbs==y.last()) measuredMax else (lo+hi)/2
                if(documented!=null)require(abs(documented-breaking)<=1e-12+1e-8*abs(breaking)){"Documented breaking strain conflicts with curve at MBS"}
            } else if(documented!=null) {
                require(documented>measuredMax); k=(mbs-y.last())/(documented-measuredMax);breaking=documented
            } else if(provisionalLinear) {
                k=y[1]/x[1];breaking=mbs/k
            } else if(x.size>=3) {
                val positive=y.drop(1);var start=min(positive.indexOfFirst{it>=.8*y.last()},max(0,positive.size-4))
                if(start>0 && positive[start]>.8*y.last()) start--
                val slopes=mutableListOf<Double>()
                for(i in start+1 until x.lastIndex) for(j in i+1..x.lastIndex) slopes+=(y[j]-y[i])/(x[j]-x[i])
                slopes.sort();k=if(slopes.size%2==1) slopes[slopes.size/2] else (slopes[slopes.size/2-1]+slopes[slopes.size/2])/2
                breaking=measuredMax+(mbs-y.last())/k
            }
        }
        if(k!=null)require(k.isFinite() && k>0 && breaking!=null && breaking.isFinite() && breaking>measuredMax) {"Continuation requires finite positive stiffness and a resolvable breaking strain"}
        continuation=k;breakingStrain=breaking;maxStrain=catalogueMaxStrain?:breaking?:measuredMax
        extrapolationReason=when {
            catalogueMaxStrain!=null->"Imported model: no upper continuation beyond supplied data. "+(modelWarning?:"Imposed origin is an approximation.")
            provisionalLinear->modelWarning+if(mbs!=null)" Bounded by stated MBS." else " No upper continuation without MBS."
            mbs==null->"MBS unavailable; no upper continuation"
            mbs<=y.last()->"MBS lies within supplied curve; no upper continuation"
            documented!=null->"Linear continuation to sourced rupture endpoint"
            k!=null->"Estimated terminal stiffness; unmeasured linear continuation to MBS"
            else->"Insufficient terminal data: need two positive-force points or a sourced rupture endpoint"
        }
    }
    private fun checked(e:Double):Double {require(e.isFinite());if(e>maxStrain) throw MaterialLimit(name,e,breakingStrain!=null);return max(e,0.0)}
    override fun tension(strain:Double):Double {val e=checked(strain);return if(catalogueMaxStrain!=null && e==measuredMax)y.last() else if(e>measuredMax) y.last()+continuation!!*(e-measuredMax) else curve.value(e)}
    override fun energy(strain:Double):Double {val e=checked(strain);val z=e-measuredMax;return if(z>0) curve.energy(measuredMax)+y.last()*z+.5*continuation!!*z*z else curve.energy(e)}
    override fun tangent(strain:Double):Double {val e=checked(strain);return if(strain<0) 0.0 else if(e>measuredMax) continuation!! else curve.slope(e)}
    override fun strainAt(force:Double):Double {
        if(catalogueMaxStrain!=null) {
            require(force.isFinite() && force>=0)
            // A zero terminal slope can lose inverse precision at an exact knot. Preserve the
            // supplied inverse pair, including the endpoint, without extending either domain.
            val knot=y.binarySearch(force)
            if(knot>=0 && x[knot]<=maxStrain)return x[knot]
        }
        return super<Material>.strainAt(force)
    }
}

interface MaterialRepository {
    fun all():List<Webbing> = manufacturers().flatMap{webbings(it)}.distinctBy{it.id}
    fun manufacturers():List<String>
    fun webbings(manufacturer:String):List<Webbing>
    fun get(id:String):Webbing
    fun previousRevisions(id:String):List<JsonObject> = emptyList()
    fun alternatives(id:String):List<Webbing> {
        val productId=get(id).metadata["webbing_id"]
        return all().filter{it.metadata["webbing_id"]==productId}
    }
    fun limitations(id:String):List<String> = listOfNotNull(get(id).modelWarning)
}
class JsonMaterialRepository(text:String):MaterialRepository {
    private val root=Json.parseToJsonElement(text).jsonObject
    override fun previousRevisions(id:String)=root["previous_model_revisions"]?.jsonObject?.get(id)?.jsonArray?.map{it.jsonObject}?:emptyList()
    val models=root.getValue("models").jsonObject.mapValues{(id,v)->Webbing(id,v.jsonObject)}
    override fun all()=models.values.sortedBy{it.name.lowercase()+it.id}
    override fun manufacturers()=models.values.filter{it.isDefault}.map{it.manufacturer}.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    override fun webbings(manufacturer:String)=models.values.filter{it.isDefault && it.manufacturer==manufacturer}.sortedBy{it.product.lowercase()}
    override fun get(id:String)=requireNotNull(models[id]){"Unknown material ID: $id"}
}

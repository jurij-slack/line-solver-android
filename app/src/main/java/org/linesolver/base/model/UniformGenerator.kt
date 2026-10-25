package org.linesolver.base.model

import kotlinx.serialization.Serializable
import org.linesolver.base.contact.ConnectionBehavior
import kotlin.math.abs

/** The sole section source in generated Uniform mode. Metres means TOTAL prepared extra. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable data class UniformGenerator(val count:String="1",val main:Selection=Selection(),val backup:Selection=Selection(),
    val extra:String="",val extraUnit:ExtraUnit=ExtraUnit.PERCENT,val connection:ConnectionBehavior=ConnectionBehavior.STOP,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val instanceIds:List<String> = emptyList()) {
    fun errors(span:String):List<String> = buildList {
        val n=count.toIntOrNull();val length=span.number();val amount=extra.number()
        if(instanceIds.isNotEmpty() && (instanceIds.size!=n || instanceIds.any{it.isBlank()} || instanceIds.distinct().size!=instanceIds.size))add("Generated sections require unique stable instance IDs matching their count.")
        if(n !in 1..100)add("Uniform sections: enter a whole number from 1 to 100 (mobile resource bound).")
        if(length==null || length<=0)add("Uniform span: enter a finite number greater than 0 m.")
        if(amount==null || amount<0)add("Uniform backup extra: enter a finite nonnegative number.")
        if(n in 1..100 && length!=null && length>0 && length/n!!<=0)add("Uniform section length cannot be represented.")
        if(length!=null && amount!=null && length>0 && amount>=0) {
            val ratio=if(extraUnit==ExtraUnit.PERCENT)amount/100 else amount/length
            if(!ratio.isFinite() || !(length*(1+ratio)).isFinite())add("Uniform backup length cannot be represented.")
        }
    }
    fun generate(span:String):List<SectionDraft> {
        if(errors(span).isNotEmpty())return emptyList()
        val n=count.toInt();val length=span.number()!!/n
        val localExtra=if(extraUnit==ExtraUnit.METRES)(extra.number()!!/n).toString() else extra
        return List(n){SectionDraft(length.toString(),main,backup,localExtra,extraUnit,connection,id=instanceIds.getOrNull(it).orEmpty())}
    }
    fun withExtraUnit(unit:ExtraUnit,span:String):UniformGenerator {
        if(unit==extraUnit)return this
        val length=span.number();val amount=extra.number()
        val value=if(length!=null && length>0 && amount!=null)
            if(unit==ExtraUnit.METRES)amount/100*length else amount/length*100 else null
        return copy(extraUnit=unit,extra=value?.takeIf{it.isFinite()}?.toString()?:"")
    }
}
fun resolveSections(kind:SetupKind,sections:List<SectionDraft>,uniform:UniformGenerator?,span:String)=
    if(kind==SetupKind.UNIFORM)uniform?.generate(span)?:sections.take(1) else sections
val Draft.activeSections get()=resolveSections(kind,sections,uniform,span)
/** Material snapshots must also work for incomplete/invalid generator inputs. */
val Draft.materialSections get()=if(kind==SetupKind.UNIFORM && uniform!=null)
    listOf(SectionDraft(main=uniform.main,backup=uniform.backup)) else sections
fun Draft.withUniform(generator:UniformGenerator)=copy(kind=SetupKind.UNIFORM,uniform=generator,sections=emptyList())
fun freshGeneratorDraft()=Draft().withUniform(UniformGenerator())
fun Draft.toMixed():Draft {
    require(uniform==null || uniform.errors(span).isEmpty()){"Complete the generator before converting."}
    return copy(kind=SetupKind.MIXED,uniform=null,sections=activeSections)
}
/** A replacement proposal, never applied without checking equivalence or confirming replacement. */
fun Draft.uniformProposal():UniformGenerator {
    val active=activeSections;val first=active.firstOrNull()?:SectionDraft()
    return UniformGenerator(active.size.coerceAtLeast(1).toString(),first.main,first.backup,
        if(first.extraUnit==ExtraUnit.METRES)first.ratio()?.let{r->span.number()?.let{r*it}}?.takeIf{it.isFinite()}?.toString()?:first.extra else first.extra,
        first.extraUnit,first.connection,active.map{it.id}.takeIf{ids->ids.all{it.isNotBlank()}} ?: emptyList())
}
private fun SectionDraft.ratio()=extra.number()?.let{if(extraUnit==ExtraUnit.PERCENT)it/100 else length.number()?.takeIf{n->n>0}?.let{n->it/n}}
fun samePhysicalSections(a:List<SectionDraft>,b:List<SectionDraft>):Boolean {
    if(a.size!=b.size || a.isEmpty())return false
    return a.zip(b).all{(x,y)->
        val xl=x.length.number();val yl=y.length.number();val xr=x.ratio();val yr=y.ratio()
        xl!=null && xl>0 && yl!=null && xr!=null && yr!=null &&
            abs(xl-yl)<=1e-12*maxOf(1.0,abs(xl)) && abs(xr-yr)<=1e-12*maxOf(1.0,abs(xr)) &&
            x.main==y.main && x.backup==y.backup && x.connection==y.connection
    }
}
fun Draft.uniformReplacementNeeded():Boolean =
    (kind==SetupKind.UNIFORM && uniform==null && sections.size>1) ||
        !samePhysicalSections(activeSections,uniformProposal().generate(span))

/** Reference-length fractions only; solved split coordinates remain solver-owned. */
fun Draft.schematicSplitFractions():List<Double> {
    val lengths=activeSections.map{it.length.number()?:return emptyList()}
    val total=lengths.sum()
    if(lengths.any{it<=0} || !total.isFinite() || total<=0)return emptyList()
    var cumulative=0.0
    return lengths.dropLast(1).map{cumulative+=it;cumulative/total}
}

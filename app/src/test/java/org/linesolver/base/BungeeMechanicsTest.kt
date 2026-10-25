package org.linesolver.base

import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.material.*
import org.linesolver.base.material.catalogue.*
import org.linesolver.base.model.*
import org.linesolver.base.energy.*
import org.linesolver.base.solver.*
import org.linesolver.base.diagnostics.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.*

class BungeeMechanicsTest {
    private val law=BungeeLaw(basis=BungeeBasis.physical_cord,testedPhysicalLengthM=2.0,source="Synthetic analytical fixture only",idealEqualLegs=true)
    private fun instance(id:String="b",rest:Double=2.0,legs:Int=1,limit:Double?=null)=InstalledBungee(id,ModelReference("synthetic","1","fixture"),rest,legs,limiterPhysicalLengthM=limit,source="Synthetic fixture")
    private fun material(rest:Double=2.0,legs:Int=1,mass:Double=1.0,ea:Double=100.0)=FoldedBungeeMaterial(LinearMaterial(ea,0.0),rest,legs,law,mass)
    private fun derivative(f:(Double)->Double,x:Double,h:Double=1e-6)=(f(x+h)-f(x-h))/(2*h)
    @Test fun singleCordLinearSlackInverseAndEnergyDerivative() {
        val m=material();val rest=m.effectiveRestM
        for(e in listOf(-.3,0.0,.01,.5,1.0)) {
            assertEquals(100*max(e,0.0),m.tension(e),0.0)
            assertEquals(100*max(e,0.0).pow(2),rest*m.energy(e),1e-12)
            if(e>0) { assertEquals(e,m.strainAt(m.tension(e)),1e-12)
                assertEquals(m.tension(e),derivative({L->rest*m.energy(L/rest-1)},rest*(1+e)),1e-7)
                assertEquals(m.tangent(e)/rest,derivative({L->m.tension(L/rest-1)},rest*(1+e)),1e-7) }
        }
    }
    @Test fun equalTwoAndFourLegFoldsCountCordEnergyAndMassOnce() {
        for(legs in listOf(1,2,4)) {
            val m=material(legs=legs);val e=.4
            assertEquals(2.0/legs,m.effectiveRestM,0.0)
            assertEquals(legs*40.0,m.tension(e),1e-12)
            assertEquals(16.0,m.effectiveRestM*m.energy(e),1e-12)
            assertEquals(1.0,m.effectiveRestM*m.density,1e-12)
            assertEquals(m.tension(e),derivative({L->m.effectiveRestM*m.energy(L/m.effectiveRestM-1)},m.effectiveRestM*(1+e)),1e-7)
            assertEquals(4.0/legs,instance(legs=legs,limit=4.0).limiterPhysicalLengthM!!/legs,0.0)
        }
    }
    @Test fun measuredFoldedAssemblyIsNotScaledAgainAndUnsupportedConversionsFail() {
        val folded=law.copy(basis=BungeeBasis.folded_assembly,testedLegs=2)
        val m=FoldedBungeeMaterial(LinearMaterial(100.0,0.0),2.0,2,folded,1.0)
        assertEquals(40.0,m.tension(.4),1e-12);assertEquals(8.0,m.effectiveRestM*m.energy(.4),1e-12)
        assertThrows(IllegalArgumentException::class.java){FoldedBungeeMaterial(m,2.0,4,folded,1.0)}
        assertThrows(IllegalArgumentException::class.java){material(rest=3.0)}
        assertThrows(IllegalArgumentException::class.java){FoldedBungeeMaterial(m,2.0,2,law.copy(idealEqualLegs=false),1.0)}
        assertThrows(IllegalArgumentException::class.java){FoldedBungeeMaterial(m,2.0,1,law.copy(basis=BungeeBasis.strand_or_layer),1.0)}
        assertThrows(IllegalArgumentException::class.java){law.copy(scalablePerLength=true).validate()}
        val scalable=FoldedBungeeMaterial(LinearMaterial(100.0,0.0),3.0,2,law.copy(scalablePerLength=true,scalingEvidence="Synthetic per-length cord assumption"),1.5)
        assertEquals(1.5,scalable.effectiveRestM,0.0)
    }
    @Test fun nonlinearSyntheticPchipDomainAndFiniteDifference() {
        val c=Pchip(doubleArrayOf(0.0,.3,1.0,2.0),doubleArrayOf(0.0,100.0,600.0,2000.0))
        val m=object:Material {
            override val name="Synthetic rubber";override val density=0.0;override val maxStrain=2.0;override val measuredMax=2.0;override val breakingStrain:Double?=null
            private fun e(x:Double):Double { require(x.isFinite() && x<=2);return max(0.0,x) }
            override fun tension(strain:Double)=c.value(e(strain));override fun energy(strain:Double)=c.energy(e(strain))
            override fun tangent(strain:Double)=if(strain<0)0.0 else c.slope(e(strain))
        }
        for(e in listOf(.0001,.15,.3,.7,1.5,1.999)) {
            assertEquals(m.tension(e),derivative(m::energy,e),2e-6)
            assertEquals(m.tangent(e),derivative(m::tension,e),.002)
            assertEquals(e,m.strainAt(m.tension(e)),1e-10)
        }
        assertThrows(IllegalArgumentException::class.java){m.tension(2.001)}
    }
    private fun series(limit:Double?=null):Network {
        val coords=doubleArrayOf(0.0,0.0,0.0,1.2,0.0,3.0)
        val elements=listOf(Element(0,1,1.0,0.0,LinearMaterial(100.0,0.0),0,Cable.MAIN,0.0,1.0),
            Element(1,2,1.0,0.0,LinearMaterial(200.0,0.0),1,Cable.MAIN,0.0,1.0))
        return Network(coords,elements,listOf(0,2),null,0.0,0.0,3.0,bungeePaths=listOf(BungeePath(instance(rest=1.0,limit=limit),listOf(0),limit)))
    }
    @Test fun seriesForceBalanceAndAnalyticComplementaryExtension() {
        val n=series();val s=Equilibrium().solve(n,fixedExtra=mapOf(2 to 0.0))
        assertEquals(5.0/3,s.r[3],1e-9)
        assertEquals(200.0/3,s.value.tensions[0],1e-7);assertEquals(s.value.tensions[0],s.value.tensions[1],1e-7)
        assertTrue(s.residualN<2e-6)
        assertEquals(100.0/3,s.value.elastic,1e-8)
    }
    @Test fun idealLimiterReactionStationarityComplementarityAndOffState() {
        for(limit in listOf(1.0,1.4,5.0/3,1.8)) {
            val n=series(limit);val s=Equilibrium().solve(n,fixedExtra=mapOf(2 to 0.0))
            val L=n.bungeePaths.single().length(n,s.r);val R=s.limiterReactionsN.getValue("b")!!
            assertTrue(L<=limit+1e-9);assertTrue(R>=0);assertTrue(abs(R*(L-limit))<1e-7)
            assertEquals(if(limit<5.0/3)limit else 5.0/3,L,1e-8)
            assertEquals(if(limit<5.0/3)500.0-300*limit else 0.0,R,1e-6)
            assertTrue(s.residualN<=2e-6);assertTrue(s.constraintViolationM<=1e-9)
            assertEquals(s.value.tensions[1],s.value.tensions[0]+R,2e-6)
        }
        assertEquals(5.0/3,Equilibrium().solve(series(),fixedExtra=mapOf(2 to 0.0)).r[3],1e-9)
    }
    @Test fun infeasiblePrescribedGeometryIsRejectedInsteadOfClipped() {
        val n=series(1.4)
        assertThrows(SolverWarning::class.java){Equilibrium().solve(n,fixedExtra=mapOf(2 to 0.0,3 to 1.5))}
    }
    @Test fun independentlyEngagingParallelLimitersAndIndeterminateSharedReaction() {
        for(secondLimit in listOf(1.6,1.4)) {
            val base=series(1.4)
            val other=base.elements.first().copy(rest=if(secondLimit==1.4)1.0 else 1.2)
            val n=base.copy(elements=base.elements+other,bungeePaths=base.bungeePaths+BungeePath(instance(id="other",rest=other.rest,limit=secondLimit),listOf(2),secondLimit))
            val s=Equilibrium().solve(n,fixedExtra=mapOf(2 to 0.0))
            assertEquals(1.4,s.r[3],1e-8);assertTrue(s.residualN<=2e-6)
            if(secondLimit==1.4) {assertNull(s.limiterReactionsN["b"]);assertNull(s.limiterReactionsN["other"])}
            else {assertEquals(190.0/3,s.limiterReactionsN.getValue("b")!!,1e-6);assertEquals(0.0,s.limiterReactionsN.getValue("other")!!,1e-8)}
            assertEquals(0.0,s.totalGradient()[3],2e-6)
        }
    }
    @Test fun twoDimensionalActiveLimitersBalanceGravityWithoutForceClipping() {
        val limit=1.6;val n=Network(doubleArrayOf(-1.0,0.0,0.0,-1.3,1.0,0.0),listOf(
            Element(0,1,1.0,80.0,LinearMaterial(100.0,0.0),0,Cable.MAIN,0.0,1.0),
            Element(1,2,1.0,80.0,LinearMaterial(100.0,0.0),1,Cable.MAIN,0.0,1.0)),listOf(0,2),null,0.0,9.81,2.0,
            bungeePaths=listOf(BungeePath(instance("left",1.0,limit=limit),listOf(0),limit),BungeePath(instance("right",1.0,limit=limit),listOf(1),limit)))
        val s=Equilibrium().solve(n);val depth=sqrt(limit*limit-1)
        assertEquals(0.0,s.r[2],1e-9);assertEquals(-depth,s.r[3],1e-9)
        val rubber=100*(limit-1);val reaction=80*9.81*limit/(2*depth)-rubber
        for(id in listOf("left","right"))assertEquals(reaction,s.limiterReactionsN.getValue(id)!!,1e-6)
        assertTrue(s.limiterReactionsN.values.all{it!!>0});assertTrue(s.residualN<2e-6)
        assertEquals(36.0,s.value.elastic,1e-8)
        val total=s.totalGradient();assertEquals(160*9.81,total[1]+total[5],2e-6)
    }
    @Test fun unequalParallelAssembliesResolveTensionsAndEnergySeparately() {
        val elements=listOf(Element(0,1,1.0,.4,LinearMaterial(100.0,0.0),0,Cable.MAIN,0.0,1.0),
            Element(0,1,1.5,.7,LinearMaterial(200.0,0.0),0,Cable.MAIN,0.0,1.0),
            Element(0,1,3.0,.9,LinearMaterial(500.0,0.0),0,Cable.MAIN,0.0,1.0))
        val n=Network(doubleArrayOf(0.0,0.0,2.0,0.0),elements,listOf(0,1),null,0.0,9.81,2.0)
        val v=n.evaluate(n.coordinates)
        assertArrayEquals(doubleArrayOf(100.0,200.0/3,0.0),v.tensions,1e-10)
        assertEquals(100.0+200.0/3,v.gradient[2],1e-10)
        assertEquals(50.0+100.0/6,v.elastic,1e-10);assertEquals(2.0,n.elements.sumOf{it.mass},1e-12)
        val doubled=n.copy(elements=listOf(elements[0],elements[0])).evaluate(n.coordinates)
        assertEquals(200.0,doubled.tensions.sum(),1e-10);assertEquals(100.0,doubled.elastic,1e-10)
    }
    @Test fun wholeAssemblyConstraintGradientHessianAndMeshInventory() {
        for(count in listOf(2,4,8)) {
            val coords=DoubleArray(2*(count+1));for(j in 0..count){coords[2*j]=2.0*j/count;coords[2*j+1]=-.1*sin(PI*j/count)}
            val elements=(0 until count).map{j->Element(j,j+1,2.0/count,1.0/count,material(),0,Cable.MAIN,j.toDouble()/count,(j+1.0)/count)}
            val p=BungeePath(instance(limit=3.0),elements.indices.toList(),3.0)
            val n=Network(coords,elements,listOf(0,count),null,0.0,9.81,2.0,bungeePaths=listOf(p))
            val c=p.constraint(n,coords)
            assertEquals(1,n.bungeePaths.size);assertEquals(1.0,elements.sumOf{it.mass},1e-12);assertEquals(2.0,elements.sumOf{it.rest},1e-12)
            for(i in coords.indices) {
                val a=coords.copyOf();val b=coords.copyOf();a[i]+=1e-6;b[i]-=1e-6
                assertEquals(c.gradient[i],(p.length(n,a)-p.length(n,b))/2e-6,1e-8)
                val ca=p.constraint(n,a);val cb=p.constraint(n,b)
                for(j in coords.indices)assertEquals(c.hessian[j][i],(ca.gradient[j]-cb.gradient[j])/2e-6,1e-6)
            }
            val v=n.evaluate(coords,hessian=true)
            for(i in coords.indices) { val a=coords.copyOf();val b=coords.copyOf();a[i]+=1e-6;b[i]-=1e-6
                assertEquals(v.gradient[i],(n.evaluate(a).total-n.evaluate(b).total)/2e-6,1e-7) }
        }
    }
    @Test fun distributedGravityHasMeshIndependentVerticalExtensionAndForceBalance() {
        for(count in listOf(2,4,8)) {
            val coords=DoubleArray(2*(count+2));for(j in 0..count)coords[2*j+1]=-2.0*j/count
            val elements=(0 until count).map{j->Element(j,j+1,2.0/count,1.0/count,LinearMaterial(100.0,0.0),0,Cable.MAIN,j.toDouble()/count,(j+1.0)/count)}
            val n=Network(coords,elements,listOf(0,count+1),null,0.0,9.81,2.0,bungeePaths=listOf(BungeePath(instance(),elements.indices.toList(),null)))
            val fixed=(1..count).associate{2*it to 0.0};val s=Equilibrium().solve(n,fixedExtra=fixed)
            assertEquals(-2.0*(1+9.81/200),s.r[2*count+1],1e-8)
            assertEquals(9.81,s.value.gradient[1],2e-6);assertTrue(s.residualN<2e-6)
            val exactEnergy=2.0*9.81.pow(2)/(6*100.0)
            assertEquals(exactEnergy*(1-1.0/(4*count*count)),s.value.elastic,1e-8)
        }
    }
    private fun repository():Pair<MaterialOverlayStore,CatalogueMaterialRepository> {
        val root=File(requireNotNull(System.getProperty("catalogueProjectRoot")))
        val legacy=JsonMaterialRepository(File(root,"data/materials/webbing_material_models_v1.json").readText())
        val shipped=CatalogueMaterialRepository(legacy,File(root,"catalogue/webbing-catalogue-v1.json").readText()).document
        var disk:String?=null;val store=MaterialOverlayStore(shipped,{disk},{disk=it})
        MaterialEditing.save(store,MaterialEdit(name="Synthetic cord",brand="Fixture",category=ProductCategory.bungee,
            bungee=law,source="Synthetic source; no experimental validation",mass="0.5",table="T/N,epsilon/%\n100,100\n300,200",forceUnit="N"))
        return store to CatalogueMaterialRepository(legacy,CatalogueCodec.encode(MaterialPackages.combine(shipped,store.value)))
    }
    @Test fun basisRevisionPersistenceAndInvalidInstanceValidation() {
        val (store,repo)=repository();val model=store.value.catalogue.models.single()
        val a=instance().copy(model=model.reference())
        assertNull(model.specifications.mbsN)
        assertEquals(1.0,a.material(repo).physicalMassKg,0.0)
        val export=store.export();var text:String?=export;val restored=MaterialOverlayStore(store.shipped,{text},{text=it})
        assertEquals(export,restored.export());assertEquals(model.reference(),restored.value.catalogue.models.single().reference())
        val edit=MaterialEditing.load(repo.document,store.value.catalogue.datasets.single(),model.id)
        MaterialEditing.save(store,edit.copy(table="T/N,epsilon/%\n200,100\n400,200"))
        assertEquals(model,store.value.catalogue.models.first());assertNotEquals(model.contentFingerprint,store.value.catalogue.models.last().contentFingerprint)
        for(invalid in listOf(a.copy(physicalRestM=3.0),a.copy(physicalRestM=0.0),a.copy(equalLegs=0),a.copy(physicalCordCount=2),a.copy(limiterPhysicalLengthM=1.9),a.copy(source=""),a.copy(model=a.model.copy(revision="unknown"))))
            assertThrows(IllegalArgumentException::class.java){invalid.material(repo)}
        assertTrue(repo.webbings("Fixture").isEmpty())
        val unmeasuredMass=MaterialEditing.preview(MaterialEdit(category=ProductCategory.bungee,bungee=law,name="Unknown mass cord",source="Synthetic unknown mass fixture",table="1,100\n3,200"))
        assertNull(unmeasuredMass.model!!.specifications.densityKgPerM)
        assertTrue(unmeasuredMass.problems.any{it.contains("mass unknown")})
    }
    @Test fun seriesPlacementAnywhereKeepsWebbingInventoryAndSeparateContactNodes() {
        val (store,repo)=repository();val model=store.value.catalogue.models.single();val a=instance().copy(model=model.reference())
        val m=repo.all().first{it.id.startsWith("raed") || it.manufacturer=="RAED"};val sel=Selection(m.manufacturer,m.id)
        val d=Draft(kind=SetupKind.MIXED,span="20",leftHeight="0",rightHeight="0",mass="80",position="5",pretensionKn="1",sections=listOf(SectionDraft("10",sel,sel,"5",id="s1"),SectionDraft("10",sel,sel,"5",id="s2")))
        val base=d.validate(repo).setup!!;val old=buildNetwork(base,repo,listOf(10.0,10.0),true)
        for(cable in Cable.entries) for(placement in BungeePlacement.entries) {
            val bank=BungeeBank("bank",placement,if(placement==BungeePlacement.series_right)null else "s2",cable,listOf(a,a.copy(id="other",equalLegs=2)))
            val rig=d.copy(bungees=BungeeLayout(banks=listOf(bank)));val setup=rig.validate(repo).setup!!
            val n=buildNetwork(setup,repo,listOf(10.0,10.0),true)
            assertEquals(old.elements,n.elements.take(old.elements.size).mapIndexed{i,e->e.copy(a=old.elements[i].a,b=old.elements[i].b)})
            assertEquals(2,n.bungeePaths.size);assertEquals(2.0,n.bungeePaths.sumOf{p->p.elementIndices.sumOf{n.elements[it].mass}},1e-12)
            assertEquals(old.attachment,n.attachment);assertNotEquals(n.attachment!!.mainNode,n.attachment.backupNode)
            for(other in Cable.entries.filter{it!=cable})assertEquals(old.elements.filter{it.cable==other},n.elements.filter{it.cable==other})
            assertEquals(rig,rig.toMixed())
            val cfg=RigConfiguration.from(rig);val doc=RigLibraryDocument(rigs=listOf(SavedRig("rig","Bungee",1,cfg)))
            val loaded=RigLibraryCodec.decode(RigLibraryCodec.encode(doc));assertEquals(3,loaded.schemaVersion);assertEquals(cfg,loaded.rigs.single().configuration)
            assertTrue(rig.copy(cutEnabled=true).validate(repo).errors.any{it.contains("Catch/failure")})
        }
    }
    @Test fun multipleSeriesBanksFollowReorderingAndKeepIndependentInventories() {
        val (store,repo)=repository();val model=store.value.catalogue.models.single()
        val m=repo.webbings("RAED").first{it.product=="Parsec"};val sel=Selection(m.manufacturer,m.id)
        val draft=Draft(kind=SetupKind.MIXED,span="20",leftHeight="0",rightHeight="0",mass="80",position="5",pretensionKn="1",elements="2",
            sections=listOf(SectionDraft("10",sel,sel,"50",id="s1"),SectionDraft("10",sel,sel,"50",id="s2")))
        val a=BungeeBank("first",BungeePlacement.series_before,"s2",Cable.MAIN,listOf(instance(id="cord-a",limit=3.0).copy(model=model.reference())))
        val b=BungeeBank("second",BungeePlacement.series_before,"s2",Cable.MAIN,listOf(instance(id="cord-b",legs=2,limit=4.0).copy(model=model.reference())))
        for(banks in listOf(listOf(a,b),listOf(b,a))) {
            val setup=draft.copy(bungees=BungeeLayout(banks=banks)).validate(repo).setup!!
            val n=buildNetwork(setup,repo,listOf(10.0,10.0),true)
            assertEquals(banks.map{it.assemblies.single().id},n.bungeePaths.map{it.instance.id})
            val first=n.bungeePaths[0];val second=n.bungeePaths[1]
            assertEquals(n.splits[1],n.elements[first.elementIndices.first()].a)
            assertEquals(n.elements[first.elementIndices.last()].b,n.elements[second.elementIndices.first()].a)
            val webbing=n.elements.first{it.section==1 && it.cable==Cable.MAIN && it.material !is FoldedBungeeMaterial}
            assertEquals(n.elements[second.elementIndices.last()].b,webbing.a)
            assertEquals(20.0,n.elements.filter{it.cable==Cable.MAIN && it.material !is FoldedBungeeMaterial}.sumOf{it.rest},1e-12)
            assertEquals(2.0,n.bungeePaths.sumOf{p->p.elementIndices.sumOf{n.elements[it].mass}},1e-12)
            for(path in n.bungeePaths) {
                assertEquals(2.0/path.instance.equalLegs,path.elementIndices.sumOf{n.elements[it].rest},1e-12)
                assertEquals(path.instance.limiterPhysicalLengthM!!/path.instance.equalLegs,path.limitM!!,0.0)
            }
        }
    }
    @Test fun installedRigCalibrationStandingAndWeblockStopsWithAndWithoutLimiter() {
        val (store,initial)=repository()
        val old=store.value.catalogue.models.single()
        val edit=MaterialEditing.load(initial.document,store.value.catalogue.datasets.single(),old.id)
        MaterialEditing.save(store,edit.copy(table="T/N,epsilon/%\n5000,100\n10000,200",bungee=law.copy(scalablePerLength=true,scalingEvidence="Synthetic analytical per-length law; not a physical measurement")))
        val root=File(requireNotNull(System.getProperty("catalogueProjectRoot")))
        val legacy=JsonMaterialRepository(File(root,"data/materials/webbing_material_models_v1.json").readText())
        val repo=CatalogueMaterialRepository(legacy,CatalogueCodec.encode(MaterialPackages.combine(store.shipped,store.value)))
        val model=store.value.catalogue.models.last();val m=repo.webbings("RAED").first{it.product=="Parsec"};val sel=Selection(m.manufacturer,m.id)
        val draft=Draft(kind=SetupKind.MIXED,span="20",leftHeight="0",rightHeight="0",mass="80",position="5",pretensionKn="1",elements="2",
            sections=listOf(SectionDraft("10",sel,sel,"50",id="s1"),SectionDraft("10",sel,sel,"50",id="s2")))
        for(placement in BungeePlacement.entries) for(limit in listOf<Double?>(null,2.3)) {
            val physicalRest=if(placement==BungeePlacement.parallel_section)8.0 else 2.0
            val physicalLimit=limit?.times(physicalRest/2.0)
            val a=instance(rest=physicalRest,limit=physicalLimit).copy(model=model.reference())
            val bank=BungeeBank("bank",placement,if(placement==BungeePlacement.series_right)null else "s2",Cable.MAIN,listOf(a))
            val setup=draft.copy(bungees=BungeeLayout(banks=listOf(bank))).validate(repo).setup!!
            val result=try { SimpleSolver(repo).solve(setup) } catch(e:Exception) { throw AssertionError("placement=$placement limiter=$limit: ${e.message}",e) }
            assertEquals(1000.0,result.achievedPretensionN,1e-5);assertEquals(10.0,result.installedLengths.first(),0.0)
            assertTrue(result.installedLengths.last()<=10.0);assertTrue(result.standing.residualN<=2e-6)
            assertEquals(physicalRest,result.standing.network.bungeePaths.single().elementIndices.sumOf{result.standing.network.elements[it].rest},1e-12)
            assertNull(result.catch);assertNull(result.rest)
            if(physicalLimit!=null)assertTrue(result.standing.bungeeResults().single().pathLengthM<=physicalLimit+1e-9)
            val n=result.standing.network
            val report=DiagnosticRunner.report(draft.copy(bungees=BungeeLayout(banks=listOf(bank))),repo,SolverSettings(),SolverTrace(),result,null)
            assertEquals(2,report.document.getValue("schema_version").jsonPrimitive.int)
            assertEquals(1,report.document.getValue("bungee_model_records").jsonArray.size)
            assertEquals(1,report.document.getValue("states").jsonObject.getValue("standing").jsonObject.getValue("bungees").jsonArray.size)
            assertThrows(IllegalArgumentException::class.java){DiagnosticRunner.replay(report.text())}
            if(placement==BungeePlacement.series_right) {
                val p=n.bungeePaths.single();val start=n.elements[p.elementIndices.first()].a
                val last=n.elements.last{it.cable==Cable.MAIN && it.material !is FoldedBungeeMaterial}
                assertEquals(last.b,start);assertNotEquals(n.splits.last(),last.b)
            }
        }
    }
    @Test fun hundredSectionAllWebbingRigRetainsSparseStaticPath() {
        val root=File(requireNotNull(System.getProperty("catalogueProjectRoot")))
        val repo=JsonMaterialRepository(File(root,"data/materials/webbing_material_models_v1.json").readText())
        val m=repo.webbings("RAED").first{it.product=="Parsec"};val sel=Selection(m.manufacturer,m.id)
        val sections=(0 until 100).map{i->SectionDraft(if(i==99)"2" else "0.49",sel,sel,"5")}
        val d=Draft(kind=SetupKind.MIXED,span="50",leftHeight="0",rightHeight="0",mass="80",position="25",pretensionKn="1",elements="2",sections=sections)
        val result=SimpleSolver(repo).solve(d.validate(repo).setup!!)
        assertEquals(100,result.installedLengths.size);assertTrue(result.standing.r.size>512)
        assertTrue(result.standing.network.bungeePaths.isEmpty());assertTrue(result.standing.residualN<=2e-6)
        assertEquals(1000.0,result.achievedPretensionN,1e-5)
        assertEquals(49*0.49,result.installedLengths.take(49).sum(),1e-12)
    }
}

package org.linesolver.base

import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.contact.ContactOptions
import org.linesolver.base.energy.*
import org.linesolver.base.material.JsonMaterialRepository
import org.linesolver.base.model.*
import org.linesolver.base.solver.*
import org.linesolver.base.ui.*

class BackupGeometryTest {
    private val materials=JsonMaterialRepository(javaClass.classLoader!!.getResource("webbing_material_models_v1.json")!!.readText())
    private val main=materials.webbings("RAED").first{it.product=="Parsec"}.id
    private val backup=materials.webbings("RAED").first{it.product=="MOTM"}.id
    private fun setup(position:Double=12.0)=LineSetup(Geometry(88.0,2.0,5.0),
        listOf(Section(20.0,main,backup,.12),Section(30.0,main,backup,.18),Section(40.0,main,backup,.09)),
        1500.0,Walker(80.0,position),null,ContactOptions(),9.81,8)
    private fun standing(position:Double)=Equilibrium().solve(buildNetwork(setup(position),materials,listOf(20.0,30.0,38.0),true))
    private fun actualCurve(s:StaticState,section:Int):List<Point> {
        fun p(i:Int)=Point(s.r[2*i],s.r[2*i+1])
        val es=s.network.elements.filter{it.cable==Cable.BACKUP && it.section==section}.sortedBy{it.from}
        return listOf(p(es.first().a))+es.map{p(it.b)}
    }
    private fun assertDownwardLoop(points:List<Point>) {
        val a=points.first();val b=points.last()
        assertTrue(arcLength(points)>distance(a,b)+1e-6)
        points.forEach { p->
            val u=(p.x-a.x)/(b.x-a.x)
            assertTrue(p.y<=a.y+(b.y-a.y)*u+1e-10)
        }
    }

    @Test fun asymmetricGuidePreservesWholeSectionLengthWithSlackOnBothSides() {
        for(x in listOf(.001,2.0,7.0,9.999)) {
            val ring=Point(x,-1.0)
            val curve=routedBackup(0,Point(0.0,2.0),ring,Point(10.0,3.0),18.0)
            assertEquals(ring,curve.points.single{it==ring})
            assertEquals(18.0,arcLength(curve.points),1e-9)
            assertEquals(0.0,curve.lengthDeficitM,0.0)
            val i=curve.points.indexOf(ring)
            assertDownwardLoop(curve.points.take(i+1));assertDownwardLoop(curve.points.drop(i))
        }
    }

    @Test fun solvedPositionsAndPhysicalConnectionsKeepSectionBudgetsSeparate() {
        for(q in listOf(2.0,12.0,20.0,35.0,50.0,75.0,86.0)) {
            val s=standing(q);val drawing=s.backupDrawing()
            assertEquals(s.attachmentPosition,drawing.ring)
            assertTrue(drawing.points.contains(drawing.ring))
            assertEquals(3,drawing.curves.size)
            for(curve in drawing.curves) {
                val original=actualCurve(s,curve.section)
                val budget=maxOf(arcLength(original),s.network.elements.filter{it.cable==Cable.BACKUP && it.section==curve.section}.sumOf{it.rest})
                assertEquals(original.first(),curve.points.first());assertEquals(original.last(),curve.points.last())
                assertEquals(budget,arcLength(curve.points),1e-8)
                if(curve.section!=s.network.attachment!!.contacts.main.section)assertEquals(original,curve.points)
            }
            assertEquals(0.0,drawing.lengthDeficitM,1e-8)
            assertTrue(drawing.threading(.2).isNotEmpty())
            if(q==20.0 || q==50.0)assertFalse(drawing.schematic)
        }
    }

    @Test fun draggingCrossesSectionsAtMainReferenceCoordinateWithoutFreezingBackupLabel() {
        val s=standing(12.0);val lengths=listOf(20.0,30.0,38.0)
        val original=s.backupDrawing()
        for(q in listOf(.1,19.9,20.0,20.1,49.9,50.0,50.1,87.9)) {
            val drawing=s.backupDrawing(q)
            assertEquals(s.pointAtReference(q,lengths),drawing.ring)
            assertTrue(drawing.points.contains(drawing.ring))
            assertNotEquals(original.ring,drawing.ring)
            drawing.curves.forEach { c->assertEquals(arcLength(actualCurve(s,c.section)),arcLength(c.points),1e-8) }
            assertEquals(0.0,drawing.lengthDeficitM,1e-8)
        }
    }

    @Test fun drawingAndSliderDoNotChangeMechanicsMaterialLabelsOrTopology() {
        val s=standing(12.0);val r=s.r.copyOf();val coordinates=s.network.coordinates.copyOf()
        val network=s.network;val elements=network.elements.toList();val contacts=network.attachment!!.contacts
        val before=network.evaluate(r,hessian=true)
        for(q in listOf(null,5.0,20.0,42.0,76.0))s.backupDrawing(q).threading(.25)
        assertArrayEquals(r,s.r,0.0);assertArrayEquals(coordinates,network.coordinates,0.0)
        assertSame(network,s.network);assertEquals(elements,network.elements);assertEquals(contacts,network.attachment!!.contacts)
        assertEquals(listOf(0,1,2,3),network.splits);assertNull(network.ringContactPair)
        assertNotEquals(network.attachment!!.mainNode,network.attachment!!.backupNode)
        val after=network.evaluate(s.r,hessian=true)
        assertEquals(before.total,after.total,0.0);assertArrayEquals(before.gradient,after.gradient,0.0)
        assertArrayEquals(before.tensions,after.tensions,0.0);assertArrayEquals(before.strains,after.strains,0.0)
        before.hessian.indices.forEach{assertArrayEquals(before.hessian[it],after.hessian[it],0.0)}
    }

    @Test fun failedStatesKeepSolvedBackupAndLeashIncludingRetainedMainContact() {
        for(q in listOf(12.0,35.0))for(cut in listOf(26.0,50.0)) {
            val rig=setup(q).copy(geometry=Geometry(70.0,0.0,0.0),sections=List(2){Section(35.0,main,backup,.05)},cut=Cut(cut,1.8,1.5))
            val result=SimpleSolver(materials).solve(rig)
            for(s in listOf(result.catch!!,result.rest!!)) {
                val drawing=s.backupDrawing();val body=s.walkerDrawing()!!
                assertFalse(drawing.schematic);assertEquals(s.attachmentPosition,drawing.ring)
                drawing.curves.forEach{assertEquals(actualCurve(s,it.section),it.points)}
                assertTrue(drawing.points.contains(body.attachment));assertEquals(body.attachment to body.harness,body.leash)
                assertEquals(1.5,body.attachment.y-body.harness.y,1e-12)
                assertTrue(body.com.y<body.attachment.y);assertTrue(drawing.threading(.2).isNotEmpty())
                assertEquals(result.standing.network.splits,s.network.splits)
            }
            assertEquals(0.0,result.fallEnergy!!.residualJ,1e-5)
        }
    }

    @Test fun insufficientLengthIsReportedRatherThanInventedAsSlack() {
        val a=Point(0.0,0.0);val b=Point(10.0,0.0);val ring=Point(5.0,-5.0)
        val curve=routedBackup(0,a,ring,b,10.0)
        assertEquals(listOf(a,ring,b),curve.points)
        assertEquals(arcLength(curve.points)-10.0,curve.lengthDeficitM,1e-12)
        assertTrue(curve.lengthDeficitM>4.0)
        assertEquals(listOf(a,b),slackLoop(a,b,10.0))
    }

    @Test fun editorPreviewIncludesRingAndRetainsMetreAndPercentExtras() {
        for(q in listOf(1.0,19.0,20.0,25.0,49.0)) {
            val d=Draft(kind=SetupKind.MIXED,span="50",leftHeight="1",rightHeight="3",position=q.toString(),sections=listOf(
                SectionDraft(length="20",extra="2",extraUnit=ExtraUnit.METRES),SectionDraft(length="30",extra="10")))
            val preview=previewGeometry(d)!!
            assertTrue(preview.backup.flatten().contains(preview.walker))
            preview.backup.forEachIndexed { i,p->
                val base=listOf(p.first())+listOf(preview.walker).filter{it.x>p.first().x && it.x<p.last().x}+p.last()
                assertEquals(arcLength(base)+if(i==0)2.0 else 3.0,arcLength(p),1e-8)
            }
        }
    }

    @Test fun fullSpanViewportIncludesRoutedLoopDepthAndUsesEqualAxes() {
        val s=standing(35.0);val drawing=s.backupDrawing()
        val points=drawing.points+s.network.splits.map{Point(s.r[2*it],s.r[2*it+1])}
        val view=Viewport.fit(points,400.0,220.0,48.0)
        points.forEach { val p=view.map(it);assertTrue(p.x in 47.999999..352.000001);assertTrue(p.y in 47.999999..172.000001) }
        assertEquals(view.scale,view.map(Point(1.0,0.0)).x-view.map(Point(0.0,0.0)).x,1e-12)
        assertEquals(view.scale,view.map(Point(0.0,0.0)).y-view.map(Point(0.0,1.0)).y,1e-12)
    }
}

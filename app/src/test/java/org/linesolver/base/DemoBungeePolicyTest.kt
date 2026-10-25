package org.linesolver.base

import org.junit.Assert.*
import org.junit.Test
import org.linesolver.base.ui.DemoBungeePolicy
import org.linesolver.base.model.*
import org.linesolver.base.material.catalogue.*

class DemoBungeePolicyTest {
    private fun layout(placement:BungeePlacement)=BungeeLayout(banks=listOf(BungeeBank("bank",placement,
        sectionId=if(placement==BungeePlacement.series_right)null else "s1",
        assemblies=listOf(InstalledBungee("cord",ModelReference("preserved-model","r3","original-fingerprint"),2.0,
            equalLegs=2,physicalCordCount=2,limiterPhysicalLengthM=4.0,source="Original source",uncertainty="Retain notes")))))
    @Test fun storedBungeesAreBlockedRegardlessOfVisibilityModeOrConnectionSetting() {
        assertFalse(DemoBungeePolicy.available)
        for(p in BungeePlacement.entries)for(visible in listOf(false,true))for(mode in Mode.entries)for(connections in listOf(false,true)) {
            val d=Draft(mode=mode,showBungeeOptions=visible,modelConnections=connections,bungees=layout(p))
            val before=d.copy()
            assertTrue(DemoBungeePolicy.blocks(d));assertEquals(before,d)
        }
    }
    @Test fun ordinaryAndEmptyLayoutsRemainAllowedWithoutResettingPreferences() {
        for(visible in listOf(false,true)) {
            assertFalse(DemoBungeePolicy.blocks(Draft(showBungeeOptions=visible)))
            assertFalse(DemoBungeePolicy.blocks(Draft(showBungeeOptions=visible,bungees=BungeeLayout())))
        }
        assertTrue(DemoBungeePolicy.allowsMaterialEdit(ProductCategory.webbing))
        assertFalse(DemoBungeePolicy.allowsMaterialEdit(ProductCategory.bungee))
    }
    @Test fun archivedRigRetainsExactBungeeInventoryAndRevisionAcrossPortableRoundtrip() {
        val d=Draft(kind=SetupKind.MIXED,span="20",sections=listOf(SectionDraft(length="20",id="s1")),
            bungees=layout(BungeePlacement.series_right),showBungeeOptions=true)
        val doc=RigLibraryDocument(rigs=listOf(SavedRig("saved","Legacy bungee rig",1,RigConfiguration.from(d))))
        val encoded=RigLibraryCodec.encode(doc)
        val decoded=RigLibraryCodec.decode(encoded)
        val restored=decoded.rigs.single().configuration.applyTo(d)
        assertTrue(DemoBungeePolicy.blocks(restored))
        assertEquals(d,restored);assertEquals(encoded,RigLibraryCodec.encode(decoded))
        assertEquals("r3",restored.bungees!!.banks.single().assemblies.single().model.revision)
        assertEquals("Retain notes",restored.bungees.banks.single().assemblies.single().uncertainty)
    }
}

package com.jasiri.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickCatalogTest {

    @Test
    fun `exactly twelve built-in presets with ids 1 to 12 in order`() {
        assertEquals(12, QuickCatalog.builtIn.size)
        assertEquals((1..12).toList(), QuickCatalog.builtIn.map { it.id })
    }

    @Test
    fun `ids are unique and built-in range, labels non-blank`() {
        val ids = QuickCatalog.builtIn.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        QuickCatalog.builtIn.forEach { preset ->
            assertTrue("id ${preset.id} out of 1..999", preset.id in 1..999)
            assertTrue("blank en for ${preset.id}", !preset.labels["en"].isNullOrBlank())
            assertTrue("blank sw for ${preset.id}", !preset.labels["sw"].isNullOrBlank())
        }
    }

    @Test
    fun `byId finds known ids and returns null for unknown`() {
        assertEquals("Need medical help", QuickCatalog.byId(3)?.en)
        assertNull(QuickCatalog.byId(4242))
    }

    @Test
    fun `labels are pinned`() {
        val expected = mapOf(
            1 to ("I'm OK" to "Niko salama"),
            2 to ("Need water" to "Nahitaji maji"),
            3 to ("Need medical help" to "Nahitaji msaada wa matibabu"),
            4 to ("Injured person here" to "Kuna majeruhi hapa"),
            5 to ("Need food" to "Nahitaji chakula"),
            6 to ("Tear gas here" to "Kuna gesi ya kutoa machozi hapa"),
            7 to ("Danger — avoid this area" to "Hatari — epuka eneo hili"),
            8 to ("Road blocked" to "Barabara imefungwa"),
            9 to ("Safe place here" to "Hapa ni mahali salama"),
            10 to ("I'm lost — need directions" to "Nimepotea — nahitaji mwelekeo"),
            11 to ("Phone battery low" to "Betri ya simu iko chini"),
            12 to ("On my way" to "Niko njiani")
        )
        assertEquals(expected.keys, QuickCatalog.builtIn.map { it.id }.toSet())
        expected.forEach { (id, labels) ->
            val preset = QuickCatalog.byId(id)
            assertEquals("en for $id", labels.first, preset?.labels?.get("en"))
            assertEquals("sw for $id", labels.second, preset?.labels?.get("sw"))
        }
    }

    @Test
    fun `every preset has a non-blank English label`() {
        QuickCatalog.builtIn.forEach { preset ->
            assertTrue("blank en for ${preset.id}", preset.en.isNotBlank())
        }
    }

    @Test
    fun `label returns the exact language or null`() {
        assertEquals("Nahitaji maji", QuickCatalog.label(2, "sw"))
        assertNull(QuickCatalog.label(2, "ha"))
        assertNull(QuickCatalog.label(4242, "en"))
    }

    @Test
    fun `complete languages are English and Swahili`() {
        assertEquals(setOf("en", "sw"), QuickCatalog.completeLanguages)
    }
}

package com.jasiri.quick

/** Drives tile colour: INFO grey/green, NEED amber, WARNING red. */
enum class QuickTone { INFO, NEED, WARNING }

/** [labels] is keyed by lowercase ISO 639-1 code ("en", "sw"); "en" is required. */
data class QuickPreset(
    val id: Int,
    val labels: Map<String, String>,
    val tone: QuickTone,
    val wantsLocation: Boolean
) {
    init {
        require(labels.containsKey("en")) { "preset $id has no English label" }
    }

    val en: String get() = labels.getValue("en")
}

/**
 * Built-in presets, format v1. See docs/JASIRI_QUICK_V1.md.
 *
 * Labels are data, not Android string resources, so every phone renders the same text whatever
 * its language. Ids are permanent: never renumber or reuse one. 1..999 are built-in;
 * 1000+ are reserved for mission packs.
 */
object QuickCatalog {

    /** Display order = list order. */
    val builtIn: List<QuickPreset> = listOf(
        preset(1, "I'm OK", "Niko salama", QuickTone.INFO, wantsLocation = false),
        preset(2, "Need water", "Nahitaji maji", QuickTone.NEED, wantsLocation = true),
        preset(3, "Need medical help", "Nahitaji msaada wa matibabu", QuickTone.NEED, wantsLocation = true),
        preset(4, "Injured person here", "Kuna majeruhi hapa", QuickTone.WARNING, wantsLocation = true),
        preset(5, "Need food", "Nahitaji chakula", QuickTone.NEED, wantsLocation = true),
        preset(6, "Tear gas here", "Kuna gesi ya kutoa machozi hapa", QuickTone.WARNING, wantsLocation = true),
        preset(7, "Danger — avoid this area", "Hatari — epuka eneo hili", QuickTone.WARNING, wantsLocation = true),
        preset(8, "Road blocked", "Barabara imefungwa", QuickTone.WARNING, wantsLocation = true),
        preset(9, "Safe place here", "Hapa ni mahali salama", QuickTone.INFO, wantsLocation = true),
        preset(10, "I'm lost — need directions", "Nimepotea — nahitaji mwelekeo", QuickTone.NEED, wantsLocation = true),
        preset(11, "Phone battery low", "Betri ya simu iko chini", QuickTone.INFO, wantsLocation = false),
        preset(12, "On my way", "Niko njiani", QuickTone.INFO, wantsLocation = false)
    )

    private val index: Map<Int, QuickPreset> = builtIn.associateBy { it.id }

    /** Languages that have a label for EVERY built-in preset (currently ["en","sw"]). */
    val completeLanguages: Set<String> =
        builtIn.map { it.labels.keys }.reduce { common, keys -> common intersect keys }

    /** Null for unknown ids. */
    fun byId(id: Int): QuickPreset? = index[id]

    /** Best label for a language: exact code, else null. Never falls back silently. */
    fun label(id: Int, lang: String): String? = index[id]?.labels?.get(lang)

    private fun preset(id: Int, en: String, sw: String, tone: QuickTone, wantsLocation: Boolean) =
        QuickPreset(id, mapOf("en" to en, "sw" to sw), tone, wantsLocation)
}

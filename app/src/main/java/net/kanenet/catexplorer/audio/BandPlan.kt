// Copyright (C) 2026 David Kane (W6MZT)
// SPDX-License-Identifier: GPL-3.0-or-later
package net.kanenet.catexplorer.audio

/**
 * US (FCC Part 97) amateur band edges and who may transmit where, for the four license classes. Used to draw band and
 * sub-band edges on the waterfall. Frequencies are in Hz. Novice privileges are left out.
 *
 * This is a guide only. Check the current FCC rules (or the ARRL band chart) before relying on it.
 */
object BandPlan {
    enum class License(val short: String, val label: String) {
        EXTRA("E", "Extra"), ADVANCED("A", "Advanced"), GENERAL("G", "General"), TECHNICIAN("T", "Technician"),
    }

    /** What a segment allows. PHONE also allows CW; ALL is every mode (CW, data, phone, image). */
    enum class Use(val label: String) { CW("CW only"), DATA("CW & data"), PHONE("Phone & CW"), ALL("All modes") }

    class Segment(val lo: Long, val hi: Long, val use: Use, val classes: Set<License>)

    class Band(val name: String, val lo: Long, val hi: Long, val segments: List<Segment>) {
        /** Where [license] may transmit inside this band, low to high. */
        fun privileges(license: License) = segments.filter { license in it.classes }.sortedBy { it.lo }
    }

    private val E = License.EXTRA; private val A = License.ADVANCED; private val G = License.GENERAL; private val T = License.TECHNICIAN
    private fun k(khz: Double) = Math.round(khz * 1000)
    private fun seg(lo: Double, hi: Double, use: Use, vararg c: License) = Segment(k(lo), k(hi), use, c.toSet())
    private fun band(name: String, vararg s: Segment) = Band(name, s.minOf { it.lo }, s.maxOf { it.hi }, s.toList())

    /** 60 m: five 2.8 kHz channels (centre frequencies; the USB dial is 1.5 kHz lower). */
    private val sixtyCentres = listOf(5332.0, 5348.0, 5358.5, 5373.0, 5405.0)

    val bands: List<Band> = listOf(
        band("160 m", seg(1800.0, 2000.0, Use.ALL, E, A, G)),
        band(
            "80 m",
            seg(3500.0, 3525.0, Use.DATA, E),
            seg(3525.0, 3600.0, Use.DATA, E, A, G),
            seg(3525.0, 3600.0, Use.CW, T),
            seg(3600.0, 3700.0, Use.PHONE, E),
            seg(3700.0, 3800.0, Use.PHONE, E, A),
            seg(3800.0, 4000.0, Use.PHONE, E, A, G),
        ),
        Band("60 m", k(5330.5), k(5406.4), sixtyCentres.map { seg(it - 1.4, it + 1.4, Use.ALL, E, A, G) }),
        band(
            "40 m",
            seg(7000.0, 7025.0, Use.DATA, E),
            seg(7025.0, 7125.0, Use.DATA, E, A, G),
            seg(7025.0, 7125.0, Use.CW, T),
            seg(7125.0, 7175.0, Use.PHONE, E, A),
            seg(7175.0, 7300.0, Use.PHONE, E, A, G),
        ),
        band("30 m", seg(10100.0, 10150.0, Use.DATA, E, A, G)),
        band(
            "20 m",
            seg(14000.0, 14025.0, Use.DATA, E),
            seg(14025.0, 14150.0, Use.DATA, E, A, G),
            seg(14150.0, 14175.0, Use.PHONE, E),
            seg(14175.0, 14225.0, Use.PHONE, E, A),
            seg(14225.0, 14350.0, Use.PHONE, E, A, G),
        ),
        band("17 m", seg(18068.0, 18110.0, Use.DATA, E, A, G), seg(18110.0, 18168.0, Use.PHONE, E, A, G)),
        band(
            "15 m",
            seg(21000.0, 21025.0, Use.DATA, E),
            seg(21025.0, 21200.0, Use.DATA, E, A, G),
            seg(21025.0, 21200.0, Use.CW, T),
            seg(21200.0, 21225.0, Use.PHONE, E),
            seg(21225.0, 21275.0, Use.PHONE, E, A),
            seg(21275.0, 21450.0, Use.PHONE, E, A, G),
        ),
        band("12 m", seg(24890.0, 24930.0, Use.DATA, E, A, G), seg(24930.0, 24990.0, Use.PHONE, E, A, G)),
        band(
            "10 m",
            seg(28000.0, 28300.0, Use.DATA, E, A, G, T),
            seg(28300.0, 28500.0, Use.PHONE, E, A, G, T),
            seg(28500.0, 29700.0, Use.PHONE, E, A, G),
        ),
        band("6 m", seg(50000.0, 50100.0, Use.CW, E, A, G, T), seg(50100.0, 54000.0, Use.ALL, E, A, G, T)),
        band("2 m", seg(144000.0, 144100.0, Use.CW, E, A, G, T), seg(144100.0, 148000.0, Use.ALL, E, A, G, T)),
        band("70 cm", seg(420000.0, 450000.0, Use.ALL, E, A, G, T)),
    )

    /** Choices for the license bars setting: "off", "all", or one class's short letter. */
    val barChoices = listOf("off" to "Off") + License.entries.reversed().map { it.short to it.label } + listOf("all" to "All four")

    /** The classes to draw bars for, Extra first, for a [barChoices] value. */
    fun barClasses(setting: String): List<License> = when (setting) {
        "off" -> emptyList()
        "all" -> License.entries
        else -> License.entries.filter { it.short == setting }.ifEmpty { License.entries }
    }

    /** The band containing [hz], or null when it's outside the ham bands. */
    fun bandAt(hz: Long): Band? = bands.firstOrNull { hz >= it.lo && hz <= it.hi }

    /** Bands that overlap [lo]..[hi]. */
    fun bandsIn(lo: Double, hi: Double) = bands.filter { it.hi >= lo && it.lo <= hi }

    /** What [license] may do at [hz] (the widest use if segments overlap), or null if it can't transmit there. */
    fun useAt(hz: Long, license: License): Use? =
        bandAt(hz)?.segments?.filter { license in it.classes && hz >= it.lo && hz <= it.hi }?.maxByOrNull { it.use.ordinal }?.use

    /** An edge on the waterfall: [bandEdge] is the end of a band; otherwise some class's privileges change here. */
    class Edge(val hz: Long, val bandEdge: Boolean)

    /** Every band and sub-band edge between [lo] and [hi]. */
    fun edgesIn(lo: Double, hi: Double): List<Edge> {
        val out = sortedMapOf<Long, Boolean>()
        for (b in bandsIn(lo, hi)) {
            out[b.lo] = true; out[b.hi] = true
            for (s in b.segments) {
                out.putIfAbsent(s.lo, false); out.putIfAbsent(s.hi, false)
            }
        }
        return out.filterKeys { it >= lo && it <= hi }.map { (hz, edge) -> Edge(hz, edge) }
    }
}

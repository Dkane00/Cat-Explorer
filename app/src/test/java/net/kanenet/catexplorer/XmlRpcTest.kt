// Copyright (C) 2026 David Kane (W6MZT)
// SPDX-License-Identifier: GPL-3.0-or-later
package net.kanenet.catexplorer

import net.kanenet.catexplorer.rig.FlrigClient
import net.kanenet.catexplorer.rig.DspState
import net.kanenet.catexplorer.rig.MicState
import net.kanenet.catexplorer.rig.RigState
import net.kanenet.catexplorer.rig.YaesuDsp
import net.kanenet.catexplorer.rig.RpcException
import net.kanenet.catexplorer.rig.XmlRpcClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class XmlRpcTest {
    private fun parse(s: String) = XmlRpcClient.parseResponse(s.toByteArray())

    @Test fun untypedValueIsString() {
        assertEquals("7110000", parse("<?xml version=\"1.0\"?><methodResponse><params><param>\n<value>7110000</value>\n</param></params></methodResponse>"))
    }

    @Test fun intAndNestedArray() {
        assertEquals(0, parse("<methodResponse><params><param><value><i4>0</i4></value></param></params></methodResponse>"))
        val r = parse("<methodResponse><params><param><value><array><data><value><array><data><value>Bandwidth</value><value>50</value></data></array></value></data></array></value></param></params></methodResponse>")
        assertEquals(listOf(listOf("Bandwidth", "50")), r)
    }

    @Test fun faultThrows() {
        val xml = "<methodResponse><fault><value><struct><member><name>faultCode</name><value><int>-1</int></value></member><member><name>faultString</name><value>boom</value></member></struct></value></fault></methodResponse>"
        try { parse(xml); fail() } catch (e: RpcException) { assertEquals("boom", e.message) }
    }

    @Test fun requestEncoding() {
        val x = XmlRpcClient.buildRequest("rig.set_frequency", listOf(7074000.0))
        assertTrue(x.contains("<methodName>rig.set_frequency</methodName>") && x.contains("<double>7074000.0</double>"))
    }

    @Test fun parseYaesuMeterReply() {
        assertEquals(128, FlrigClient.parseRm("RM4128;"))
        assertEquals(0, FlrigClient.parseRm("RM7000;"))
        assertNull(FlrigClient.parseRm("?;"))
        assertNull(FlrigClient.parseRm(RpcException("x")))
    }

    @Test fun dspParsingMatchesRealFt891Replies() {
        val r = listOf("NB01;", "NL0007;", "NR01;", "RL012;", "BC01;", "BP00001;", "BP01150;", "CO000001;",
            "CO010300;", "CO020001;", "GT05;", "PA01;", "RA01;", "NA01;", "IS01-0120;", "SH0117;", "KP40;", "CO030050;", "IF002007110000+000000C10000;", "LK1;")
        // same order as DSP_READS
        val d = YaesuDsp.parseDsp(r, DspState())
        assertTrue(d.nbOn && d.nrOn && d.autoNotch && d.notchOn && d.contourOn && d.apfOn)
        assertEquals(7, d.nbLevel); assertEquals(12, d.nrLevel)
        assertEquals(1500, d.notchHz); assertEquals(300, d.contourHz)
        assertEquals(4, d.agc)
        assertTrue(d.preampOn && d.attOn && d.narrow && d.ifShiftOn)
        assertEquals(-120, d.ifShift)
        assertEquals(17, d.widthIdx); assertEquals(1, d.widthP2); assertEquals(700, d.keyPitchHz); assertEquals(250, d.apfHz)
        assertTrue(d.memoryMode); assertTrue(d.lockOn)
    }

    @Test fun readbackCommands() {
        listOf("NB01;" to "NB0;", "NL0005;" to "NL0;", "RL003;" to "RL0;", "BP00001;" to "BP00;", "BP01150;" to "BP01;",
            "CO010300;" to "CO01;", "CO020001;" to "CO02;", "GT04;" to "GT0;", "IS01+0100;" to "IS0;", "MG075;" to "MG;",
            "PL010;" to "PL;", "PR11;" to "PR1;", "ML0001;" to "ML0;", "ML1030;" to "ML1;")
            .forEach { (set, read) -> assertEquals(set, read, YaesuDsp.readbackFor(set)) }
    }

    @Test fun widthTables() {
        // Real radio: DATA-USB, narrow off, SH index 17 -> 3000 Hz (matches FLRig's reading)
        val g = YaesuDsp.modeGroup("DATA-U")
        assertEquals(3000, YaesuDsp.widthTable(g, false)[17])
        assertEquals(2700, YaesuDsp.widthTable(YaesuDsp.modeGroup("USB"), false)[17])
        assertEquals(500, YaesuDsp.widthTable(YaesuDsp.modeGroup("CW-U"), true)[10])
        assertTrue(YaesuDsp.widthTable(YaesuDsp.modeGroup("FM"), false).isEmpty())
        assertEquals("SH0112;", YaesuDsp.width(1, 12)); assertEquals("CO030050;", YaesuDsp.apfFreq(250))
    }

    @Test fun micParsing() {
        val m = YaesuDsp.parseMic(listOf("MG050;", "PR01;", "PL004;", "PR11;", "ML0001;", "ML1030;"), MicState())
        assertEquals(50, m.micGain); assertTrue(m.procOn); assertEquals(4, m.procLevel)
        assertTrue(m.eqOn); assertTrue(m.monitorOn); assertEquals(30, m.monitorLevel)
    }

    @Test fun dspCommandStrings() {
        assertEquals("NB01;", YaesuDsp.nb(true)); assertEquals("NL0005;", YaesuDsp.nbLevel(5))
        assertEquals("RL003;", YaesuDsp.nrLevel(3))
        assertEquals("BP00001;", YaesuDsp.notch(true)); assertEquals("BP01150;", YaesuDsp.notchFreq(1500))
        assertEquals("CO010300;", YaesuDsp.contourFreq(300)); assertEquals("GT04;", YaesuDsp.agc(4))
        assertEquals("IS01+0100;", YaesuDsp.ifShift(true, 100)); assertEquals("IS00-0040;", YaesuDsp.ifShift(false, -40))
        assertEquals("MG075;", YaesuDsp.micGain(75)); assertEquals("PR11;", YaesuDsp.micEq(true))
        assertEquals("ML1030;", YaesuDsp.monitorLevel(30))
    }

    @Test fun memoryTagAndChannel() {
        val tagged = "MT005007110000+000000C10000" + "1" + "NET 40M     " + ";"
        assertEquals("NET 40M", YaesuDsp.parseMemTag(tagged, 5))
        assertEquals(null, YaesuDsp.parseMemTag(tagged, 6))
        assertEquals("", YaesuDsp.parseMemTag("MT002007110000+000000C00000" + "0" + " ".repeat(12) + ";", 2))
        val blank = List<Any?>(YaesuDsp.DSP_READS.size) { null }
        val d = YaesuDsp.parseDsp(blank.toMutableList().also { it[18] = "IF005007110000+000000C10000;" }, net.kanenet.catexplorer.rig.DspState())
        assertEquals(5, d.memChannel); assertEquals(7110000L, d.memFreqHz)
    }

    @Test fun frequencyEntry() {
        fun p(t: String) = net.kanenet.catexplorer.ui.parseFrequencyHz(t)
        assertEquals(7_074_000.0, p("7.074")!!, 0.5); assertEquals(7_074_000.0, p("7074")!!, 0.5)
        assertEquals(14_074_500.0, p("14074.5")!!, 0.5); assertEquals(14_074_000.0, p("14.074 MHz")!!, 0.5)
        assertEquals(50_313_000.0, p("50.313")!!, 0.5); assertEquals(530_000.0, p("530")!!, 0.5)
        assertEquals(7_110_000.0, p("7110k")!!, 0.5); assertEquals(7_110_000.0, p("7110000")!!, 0.5)
        assertEquals(7_110_000.0, p("7,11 m")!!, 0.5); assertEquals(7_200_100.0, p("7.200.1")!!, 0.5); assertEquals(7_200_100.0, p("7.200.100")!!, 0.5)
        assertEquals(14_074_500.0, p("14.074.500")!!, 0.5); assertEquals(7_200_150.0, p("7.200.15")!!, 0.5)
        assertEquals(7_200_100.0, p("7200.1")!!, 0.5); assertEquals(7_200_000.0, p("7.200")!!, 0.5); assertEquals(null, p("7..1"))
        assertEquals(null, p("abc")); assertEquals(null, p("7.1x"))
    }

    @Test fun vfoMemory() {
        assertEquals("LK1;", YaesuDsp.lock(true)); assertEquals("LK;", YaesuDsp.readbackFor("LK0;")); assertEquals("SV;", YaesuDsp.swapVfo()); assertEquals("IF;", YaesuDsp.readbackFor("SV;")); assertEquals("VM;", YaesuDsp.vfoMemory()); assertEquals("IF;", YaesuDsp.readbackFor("VM;"))
        val blank = List<Any?>(YaesuDsp.DSP_READS.size) { null }
        fun st(reply: String) = YaesuDsp.parseDsp(blank.toMutableList().also { it[18] = reply }, net.kanenet.catexplorer.rig.DspState())
        assertEquals(false, st("IF002007110000+000000C00000;").memoryMode)
        assertEquals(true, st("IF002007110000+000000C10000;").memoryMode)
    }

    @Test fun dspMenuCommands() {
        assertEquals("EX1202-15;", YaesuDsp.contourLevel(-15)); assertEquals("EX1202+05;", YaesuDsp.contourLevel(5))
        assertEquals("EX120310;", YaesuDsp.contourWidth(10)); assertEquals("EX12011;", YaesuDsp.apfWidth(1)); assertEquals("EX12040;", YaesuDsp.notchWidth(false))
        val m = net.kanenet.catexplorer.rig.DspMenu(-3, 4, 2, false)
        assertEquals(m, net.kanenet.catexplorer.rig.DspMenu.decode(m.encode()))
    }

    @Test fun eqCommandStrings() {
        assertEquals("EX151002;", YaesuDsp.eqFreq(true, 0, 2)); assertEquals("EX1511+05;", YaesuDsp.eqLevel(true, 0, 5))
        assertEquals("EX1514-20;", YaesuDsp.eqLevel(true, 1, -20)); assertEquals("EX150700;", YaesuDsp.eqFreq(false, 2, 0))
        assertEquals("EX151618;", YaesuDsp.eqFreq(true, 2, 18)); assertEquals("EX150310;", YaesuDsp.eqBw(false, 0, 10))
        assertEquals("EX1502+00;", YaesuDsp.eqLevel(false, 0, 0)); assertEquals("PR1;", YaesuDsp.readbackFor("EX151002;"))
        assertEquals(1500, YaesuDsp.eqFreqHz(1, 9)); assertEquals(3200, YaesuDsp.eqFreqHz(2, 18)); assertEquals(700, YaesuDsp.eqFreqHz(1, 1))
        assertEquals(9, YaesuDsp.eqIdxForHz(1, 5000f)); assertEquals(1, YaesuDsp.eqIdxForHz(2, 0f))
        val e = net.kanenet.catexplorer.rig.EqState().with(true, 1, net.kanenet.catexplorer.rig.EqBand(3, -4, 7))
        assertEquals(e, net.kanenet.catexplorer.rig.EqState.decode(e.encode()))
    }

    /** Read-only smoke test against a real FLRig. Run with CATEXPLORER_LIVE=127.0.0.1 */
    @Test fun liveFlrigReadOnly() {
        val host = System.getenv("CATEXPLORER_LIVE")
        assumeTrue(host != null)
        val c = FlrigClient(host!!, 12345)
        val info = c.readStatic()
        println("LIVE static: $info")
        val st = c.poll(RigState())
        println("LIVE poll: $st")
        assertTrue(st.connected && st.vfoAHz > 0 && info.xcvr.isNotEmpty())
    }
}

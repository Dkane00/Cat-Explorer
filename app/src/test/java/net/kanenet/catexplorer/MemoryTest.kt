// Copyright (C) 2026 David Kane (W6MZT)
// SPDX-License-Identifier: GPL-3.0-or-later
package net.kanenet.catexplorer

import net.kanenet.catexplorer.rig.YaesuDsp
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryTest {
    // replies copied from the radio (the channel number inside a reply is not the one asked for)
    private val replies = mapOf(
        1 to "MT002007238000+0000001000000            ;",
        10 to "MT002029680000+0000004020021N9UMJ BED   ;",
    )

    @Test fun writeReproducesWhatWasRead() {
        replies.forEach { (ch, reply) ->
            val m = YaesuDsp.parseMemChannel(reply, ch)!!
            assertEquals("MT%03d".format(ch) + reply.substring(5), YaesuDsp.memWrite(m))
        }
    }

    @Test fun renameChangesOnlyTheTag() {
        val m = YaesuDsp.parseMemChannel(replies.getValue(1), 1)!!
        assertEquals("MT001007238000+0000001000001TEST        ;", YaesuDsp.memWrite(m.copy(name = "TEST")))
    }

    @Test fun writeIs41Characters() {
        assertEquals(41, YaesuDsp.memWrite(YaesuDsp.parseMemChannel(replies.getValue(10), 10)!!).length)
    }
}

package com.tyranor.next.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCommandTemplateTest {
    @Test
    fun packageNamesNeedAtLeastTwoSegments() {
        assertTrue(RuntimeCommandTemplate.isValidPackageName("com.winlator"))
        assertTrue(RuntimeCommandTemplate.isValidPackageName("cyou.joiplay.runtime.renpy.v8d4d1"))
        assertFalse(RuntimeCommandTemplate.isValidPackageName("winlator"))
        assertFalse(RuntimeCommandTemplate.isValidPackageName("com..winlator"))
        assertFalse(RuntimeCommandTemplate.isValidPackageName("com.winlator;rm -rf /"))
    }

    @Test
    fun commandIsAppendedWhenTemplateHasNoPlaceholder() {
        assertEquals(
            "su -c 'am force-stop com.winlator'",
            RuntimeCommandTemplate.build("su -c", "am force-stop com.winlator"),
        )
    }

    @Test
    fun placeholderIsReplacedAndShellQuoted() {
        assertEquals(
            "sh -c 'am force-stop com.winlator; am force-stop cyou.joiplay.runtime.renpy.v8d4d1'",
            RuntimeCommandTemplate.build(
                "sh -c %s",
                "am force-stop com.winlator; am force-stop cyou.joiplay.runtime.renpy.v8d4d1",
            ),
        )
    }

    @Test
    fun singleQuoteIsEscapedForPosixShell() {
        assertEquals("'a'\\''b'", RuntimeCommandTemplate.shellQuote("a'b"))
    }
}

package com.example.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellPolicyTest {
    @Test fun readOnlyCommandsAreAllowed() {
        listOf("dumpsys meminfo", "dumpsys meminfo com.example.app", "ps -A", "top -b -n 1 -m 20",
            "cmd package list packages -3", "cat /proc/meminfo", "dumpsys activity top").forEach {
            assertTrue(it, ShellPolicy.isAllowed(it))
        }
    }

    @Test fun dangerousOrUnknownCommandsAreRejected() {
        listOf("rm -rf /sdcard", "pm disable com.x", "am force-stop com.x", "kill 123", "reboot",
            "dumpsys meminfo; reboot", "ps -A | grep x", "cat /proc/meminfo > /sdcard/a", "dumpsys meminfo `id`",
            "dumpsys meminfo \$(id)", "", "settings put global x 1").forEach {
            assertFalse(it, ShellPolicy.isAllowed(it))
        }
    }
}

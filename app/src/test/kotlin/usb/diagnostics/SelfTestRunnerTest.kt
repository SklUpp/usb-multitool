package usb.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

class SelfTestRunnerTest {
    @Test
    fun selfTestRunsAllSuitesAndPasses() {
        val report = SelfTestRunner.runAll(disk = null)
        assertTrue(report.summary(), report.allPassed)
        assertTrue(report.results.size >= 7)
    }
}

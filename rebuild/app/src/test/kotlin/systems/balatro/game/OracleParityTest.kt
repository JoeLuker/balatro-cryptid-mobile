package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wires the score oracle (~99 LÖVE-recorded baseline hands in Oracle.kt) into the Gradle suite.
 * Previously the oracle ran only via test/kt-oracle.sh (standalone kotlinc), so a green
 * `./gradlew test` said nothing about scoring parity and vice versa (docs/REVIEW-2026-07-01.md §2).
 * kt-oracle.sh remains as the Gradle-free fast path; both call the same Oracle object.
 */
class OracleParityTest {
    @Test fun allBaselineCasesPass() {
        val (pass, total) = Oracle.run()
        assertEquals("score-oracle baselines: $pass/$total passed", total, pass)
    }

    @Test fun multiCallCasesPass() {
        val (pass, total) = Oracle.runMultiCall()
        assertEquals("score-oracle multi-call cases: $pass/$total passed", total, pass)
    }
}

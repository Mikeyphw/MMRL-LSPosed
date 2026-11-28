package io.github.mikeyphw.mmrl.lsposed

import org.junit.Test

class DurableTransferTest {
    @Test fun concurrencyAndDeduplication() = DurableTransferCases.concurrencyAndDeduplication()
    @Test fun retryAndCancellation() = DurableTransferCases.retryAndCancellation()
    @Test fun recoveryAndLock() = DurableTransferCases.recoveryAndLock()
    @Test fun persistenceFailureAndCorruption() = DurableTransferCases.persistenceFailureAndCorruption()
    @Test fun exactResumeAndDigest() = DurableTransferCases.exactResumeAndDigest()
    @Test fun wrongRangesAndRestart() = DurableTransferCases.wrongRangesAndRestart()
    @Test fun weakIdentityAndExplicitCancellation() = DurableTransferCases.weakIdentityAndExplicitCancellation()
}

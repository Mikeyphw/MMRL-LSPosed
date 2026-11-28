package io.github.mikeyphw.mmrl.lsposed

import org.junit.Test

class TransferPublicationTest {
    @Test fun boundedCopy() = TransferPublicationCases.boundedCopy()
    @Test fun explicitCancelAndTimeout() = TransferPublicationCases.explicitCancelAndTimeout()
    @Test fun publicationAndReuse() = TransferPublicationCases.publicationAndReuse()
    @Test fun mismatchesNeverReplace() = TransferPublicationCases.mismatchesNeverReplace()
    @Test fun receiptCollisionAndOrphanRecovery() = TransferPublicationCases.receiptCollisionAndOrphanRecovery()
    @Test fun symlinkAndForeignStage() = TransferPublicationCases.symlinkAndForeignStage()
    @Test fun concurrentDifferentWriters() = TransferPublicationCases.concurrentDifferentWriters()
}

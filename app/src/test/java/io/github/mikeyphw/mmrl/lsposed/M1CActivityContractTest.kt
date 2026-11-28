package io.github.mikeyphw.mmrl.lsposed

import io.github.mikeyphw.mmrl.shared.OperationEvent
import io.github.mikeyphw.mmrl.shared.OperationId
import io.github.mikeyphw.mmrl.shared.OperationKind
import io.github.mikeyphw.mmrl.shared.OperationOrigin
import io.github.mikeyphw.mmrl.shared.OperationPhase
import io.github.mikeyphw.mmrl.shared.OperationRecord
import io.github.mikeyphw.mmrl.shared.OperationStatus
import org.junit.Assert.*
import org.junit.Test

class M1CActivityContractTest {
    @Test fun pendingWriteCannotResumeWithoutReview() {
        val id = OperationId.parse("vector:pending-id")!!
        val unknown = OperationRecord.restore(id, OperationKind.SCOPE_CHANGE,
            OperationOrigin.USER, OperationStatus.RECONCILIATION_REQUIRED,
            OperationPhase.WAITING_FOR_USER, 10L, 11L)!!
        assertNull(unknown.reduce(OperationEvent.of(id, OperationStatus.RECONCILIATION_REQUIRED,
            OperationStatus.SUCCEEDED, OperationPhase.FINISHED, 12L)!!))
        assertNull(unknown.reduce(OperationEvent.of(id, OperationStatus.RECONCILIATION_REQUIRED,
            OperationStatus.RUNNING, OperationPhase.APPLYING, 12L,
            reconciliationReviewed = true)!!))
        val interrupted = unknown.reduce(OperationEvent.of(id,
            OperationStatus.RECONCILIATION_REQUIRED, OperationStatus.INTERRUPTED,
            OperationPhase.FINISHED, 13L, reconciliationReviewed = true)!!)!!
        assertTrue(interrupted.status.terminal)
    }
}

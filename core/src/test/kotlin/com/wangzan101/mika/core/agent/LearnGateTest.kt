package com.wangzan101.mika.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnGateTest {
    private val unreviewed = TaskState.Succeeded("ok", verified = false, reviewed = false)

    @Test fun doneWithoutReviewCounts() {
        assertTrue(LearnGate.done(unreviewed, RunOutcome.Done))
        assertTrue(LearnGate.done(TaskState.Succeeded("ok", verified = true, reviewed = true), RunOutcome.Done))
    }

    @Test fun reviewedButNotVerifiedIsBlocked() {
        assertFalse(LearnGate.done(TaskState.Succeeded("ok", verified = false, reviewed = true, note = "x"), RunOutcome.Done))
        assertFalse(LearnGate.done(TaskState.Succeeded("ok", verified = false, unconfirmed = listOf("3 元"), reviewed = false), RunOutcome.Done))
    }

    @Test fun onlyDoneCounts() {
        for (o in listOf(RunOutcome.Partial, RunOutcome.NotPossible, RunOutcome.Unknown)) assertFalse(o.wire, LearnGate.done(unreviewed, o))
        assertFalse(LearnGate.done(TaskState.Failed("x", FailKind.GaveUp), RunOutcome.Done))
        assertFalse(LearnGate.done(TaskState.Cancelled, RunOutcome.Done))
    }
}

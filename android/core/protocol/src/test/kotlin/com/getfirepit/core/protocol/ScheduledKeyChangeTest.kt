package com.getfirepit.core.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledKeyChangeTest {
    @Test fun makerConnectedDueWithEvidenceChanges() {
        assertTrue(
            ScheduledKeyChange.shouldChange(
                isMaker = true,
                setting = RoomKeyChange.DAILY,
                keyAgeMillis = ScheduledKeyChange.DAY_MILLIS,
                hasCurrentGenerationEvidence = true,
                connected = true,
                alreadyRotating = false,
            ),
        )
    }

    @Test fun blockersPreventChanging() {
        val due = ScheduledKeyChange.DAY_MILLIS
        assertFalse(ScheduledKeyChange.shouldChange(false, RoomKeyChange.DAILY, due, true, true, false))
        assertFalse(ScheduledKeyChange.shouldChange(true, RoomKeyChange.NEVER, due, true, true, false))
        assertFalse(ScheduledKeyChange.shouldChange(true, RoomKeyChange.DAILY, due - 1, true, true, false))
        assertFalse(ScheduledKeyChange.shouldChange(true, RoomKeyChange.DAILY, due, false, true, false))
        assertFalse(ScheduledKeyChange.shouldChange(true, RoomKeyChange.DAILY, due, true, false, false))
        assertFalse(ScheduledKeyChange.shouldChange(true, RoomKeyChange.DAILY, due, true, true, true))
    }

    @Test fun weeklyNeedsAWeek() {
        assertFalse(
            ScheduledKeyChange.shouldChange(true, RoomKeyChange.WEEKLY, ScheduledKeyChange.WEEK_MILLIS - 1, true, true, false),
        )
        assertTrue(
            ScheduledKeyChange.shouldChange(true, RoomKeyChange.WEEKLY, ScheduledKeyChange.WEEK_MILLIS, true, true, false),
        )
    }
}

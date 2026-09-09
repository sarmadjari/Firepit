package com.getfirepit.core.protocol.phoneapi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/** Runs the session on the test scheduler and lets each test cancel it. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.launchSession(session: PhoneApiSession): Job {
    val job = launch { session.run() }
    runCurrent()
    return job
}

/** Suspends until the config download finishes. */
internal suspend fun PhoneApiSession.awaitReady(): SessionState.Ready =
    state.first { it is SessionState.Ready } as SessionState.Ready

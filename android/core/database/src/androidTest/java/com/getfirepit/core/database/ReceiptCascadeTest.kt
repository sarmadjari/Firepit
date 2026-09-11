package com.getfirepit.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.getfirepit.core.model.MessageStatus
import com.getfirepit.core.model.ReceiptState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Receipts are only trustworthy if they cannot outlive the message they
 * describe, and that promise is made by the database rather than by any caller
 * remembering to keep it.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptCascadeTest {

    private lateinit var db: FirepitDatabase
    private lateinit var messages: MessageDao
    private lateinit var receipts: ReceiptDao

    private val message = 1001
    private val sender = 77

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FirepitDatabase::class.java,
        ).build()
        messages = db.messageDao()
        receipts = db.receiptDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun deletingAMessageTakesItsReceipts() = runBlocking {
        saveMessage(message, sentAt = 1_000)
        receipts.recordReceived(message, nodeNum = 5, at = 1_100)
        receipts.recordRead(message, nodeNum = 6, at = 1_200)
        assertEquals(2, receipts.observeFor(message).first().size)

        messages.deleteOlderThan(cutoff = 2_000)

        assertTrue(receipts.observeFor(message).first().isEmpty())
    }

    @Test
    fun leavingARoomTakesTheReceiptsWithTheMessages() = runBlocking {
        saveMessage(message, sentAt = 1_000, channel = 3)
        receipts.recordRead(message, nodeNum = 5, at = 1_100)

        messages.deleteChannel(channel = 3, broadcast = -1)

        assertTrue(receipts.observeFor(message).first().isEmpty())
    }

    @Test
    fun aReadReceiptIsNotWalkedBackByARepeatedDeliveryOne() = runBlocking {
        saveMessage(message, sentAt = 1_000)

        receipts.recordRead(message, nodeNum = 5, at = 1_200)
        receipts.recordReceived(message, nodeNum = 5, at = 1_300)

        val only = receipts.observeFor(message).first().single()
        assertEquals(ReceiptState.READ, only.state)
        assertEquals(1_200L, only.at)
    }

    @Test
    fun receivingThenReadingLeavesOneRowThatSaysRead() = runBlocking {
        saveMessage(message, sentAt = 1_000)

        receipts.recordReceived(message, nodeNum = 5, at = 1_100)
        receipts.recordRead(message, nodeNum = 5, at = 1_200)

        val only = receipts.observeFor(message).first().single()
        assertEquals(ReceiptState.READ, only.state)
        assertEquals(1_200L, only.at)
    }

    @Test
    fun aReceiptForAMessageWeNeverHadIsDroppedRatherThanFailing() = runBlocking {
        receipts.recordReceived(messageId = 9999, nodeNum = 5, at = 1_100)
        receipts.recordRead(messageId = 9999, nodeNum = 5, at = 1_100)

        assertTrue(receipts.observeFor(9999).first().isEmpty())
    }

    @Test
    fun eachPersonGetsOneRowPerMessage() = runBlocking {
        saveMessage(message, sentAt = 1_000)

        receipts.recordReceived(message, nodeNum = 5, at = 1_100)
        receipts.recordReceived(message, nodeNum = 5, at = 1_150)
        receipts.recordReceived(message, nodeNum = 6, at = 1_160)

        assertEquals(2, receipts.observeFor(message).first().size)
    }

    private suspend fun saveMessage(id: Int, sentAt: Long, channel: Int = 0) {
        messages.upsert(
            MessageEntity(
                id = id,
                channel = channel,
                fromNodeNum = sender,
                toNodeNum = -1,
                peerNodeNum = -1,
                text = "hello",
                sentAt = sentAt,
                rxTime = null,
                status = MessageStatus.QUEUED,
                failureReason = null,
                isOutgoing = true,
                replyId = null,
                rxSnr = null,
                rxRssi = null,
                hopsAway = null,
                emoji = null,
                signed = false,
            ),
        )
    }
}

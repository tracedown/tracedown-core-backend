package dev.tracedown.notifications.consumers

import dev.tracedown.common.email.EmailStatusEvent
import dev.tracedown.common.models.NotificationLog
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

/**
 * Consumes email delivery-status events published by the email-service.
 *
 * Runs a coroutine loop that BRPOP's from `email_status_queue` and
 * transitions the matching notification_log row from `queued` to the
 * actual send outcome (`sent` / `failed`).
 *
 * Needs a dedicated Redis connection: BRPOP blocks the connection for up
 * to [popTimeoutSeconds], which would stall concurrent publishes if shared.
 */
class EmailStatusConsumer(
    private val redis: RedisCommands<String, String>,
    private val popTimeoutSeconds: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private var job: Job? = null

    /** Starts the consumer loop in the given coroutine scope. */
    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            log.info("email status consumer started, BRPOP timeout={}s", popTimeoutSeconds)
            while (isActive) {
                try {
                    consumeOne()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("email status consumer error: {}", e.message, e)
                    delay(1000)
                }
            }
        }
    }

    /** Stops the consumer loop. */
    fun stop() {
        job?.cancel()
    }

    private suspend fun consumeOne() {
        val result = redis.brpop(popTimeoutSeconds.toDouble(), EmailStatusEvent.QUEUE_KEY)
            ?: return // timeout, no message

        val event = try {
            EmailStatusEvent.parse(Json.parseToJsonElement(result.value).jsonObject)
        } catch (e: Exception) {
            null
        }
        if (event == null) {
            log.error("malformed email status event, dropping: {}", result.value)
            return
        }

        val updated = newSuspendedTransaction(Dispatchers.IO) {
            NotificationLog.update({ NotificationLog.id eq event.notificationLogId }) {
                it[status] = event.status
                it[error] = event.error
            }
        }
        if (updated == 0) {
            log.warn("no notification_log row {} for email status event", event.notificationLogId)
        } else {
            log.debug("notification {} marked {}", event.notificationLogId, event.status)
        }
    }
}

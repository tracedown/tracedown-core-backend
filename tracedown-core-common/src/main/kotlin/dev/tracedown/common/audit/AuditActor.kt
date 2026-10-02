package dev.tracedown.common.audit

import kotlinx.coroutines.asContextElement
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * The credential an audited action came through, when it was not a signed-in
 * session.
 *
 * [AuditService.log] is handed the user who acted; it is not handed how they
 * authenticated, and threading that through every controller signature would
 * touch all of them to carry a value only one function reads. So the entry
 * point that authenticates an API key states it once, here, and the audit
 * writer picks it up.
 *
 * The value lives in a thread-local, but it is only ever set through
 * [asContextElement]: a coroutine context element that installs it on whichever
 * thread the request resumes on and removes it when the request suspends. A
 * bare thread-local would not do — request threads are pooled, and a value left
 * behind, or picked up after a resume on another thread, would attribute one
 * request's action to another request's key.
 */
object AuditActor {

    private val apiKeyId = ThreadLocal<UUID?>()

    /** The API key the current request authenticated with, or null for a session or a system action. */
    fun currentApiKeyId(): UUID? = apiKeyId.get()

    /**
     * A context element that makes [keyId] the current key for as long as a
     * coroutine runs under it — or, given null, that makes sure there is none.
     * Every request runs under one or the other, so a request resumed inline
     * on a thread another request is using restores its own answer.
     */
    fun asContextElement(keyId: UUID?): CoroutineContext.Element = apiKeyId.asContextElement(keyId)
}

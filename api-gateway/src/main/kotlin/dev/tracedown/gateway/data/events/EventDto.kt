package dev.tracedown.gateway.data.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** What an event is about. */
@Serializable
data class EventResource(
    /** `workspace`, `project`, `service`, `variable` or `alert`. */
    val type: String,
    val id: String,
)

/** One event of the feed. */
@Serializable
data class FeedEvent(
    /** Unique per event; the same event read twice has the same id. */
    val id: String,
    /** One of [dev.tracedown.gateway.controllers.events.EventTypes.ALL]. */
    val type: String,
    /**
     * When it happened, ISO-8601: for a result, when its run started; for an
     * alert, when its episode began; for a change, when it was made.
     */
    val occurredAt: String,
    val resource: EventResource,
    /**
     * What the type carries — exactly the fields
     * [dev.tracedown.gateway.controllers.events.EventTypes.DATA] lists for it;
     * never a variable's value or any other secret.
     */
    val data: JsonObject,
)

/** A read of the feed: the events after the cursor, and the cursor to read on from. */
@Serializable
data class EventPage(
    val items: List<FeedEvent>,
    /** Pass as `after` to read on. Moves even when [items] is empty. */
    val next: String,
    /** True when more may be there already: read on from [next] at once rather than waiting. */
    val more: Boolean = false,
)

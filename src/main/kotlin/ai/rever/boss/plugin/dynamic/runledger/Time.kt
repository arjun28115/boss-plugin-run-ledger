package ai.rever.boss.plugin.dynamic.runledger

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val UTC_SECONDS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

/** Current UTC instant, second precision, always with a trailing Z. */
fun nowUtc(): String = UTC_SECONDS.format(Instant.now())

/**
 * Milliseconds since epoch for a timestamp this plugin wrote, or null if the ledger line was
 * hand-edited into something unparseable. Returning null rather than throwing keeps one bad
 * timestamp from taking down the whole panel.
 */
fun parseInstantMillis(text: String): Long? =
    runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()

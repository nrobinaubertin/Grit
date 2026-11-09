/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 *
 * Copied from DAVx5 (https://github.com/bitfireAT/davx5-ose, synctools module), GPLv3.
 * Changes for Grit: package name, and removal of the ZoneIdPool garbage collection workaround,
 * because it loads java.time.zone.ZoneRulesProvider, which Android hides without desugaring.
 */

package com.shub39.grit.caldav.davx5

import java.time.DateTimeException
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneRules
import java.util.logging.Logger
import net.fortuna.ical4j.model.DefaultTimeZoneRegistryFactory
import net.fortuna.ical4j.model.TimeZone
import net.fortuna.ical4j.model.TimeZoneRegistry
import net.fortuna.ical4j.model.TimeZoneRegistryFactory
import net.fortuna.ical4j.model.component.Observance

/**
 * A [TimeZoneRegistry] that uses system timezone IDs for all TZIDs recognised by [ZoneId.of],
 * ignoring any VTIMEZONE component provided alongside such a TZID.
 *
 * Also contains a workaround for https://github.com/bitfireAT/davx5-ose/issues/2248 (NPE when
 * receiving a calendar event with an invalid VTIMEZONE).
 */
class SystemAwareTimeZoneRegistry(
    /** Underlying registry that handles storage and custom-VTIMEZONE resolution. */
    private val timeZoneRegistry: TimeZoneRegistry =
        DefaultTimeZoneRegistryFactory().createRegistry()
) : TimeZoneRegistry by timeZoneRegistry {

    private val logger: Logger
        get() = Logger.getLogger(javaClass.name)

    /** Delegates to our [register] (not the one of [timeZoneRegistry]). */
    override fun register(timeZone: TimeZone) {
        register(timeZone, update = false)
    }

    /**
     * Registers [timeZone] in the underlying registry, with the following exceptions:
     * - If the TZID is known to the system (like `Europe/Berlin`), registration is skipped so
     *   that [getZoneId] returns the system [ZoneId] directly.
     * - If the VTIMEZONE has no STANDARD or DAYLIGHT observances, it is invalid and ignored.
     */
    override fun register(timeZone: TimeZone, update: Boolean) {
        if (ZoneId.getAvailableZoneIds().contains(timeZone.id)) {
            logger.fine("Skipping VTIMEZONE registration for system-known TZID: ${timeZone.id}")
            return
        }

        if (isEmptyTimeZone(timeZone)) {
            logger.warning("Ignoring invalid VTIMEZONE with TZID: ${timeZone.id}")
            return
        }

        timeZoneRegistry.register(timeZone, update)
    }

    /**
     * Required hack: returns a non-empty zone-rules map so that
     * [net.fortuna.ical4j.model.parameter.TzId.toZoneId] always calls [getZoneId] (overridden
     * below) rather than falling through to [TimeZoneRegistry.getGlobalZoneId], which would
     * return `ical4j~UUID` zones.
     */
    override fun getZoneRules(): Map<String, ZoneRules> =
        timeZoneRegistry.zoneRules.takeIf { it.isNotEmpty() } ?: NON_EMPTY_ZONE_RULES

    /**
     * Returns the system [ZoneId] directly for TZIDs recognised by [ZoneId.of], instead of
     * `ical4j-local-*` or `ical4j~UUID` zones. Falls back to the underlying registry for custom
     * TZIDs.
     */
    override fun getZoneId(tzId: String): ZoneId =
        try {
            ZoneId.of(tzId)
        } catch (_: DateTimeException) {
            timeZoneRegistry.getZoneId(tzId)
        }

    private fun isEmptyTimeZone(timeZone: TimeZone): Boolean {
        val vTimeZone = timeZone.vTimeZone
        val standardObservances = vTimeZone.getComponents<Observance>(Observance.STANDARD)
        val daylightObservances = vTimeZone.getComponents<Observance>(Observance.DAYLIGHT)
        return standardObservances.isEmpty() && daylightObservances.isEmpty()
    }

    private companion object {
        val NON_EMPTY_ZONE_RULES: Map<String, ZoneRules> =
            mapOf("_ical4j_zone_" to ZoneOffset.UTC.rules)
    }

    /* referenced in ical4j.properties */
    class Factory : TimeZoneRegistryFactory() {
        override fun createRegistry() = SystemAwareTimeZoneRegistry()
    }
}

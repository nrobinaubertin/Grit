# R8 rules for :shared:caldav and its libraries. R8 applies rules embedded in jars automatically.
# Based on DAVx5 synctools/consumer-rules.pro (GPLv3).

# keep all iCalendar properties/parameters - https://github.com/ical4j/ical4j/issues/717
-keep,allowoptimization class net.fortuna.ical4j.** { *; }

# loaded dynamically from ical4j.properties
-keep,allowoptimization class com.shub39.grit.caldav.davx5.SystemAwareTimeZoneRegistry$Factory { *; }

# ical4j: optional dependencies that are not packaged
-dontwarn com.github.benmanes.caffeine.**
-dontwarn com.github.erosb.jsonsKema.**
-dontwarn groovy.**
-dontwarn java.beans.Transient
-dontwarn java.time.zone.ZoneRulesProvider
-dontwarn javax.cache.**
-dontwarn org.codehaus.groovy.**
-dontwarn org.joda.convert.ToString
-dontwarn org.jparsec.**

# dav4jvm and Ktor
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn java.lang.management.**

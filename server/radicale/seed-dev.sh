#!/bin/sh
# Creates the test calendars and shares on a fresh development server.
#
#   alice/personal  alice's calendar, shared read-only to bob as bob/alice-personal
#   alice/family    shared to bob with write access as bob/family
#   bob/work        bob's own calendar
set -eu

base=${1:-http://127.0.0.1:5232}

mkcalendar() { # user name color
    curl -sf -u "$1:$1" -X MKCALENDAR "$base/$1/$2/" \
        -H "Content-Type: application/xml; charset=utf-8" --data-binary @- >/dev/null <<XML
<?xml version="1.0" encoding="utf-8"?>
<C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:I="http://apple.com/ns/ical/">
  <D:set><D:prop>
    <D:displayname>$2</D:displayname>
    <I:calendar-color>$3</I:calendar-color>
    <C:supported-calendar-component-set><C:comp name="VEVENT"/></C:supported-calendar-component-set>
  </D:prop></D:set>
</C:mkcalendar>
XML
}

share() { # owner path_for_user mapped user permissions
    curl -sf -u "$1:$1" -H "Content-Type: application/json" \
        -d "{\"PathOrToken\": \"$2\", \"PathMapped\": \"$3\", \"User\": \"$4\", \"Permissions\": \"$5\", \"Enabled\": true, \"Hidden\": false}" \
        "$base/.sharing/v1/map/create" >/dev/null
    for hook in enable unhide; do
        curl -sf -u "$4:$4" -H "Content-Type: application/json" \
            -d "{\"PathOrToken\": \"$2\"}" "$base/.sharing/v1/map/$hook" >/dev/null
    done
}

event() { # user calendar uid summary dtstart dtend [rrule]
    {
        printf 'BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Grit//seed//EN\r\nBEGIN:VEVENT\r\n'
        printf 'UID:%s\r\nDTSTAMP:20260101T000000Z\r\nSUMMARY:%s\r\n' "$3" "$4"
        printf 'DTSTART;TZID=Europe/Paris:%s\r\nDTEND;TZID=Europe/Paris:%s\r\n' "$5" "$6"
        if [ -n "${7:-}" ]; then printf 'RRULE:%s\r\n' "$7"; fi
        printf 'END:VEVENT\r\nEND:VCALENDAR\r\n'
    } | curl -sf -u "$1:$1" -X PUT "$base/$1/$2/$3.ics" \
        -H "Content-Type: text/calendar; charset=utf-8" -H "If-None-Match: *" \
        --data-binary @- >/dev/null
}

# Create the principal collections by touching them once
for user in alice bob; do
    curl -sf -u "$user:$user" -X PROPFIND -H "Depth: 0" "$base/$user/" >/dev/null
done

mkcalendar alice personal "#3F51B5"
mkcalendar alice family "#E91E63"
mkcalendar bob work "#009688"

share alice /bob/alice-personal/ /alice/personal/ bob r
share alice /bob/family/ /alice/family/ bob rw

today=$(date +%Y%m%d)
event alice personal dentist "Dentist" "${today}T090000" "${today}T100000"
event alice family groceries "Groceries" "${today}T180000" "${today}T190000" "FREQ=WEEKLY;COUNT=10"
event bob work standup "Standup" "${today}T093000" "${today}T094500" "FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR"

echo "Seeded alice/personal, alice/family (shared to bob), bob/work"

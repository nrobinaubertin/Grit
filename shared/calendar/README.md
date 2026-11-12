# Calendar tab (fork feature)

This fork of Grit adds a Calendar tab that syncs with a CalDAV server, for example Radicale. Two people can share calendars without another sync app such as DAVx5.

## What it does

- It shows a month grid with the events of the selected day, a week view with hours, and an agenda list.
- It keeps calendars on the device only, without a server. These calendars never sync. A hidden local account holds them, so they use the same storage, editor and reminders as synced calendars.
- It creates, edits and deletes events on calendars that you can write to. A repeating event is edited as a whole series. One occurrence can be deleted on its own.
- It shows read-only calendars without edit actions. If the server does not give the `DAV:write-content` privilege, the calendar is read-only.
- It moves a calendar between this device and the server:
  - "Start syncing" on a calendar of the device puts it on the server, with the same name and color, and uploads its events. If the calendar was on the server before and that server calendar still exists, Grit reconnects to it: events changed on the device upload, and the others take the server version.
  - "Stop syncing" on a server calendar keeps its events on this device only. If the calendar is yours alone, Grit deletes it from the server, and your other devices lose it. Grit asks the Radicale sharing API: a calendar that you shared, or that someone shared with you, stays on the server. If Grit cannot tell, for example with another server or without a connection, the calendar also stays. Grit then leaves the kept calendar out of later syncs.
- It imports `.ics` files, for example the export of another calendar app, into a calendar that you choose. A series stays together with its changed occurrences. An event that is already in the calendar (same UID) is updated, so a second import of the same file adds nothing.
- It shows reminders as Android notifications. The Grit setting that pauses notifications also pauses calendar reminders.
- It syncs at these times: every 30 minutes, 5 seconds after a local change, at the opening of the tab, and on pull-to-refresh.
- On Android, it imports the calendars of other apps, for example Google Calendar or DAVx5. These apps keep their calendars in the Android calendar provider (the shared calendar storage of the phone). Choose a calendar under "Calendars from other apps" on the "Calendars and accounts" screen, then a new calendar on this device or one of your calendars as the target. The events become normal Grit events, with their changed occurrences and their reminders. Grit does not see later changes in the other app: import the calendar again to update it.

The desktop app syncs while it runs and has no reminders, like the upstream desktop app.

## Modules

| Module | Content |
|---|---|
| `:shared:caldav` | Plain JVM module. CalDAV client (dav4jvm over Ktor), iCalendar layer (ical4j), and the generic sync engine. |
| `:shared:calendar` | Kotlin Multiplatform module (Android and desktop). Room database, sync and reminder scheduling, screens. |

The sync engine works through the `LocalCollection` interface. A later task-list sync (VTODO) can use the same engine with its own `LocalCollection`.

Library versions are in `gradle/calendar.versions.toml`, so that `gradle/libs.versions.toml` stays as upstream has it.

## Changes to upstream files

The fork first kept edits to upstream files small, so that upstream changes merged easily. Since version 7.5.0, the fork no longer aims at compatibility with upstream Grit and changes its code where that is simpler. The list below shows the extension points of that first period:

- `settings.gradle.kts`: module includes, the second version catalog, and JitPack for dav4jvm only.
- `androidApp/build.gradle.kts` and `desktopApp/build.gradle.kts`: one dependency line each.
- Both `GritModules.kt` files: `CalendarModules` in the Koin `includes`.
- `shared/ui/.../navigation/`: the new file `ExtensionRoutes.kt`, plus a few lines in `NavigationConfig.kt`, `NavBarAndRail.kt` and `GritNavDisplay.kt`.
- `shared/core/.../settings/backup/`: the new file `BackupContributor.kt`.
- `shared/core/.../settings/backup/ExportRepo.kt`: `exportJsonText()`, the backup as text without a save dialog.
- `shared/logic/.../core/backup/`: the `extensions` field in `ExportSchema.kt`, `exportJsonText()` in `ExportImpl.kt`, and a few lines in `RestoreImpl.kt`.
- `shared/ui/.../setting/ui/section/BackupPage.kt`: a few lines that show the sections of `BackupPageContributor` (in `ExtensionRoutes.kt`).
- `shared/core/.../settings/Sections.kt` and `GritNavDisplay.kt`: the `Calendar` starting page.

The calendar module adds its screens through `NavEntryContributor`, which Koin provides to `GritNavDisplay`. It adds its part of the backup file through `BackupContributor`, and the automatic backups (package `autobackup`) to the Backup page through `BackupPageContributor`. Its Android manifest adds the INTERNET permission, the receivers and a network security configuration. The manifest merger adds these to both app flavors.

## Data and privacy

- On Android, the calendar database is in `noBackupFilesDir`. Calendars are downloaded again after a restore.
- The account password is encrypted with an Android Keystore key. On desktop, it is in a file that only the user can read.
- Every event keeps its full iCalendar text. Edits change only the fields that Grit shows, so other clients lose no data.
- The Grit backup file also contains the calendars:
  - the categories with their colors
  - the calendars on this device, with the full iCalendar text of every event
  - the CalDAV account, and the switches of its calendars

  The password is not in the file. After a restore, enter it again on the "Calendars and accounts" screen, and the server calendars download again. A restore adds and replaces events, but it never deletes them. Changes that are not uploaded yet are not in the file, so sync before you make a backup.
- If you import a calendar of another app, Grit asks for the calendar read permission first. Grit copies only the calendars that you import, and it never writes to the calendar provider.

## Tests

Run the unit tests:

```sh
./gradlew :shared:caldav:test :shared:calendar:jvmTest
```

Run the integration tests against the development server in `server/radicale` (see its README for the Python setup):

```sh
docker compose -f server/radicale/compose.yaml up -d
server/radicale/seed-dev.sh
RADICALE_URL=http://127.0.0.1:5232 ./gradlew :shared:caldav:test :shared:calendar:jvmTest
```

Render the screens to PNG files:

```sh
GRIT_SCREENSHOTS=/tmp/grit-shots ./gradlew :shared:calendar:jvmTest --tests '*ScreenshotTest*'
```

`:shared:caldav:animalsnifferMain` makes sure that the CalDAV module only calls Java APIs that exist on Android API 29.

## Known limits

- One account in the user interface. The database supports more.
- An event that you delete on the device while a kept calendar does not sync comes back when you start syncing it again.
- Grit creates a server calendar only through "Start syncing". For an empty one, use the web interface of the server.
- A single occurrence of a repeating event cannot be edited, only deleted.
- No day view with hours.
- If two people change the same event between two syncs, the newer change wins. Grit keeps the other version in the database, but does not show it.
- No invitations or attendees.

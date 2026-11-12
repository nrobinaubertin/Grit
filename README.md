# Grit, modified version

This is a modified version of [Grit](https://github.com/shub39/Grit) by shub39, for my own needs. It is not an official Grit release.

The changes in this fork are completely vibe coded: an AI coding assistant wrote them from my instructions. Treat them as such. Expect bugs, and keep backups of your data.

## Main changes from Grit

- A Calendar tab that syncs with a CalDAV server, for example Radicale. Two people can share calendars without another sync app such as DAVx5. The tab has month, week and agenda views, categories with colors, reminders, and calendars that stay on the device only. It imports `.ics` files, and the Grit backup file also contains the calendars. On Android, it can also import the calendars of other apps on the phone. See [shared/calendar/README.md](shared/calendar/README.md) and the server setup in [server/radicale/README.md](server/radicale/README.md).
- Task colors: each task can have a color, chosen in the task editor and shown as a stripe on the task.
- Tasks in the calendar: an open task with a reminder shows in the month, week and agenda views at the time of its reminder, in the color of the task.
- A start screen setting (Tasks, Habits or Calendar) in the general settings, and no Grit Plus block.
- Automatic backups on Android: daily, weekly or monthly, Grit writes its backup file into a folder that you choose, and keeps the number of newest files that you choose. Find them under Backup in the Grit settings.

## License

Like Grit, this fork uses the GNU General Public License version 3. See [LICENSE](LICENSE).

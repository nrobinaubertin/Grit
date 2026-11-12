# Radicale for the Grit calendar

The Grit calendar tab syncs with any CalDAV server. This folder holds a Radicale setup for local development and tests, and notes for a real server.

## Local development server

The development server runs on `127.0.0.1:5232`. You can run it in Docker or in a Python virtual environment. Both use the configuration files in this folder, and both need `curl` for the test data.

### Run in Docker

This setup uses the official Radicale image `ghcr.io/kozea/radicale:3.9.0`. It needs Docker with the Compose plugin.

1. Start the server:

   ```sh
   docker compose -f server/radicale/compose.yaml up -d
   ```

2. Create the test calendars:

   ```sh
   server/radicale/seed-dev.sh
   ```

The data stays in the Docker volume `grit-radicale_radicale-data` after the container stops. If the test calendars exist already, `seed-dev.sh` changes nothing.

- Read the server log: `docker compose -f server/radicale/compose.yaml logs -f`.
- Stop the server: `docker compose -f server/radicale/compose.yaml down`.
- Stop the server and delete its data: add `-v` to the `down` command.

The container can use 128 MiB of memory at most. Radicale uses about 30 MiB. The container runs as user ID 1000 and reads `config`, `users` and `rights` from this folder. If your user ID is not 1000, make these files readable for all users:

```sh
chmod 644 server/radicale/config server/radicale/users server/radicale/rights
```

### Run in a Python virtual environment

This setup stores the data in a temporary folder. It needs Python 3.

1. Start the server:

   ```sh
   server/radicale/run-dev.sh
   ```

   The first start creates a Python virtual environment in `server/radicale/.venv` and installs Radicale 3.9.0. The script also creates the test calendars.

2. Wait for the line `Radicale is ready`.

Stop the server with Ctrl+C. To keep the data after the server stops, set `RADICALE_DATA` to a folder.

### Test users and calendars

The test data has two users and three calendars:

| User | Password | Calendars that the user sees |
|---|---|---|
| alice | alice | `alice/personal`, `alice/family`, the task list `alice/chores` |
| bob | bob | `bob/work`, `bob/family` (shared from alice, writable), `bob/alice-personal` (shared from alice, read-only), the task list `bob/chores` (shared from alice, writable) |

### Connect the desktop app

1. Start the desktop app: `./gradlew :desktopApp:run`.
2. Open the Calendar tab and select Connect.
3. Enter `http://127.0.0.1:5232` as the server URL, and `bob` as the user name and password.

To run two desktop instances as two users, give each instance its own data folder with the `grit.calendar.dir` system property.

### Connect a phone over USB

The phone reaches the development server through `adb reverse`. The calendar module allows plain HTTP only for `localhost`, `127.0.0.1` and `10.0.2.2`.

1. Connect the phone with USB debugging on.
2. Forward the port: `adb reverse tcp:5232 tcp:5232`.
3. Install the debug app: `./gradlew :androidApp:installFossDebug`.
4. In Grit, connect to `http://localhost:5232`.

### Run the integration tests

If `RADICALE_URL` is not set, the integration tests are skipped. Start the development server first, then run:

```sh
RADICALE_URL=http://127.0.0.1:5232 ./gradlew :shared:caldav:test :shared:calendar:jvmTest
```

## A real server

Use Radicale 3.7 or later. Version 3.7 added map shares, which let one user see another user's calendar in their own calendar list. Grit finds these shares by itself. Older versions can share through the rights file, but Grit then needs the calendar URL ("Add calendar by URL" on the account screen).

### Server configuration

Use the Radicale documentation for the installation. This part of the configuration matters for Grit:

```ini
[auth]
type = htpasswd
htpasswd_filename = /etc/radicale/users
htpasswd_encryption = bcrypt

[rights]
type = owner_only

[sharing]
type = csv
collection_by_map = true
permit_create_map = true
default_permissions_create_map = r
```

Put Radicale behind an HTTPS reverse proxy. Android blocks plain HTTP to other hosts. A certificate from Let's Encrypt works without extra steps. For a private CA, install the CA on the phone as a user certificate.

### Share a calendar

The owner creates the share, and the other user accepts it. Both steps use the Radicale web interface or its sharing API. The examples use the API, with `alice` as the owner and `bob` as the other user.

1. As alice, share `alice/personal` with bob, read-only:

   ```sh
   curl -u alice -H "Content-Type: application/json" \
     -d '{"PathOrToken": "/bob/alice-personal/", "PathMapped": "/alice/personal/", "User": "bob", "Permissions": "r", "Enabled": true, "Hidden": false}' \
     https://dav.example.com/.sharing/v1/map/create
   ```

   For a calendar that both users can change, use `"Permissions": "rw"`.

2. As bob, enable the share and make it visible:

   ```sh
   curl -u bob -H "Content-Type: application/json" \
     -d '{"PathOrToken": "/bob/alice-personal/"}' https://dav.example.com/.sharing/v1/map/enable
   curl -u bob -H "Content-Type: application/json" \
     -d '{"PathOrToken": "/bob/alice-personal/"}' https://dav.example.com/.sharing/v1/map/unhide
   ```

3. In Grit, select Sync now on the account screen. The shared calendar appears in bob's list. Grit shows a read-only calendar without edit actions.

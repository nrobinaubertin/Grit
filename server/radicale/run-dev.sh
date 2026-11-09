#!/bin/sh
# Starts a Radicale development server on 127.0.0.1:5232 with temporary storage, and fills it
# with test calendars (see seed-dev.sh). Stop it with Ctrl+C.
#
# Environment variables:
#   RADICALE_PORT  port to listen on (default 5232)
#   RADICALE_DATA  storage folder (default: a new temporary folder)
#   RADICALE_SEED  set to 0 to skip the test data
set -eu

here=$(cd "$(dirname "$0")" && pwd)
venv="$here/.venv"
port=${RADICALE_PORT:-5232}
data=${RADICALE_DATA:-$(mktemp -d)}

if [ ! -x "$venv/bin/radicale" ]; then
    python3 -m venv "$venv"
    "$venv/bin/pip" install --quiet "radicale==3.9.0"
fi

echo "Radicale storage: $data"
"$venv/bin/radicale" \
    --config "$here/config" \
    --server-hosts "127.0.0.1:$port" \
    --auth-htpasswd-filename "$here/users" \
    --rights-file "$here/rights" \
    --storage-filesystem-folder "$data/collections" \
    --sharing-database-path "$data/sharing.csv" &
pid=$!
trap 'kill $pid 2>/dev/null' INT TERM EXIT

# Wait until the server answers
tries=0
until curl -s -o /dev/null "http://127.0.0.1:$port/"; do
    tries=$((tries + 1))
    if [ $tries -gt 50 ]; then
        echo "Radicale did not start" >&2
        exit 1
    fi
    sleep 0.2
done

if [ "${RADICALE_SEED:-1}" != 0 ]; then
    "$here/seed-dev.sh" "http://127.0.0.1:$port"
fi

echo "Radicale is ready on http://127.0.0.1:$port (users: alice/alice, bob/bob)"
wait $pid

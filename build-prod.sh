#!/bin/bash

set -euo pipefail

HOST="ssh-nursepro.alwaysdata.net"
USER="nursepro"
REMOTE_JAR="nurpro.jar"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519_nursepro}"

./mvnw clean package -DskipTests

scp -i "$SSH_KEY" deploy/alwaysdata-java "${USER}@${HOST}:.local/bin/java"
ssh -i "$SSH_KEY" "${USER}@${HOST}" "chmod +x ~/.local/bin/java"

if [ -n "${DB_PASSWORD:-}" ]; then
  printf 'DB_PASSWORD=%s\n' "${DB_PASSWORD}" |
    ssh -i "$SSH_KEY" "${USER}@${HOST}" \
      'umask 077; mkdir -p ~/.config/nursepro; cat > ~/.config/nursepro/env'
fi

scp -i "$SSH_KEY" target/nursepro.jar "${USER}@${HOST}:${REMOTE_JAR}"

if [ -n "${ALWAYSDATA_API_TOKEN:-}" ]; then
  site_id=$(curl -fsS --basic --user "${ALWAYSDATA_API_TOKEN}:" \
    https://api.alwaysdata.com/v1/site/ |
    jq -r '.[] | select((.addresses // []) | index("nursepro.alwaysdata.net")) | .id' |
    head -1)

  if [ -z "$site_id" ] || [ "$site_id" = "null" ]; then
    echo "Could not find the nursepro.alwaysdata.net site." >&2
    exit 1
  fi

  echo "Restarting AlwaysData site ${site_id}"
  curl -fsS -X POST --basic --user "${ALWAYSDATA_API_TOKEN}:" \
    -H 'alwaysdata-synchronous: yes' \
    "https://api.alwaysdata.com/v1/site/${site_id}/restart/"
else
  echo "ALWAYSDATA_API_TOKEN is not set; restart the site from the AlwaysData panel."
fi

echo "Deployed ${REMOTE_JAR} to ${USER}@${HOST}:~"

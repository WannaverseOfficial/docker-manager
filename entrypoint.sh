#!/bin/sh
set -e

# Volumes mounted from the host may be owned by root; the app runs as an unprivileged user.
for dir in /app/database "${GIT_CLONE_DIR:-/opt/docker-manager/git-repos}" "${STACKS_DIR:-/opt/docker-manager/stacks}"; do
  mkdir -p "$dir"
  chown dockermanager:dockermanager "$dir" 2>/dev/null || true
done
# Files created by earlier versions (which ran as root) must stay readable.
chown -R dockermanager:dockermanager /app/database 2>/dev/null || true

SOCK="/var/run/docker.sock"
if [ -S "$SOCK" ]; then
  SOCK_GID=$(stat -c '%g' "$SOCK")
  if [ "$SOCK_GID" = "0" ]; then
    # Docker Desktop and some hosts expose the socket as root:root. Joining group 0 is the only
    # way to reach it without running the whole app as root.
    DOCKER_GROUP=root
  else
    if ! getent group "$SOCK_GID" >/dev/null; then
      groupadd -g "$SOCK_GID" docker-host
    fi
    DOCKER_GROUP=$(getent group "$SOCK_GID" | cut -d: -f1)
  fi
  # shellcheck disable=SC2086
  exec gosu dockermanager:"$DOCKER_GROUP" java $JAVA_OPTS -jar /app/app.jar
fi

# shellcheck disable=SC2086
exec gosu dockermanager java $JAVA_OPTS -jar /app/app.jar

#!/bin/sh
set -e

SOCK="/var/run/docker.sock"

if [ -S "$SOCK" ]; then
  SOCK_GID=$(stat -c '%g' "$SOCK")

  # Create docker group with socket's GID if it doesn't exist
  if ! getent group "$SOCK_GID" >/dev/null; then
    groupadd -g "$SOCK_GID" docker
  fi

  # Get the group name for this GID
  DOCKER_GROUP=$(getent group "$SOCK_GID" | cut -d: -f1)

  # Run as dockermanager user with docker group
  exec gosu dockermanager:$DOCKER_GROUP java $JAVA_OPTS -jar /app/app.jar
fi

# No socket mounted, run without docker group
exec gosu dockermanager java $JAVA_OPTS -jar /app/app.jar

---
title: Install
description: Run Docker Manager with Docker Compose, or try it with one docker run command.
---

Docker Manager runs as a single container on a server that has Docker. The image is
[`wannaverse/docker-manager`](https://hub.docker.com/r/wannaverse/docker-manager) on Docker Hub, built for
`linux/amd64` and `linux/arm64`.

## With Docker Compose

1. Create a folder for it, and in it a file called `docker-compose.yml`:

   ```yaml
   services:
     docker-manager:
       image: wannaverse/docker-manager:latest
       container_name: docker-manager
       pull_policy: always
       restart: unless-stopped
       ports:
         - "8080:8080"
       volumes:
         # Lets Docker Manager control this host's Docker
         - /var/run/docker.sock:/var/run/docker.sock
         # Database, database backups, and the secrets generated on first start
         - docker_manager_data:/app/database
         # Compose stacks and git checkouts. Keep the same path on both sides (see below)
         - /opt/docker-manager/stacks:/opt/docker-manager/stacks
         - /opt/docker-manager/git-repos:/opt/docker-manager/git-repos
       environment:
         # The address you open Docker Manager at; used for webhook URLs and email links
         - BASE_URL=http://your-domain.com
         # Optional: times in emails and logs (default UTC)
         # - TZ=Europe/London

   volumes:
     docker_manager_data:
   ```

2. Change `BASE_URL` to the address you'll open Docker Manager at, for example `http://192.168.1.20:8080` or
   `https://docker.example.com`.
3. Start it:

   ```bash
   docker compose up -d
   ```

4. Open port 8080 in your browser, for example `http://your-server:8080`, and
   [sign in](/docs/start/first-steps/).

The image has its own health check, and the Docker host whose socket is mounted is added automatically on first
start.

## Try it with one command

For a quick look, without a compose file:

```bash
docker run -d -p 8080:8080 -v /var/run/docker.sock:/var/run/docker.sock wannaverse/docker-manager
```

This keeps its database inside the container, so it's lost when the container is removed. Use the compose file
above for anything you want to keep.

## What the mounts are for

| Mount | Why |
| --- | --- |
| `/var/run/docker.sock` | Lets Docker Manager control the Docker engine on this server. |
| `docker_manager_data:/app/database` | Its SQLite database, database backups, and the secrets generated on first start (`.secrets.properties`). Back this volume up. |
| `/opt/docker-manager/stacks` | One folder per [Compose stack](/docs/run/stacks/), with its `compose.yaml` and `.env`. |
| `/opt/docker-manager/git-repos` | Checkouts of your [git repositories](/docs/deploy/git-pipelines/). |

### Why the stack and git folders use the same path on both sides

Docker resolves relative bind mounts in a compose file (like `./data:/data`) on the **host**, not inside Docker
Manager's container. If a stack's folder is `/opt/docker-manager/stacks/my-app` inside the container, it has to be
the same folder on the host, or `./data` points at an empty folder. So mount both folders at the same path on the
host and in the container, as in the example.

If you change the paths, set `STACKS_DIR` and `GIT_CLONE_DIR` to match (see
[Environment variables](/docs/reference/environment/)).

## Optional: DNS

To use the built-in [DNS server](/docs/deploy/dns/), also publish port 53 for UDP and TCP. It listens on port 1053
inside the container:

```yaml
    ports:
      - "8080:8080"
      - "53:1053/udp"
      - "53:1053/tcp"
```

On servers running `systemd-resolved`, port 53 may already be taken. The [DNS guide](/docs/deploy/dns/) explains
what to do.

## Behind a reverse proxy

Before you open Docker Manager to the internet, put it behind HTTPS.

- Consider publishing its port only to the proxy: `127.0.0.1:8080:8080`.
- The proxy must send `X-Forwarded-For` and `X-Forwarded-Proto`. They are trusted from private addresses (Docker
  networks, your LAN). For a proxy on a public address, set `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` to a regular
  expression that matches it. Otherwise every visitor seems to come from the proxy, and they all share one sign-in
  rate limit.
- Volume uploads can be up to 100 MB per file, and uploaded database backups can be larger. Raise the proxy's body
  size limit to match (nginx allows 1 MB by default; for example `client_max_body_size 2g;`).

:::tip
Docker Manager has its own [ingress proxy](/docs/deploy/ingress/) with Let's Encrypt certificates, which can
publish your apps. To serve Docker Manager itself over HTTPS, you can use any reverse proxy you like.
:::

## Secrets

Strong random secrets for sign-in tokens and for encrypting stored credentials (registry passwords, git tokens,
SSH keys, the email password) are generated on first start and kept in the data volume, in
`/app/database/.secrets.properties`. You don't need to set anything.

To manage them yourself, set `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET` and `ENCRYPTION_KEY` to random values of at
least 32 characters, for example from:

```bash
openssl rand -base64 48
```

:::caution
Never reuse the example secrets that older versions of the README showed: they are public. Docker Manager replaces
public or weak sign-in secrets with generated ones automatically.
:::

## Next

[Sign in and take a first look around](/docs/start/first-steps/).

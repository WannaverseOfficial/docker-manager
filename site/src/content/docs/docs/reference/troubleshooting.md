---
title: Troubleshooting
description: Fixes for common problems with installing, signing in, stacks, remote hosts and reverse proxies.
---

Start with Docker Manager's own log. It explains most problems:

```bash
docker logs docker-manager
```

## Signing in

**I can't find the first password.** It's printed once, in the log of the very first start, under `INITIAL ADMIN
ACCOUNT CREATED`. Run `docker logs docker-manager` and scroll up. If you removed and recreated the container but
kept the data volume, the `admin` user already exists and no new password is printed.

**"That username and password don't match."** Check for spaces you copied with the password. After several wrong
attempts, more are refused for a while; the wait doubles with each further failure, up to 15 minutes.

**Someone forgot their password.** An administrator can open **Users**, choose **Reset password** in that person's
menu, and give them the temporary password it shows.

**The only administrator forgot their password.** Reset it from the server:

1. Add `DM_RESET_PASSWORD=admin` (use the user name) to the container's environment, for example under
   `environment:` in the compose file.
2. Recreate the container: `docker compose up -d`.
3. Find the temporary password in the log: `docker logs docker-manager`, after `PASSWORD RESET (DM_RESET_PASSWORD)`.
4. Sign in with it and choose a new password.
5. Remove `DM_RESET_PASSWORD` and run `docker compose up -d` again.

The user is signed out everywhere, and enabled again if they were disabled. If you forget step 5, nothing is reset
again once they've chosen their new password; the log just reminds you to remove the variable. If the user is
managed by the [configuration file](/docs/admin/configuration-as-code/) with a `password:`, sign in with that
password instead.

**Everyone shares one sign-in limit, or the activity log shows the proxy's address for every user.** Docker Manager
is behind a reverse proxy that it doesn't trust. See [Behind a reverse proxy](/docs/start/install/#behind-a-reverse-proxy).

## Docker access

**No hosts appear after installing.** The local host is only added when the Docker socket is mounted. Check that
the compose file has `- /var/run/docker.sock:/var/run/docker.sock` under `volumes`, then recreate the container with
`docker compose up -d`.

**A host shows "Not reachable".**

- For an **SSH** host: the user must be in the `docker` group on the server, its public key must be in
  `~/.ssh/authorized_keys`, and the server needs `AllowTcpForwarding yes` in `/etc/ssh/sshd_config`. The key must
  not have a passphrase.
- For a **TLS** host: check that port 2376 is reachable from Docker Manager's server, and that the CA certificate,
  client certificate and client key are the ones made for that daemon.
- For any host: check that the server is on and Docker is running on it.

## Stacks

**A stack's container can't find its files, or a relative bind mount (`./data`) is empty.** Docker resolves
relative paths on the host. Mount the stacks and git folders at the same path on the host and in the container, as
in the [install example](/docs/start/install/#why-the-stack-and-git-folders-use-the-same-path-on-both-sides).

**A stack is "Not managed here".** It was started outside Docker Manager. Use **Find the compose file** on its page
to import it. See [Stacks started elsewhere](/docs/run/stacks/#stacks-started-elsewhere).

**I can't deploy or stop Docker Manager's own stack.** That's on purpose: it would go offline halfway. Update it on
the server with `docker compose pull && docker compose up -d`.

**I can deploy a stack but not edit its file.** Only administrators edit compose files, because they can give
containers access to the host.

## Containers

**A container I removed came back.** It belongs to a stack, which recreates it on its next deploy. Take the stack
down, or remove the service from its compose file.

**The container page says it ran out of memory.** Its memory limit is too low for what it does. Raise **Memory
limit (MiB)** under **Settings**, **Advanced**, or set it to 0 for no limit.

**`|` or `&&` don't work in the Console.** The Console runs one command at a time without a shell. To use shell
features, run the shell yourself, for example `sh -c "ps aux | grep node"`, if the image has one.

## Uploads and proxies

**Uploading a file or a database backup fails with "413" or a similar error.** Your reverse proxy limits request
size. Raise it, for example `client_max_body_size 2g;` in nginx. Docker Manager itself accepts volume uploads of up
to 100 MB per file.

## After upgrading from 1.x

**Registry passwords, git tokens or the email password stopped working.** If you set your own `ENCRYPTION_KEY` in
1.x, put the same value back in the compose file. See [Upgrade from 1.x](/docs/start/update/#upgrade-from-1x).

## DNS

**Port 53 is already in use.** On many Linux servers, `systemd-resolved` holds port 53. The
[DNS guide](/docs/deploy/dns/) explains how to free it or publish DNS on one address only.

## Still stuck?

Search the [GitHub issues](https://github.com/WannaverseOfficial/docker-manager/issues), or open a new one with the
relevant part of `docker logs docker-manager` (remove any passwords from it first).

---
title: Update and upgrade
description: Update Docker Manager to the newest version, and upgrade from 1.x.
---

## Update

Update Docker Manager on the server, in the folder with its `docker-compose.yml`:

```bash
docker compose pull && docker compose up -d
```

Your users, settings, history and backups are in the data volume, so they're kept.

You can see which version you're running at the bottom of the sidebar. New releases are listed on
[GitHub](https://github.com/WannaverseOfficial/docker-manager/releases).

### Why Docker Manager can't update itself

Docker Manager won't stop, remove, replace or disconnect its own container, and won't deploy or stop its own
Compose project. Compose runs inside it, so it would go offline halfway through with nothing to bring it back. If
you open its own stack, the page says **This is Docker Manager itself** and turns those actions off. Update it with
the command above instead.

## Back up

Everything Docker Manager stores is in its data volume (`docker_manager_data` in the example compose file): the
database, database backups, and the generated secrets. Your stacks' compose files are in the stacks folder
(`/opt/docker-manager/stacks`). Copy both before big upgrades.

## Upgrade from 1.x

Version 2.0 rewrote the interface and changed a few things on the server. Before you upgrade:

1. **Back up first:** copy the `docker_manager_data` volume. On its first start, 2.0 also keeps a copy of the
   database next to it (`data.db.before-2.0-migration`) before adapting its tables.
2. **Check `ENCRYPTION_KEY`.** Registry passwords, git tokens and the SMTP password are encrypted with it.
   - If your 1.x compose file had the example value from the old README, or no key at all, you can drop it. 2.0
     still reads credentials saved with those, and uses a strong generated key for anything saved from now on.
   - If you set **your own** value, keep that exact line in the new compose file, or those credentials can't be
     read and have to be entered again.
3. **Add the folders:** add the two `/opt/docker-manager/...` mounts from the
   [install example](/docs/start/install/). Without them, stacks are kept inside the container, and relative paths
   in compose files point at empty folders.
4. Pull and start the new version as above.

After the upgrade:

- Everyone signs in again once, because sign-in tokens are now made differently.
- Compose deployments made with 1.x were never saved anywhere, so they show up under **Stacks** as "not managed
  here". Use **Find the compose file** on the stack's page, or **Import ... unmanaged** on the Stacks page, to
  manage them. Importing never restarts anything, and an imported stack deploys without removing services it
  doesn't know about. See [Compose stacks](/docs/run/stacks/#stacks-started-elsewhere).
- Git repositories that deploy a Dockerfile now also run the built image. Check their container settings.
- Creating and editing users, groups, git repositories, pipelines and compose files is now limited to
  administrators.

---
title: FAQ
description: Common questions about Docker Manager.
---

## Is it really free?

Yes. Docker Manager is open source under the MIT licence, with no subscriptions, licence fees, accounts or feature
gates. The source is on [GitHub](https://github.com/WannaverseOfficial/docker-manager).

## What does it need?

A server with Docker, on `amd64` or `arm64` (the image is built for both). Docker Manager itself runs in one
container; its Java runtime is limited to 512 MB of memory by default (`JAVA_OPTS`). Features you turn on add their
own containers, for example the [observability stack](/docs/watch/observability/), whose images take about 4 GB of
disk.

## Does it change my existing containers?

No. It shows what's already running and only changes things when you ask. Compose projects started elsewhere are
listed as "not managed here" until you import them, and importing never restarts anything.

## What happens to my apps if I stop using Docker Manager?

Nothing. Everything it creates is an ordinary Docker container, volume or network, and stacks are ordinary compose
files in the stacks folder. Stop or remove Docker Manager's container and your apps keep running. (Features that
run inside Docker Manager itself, like the [DNS server](/docs/deploy/dns/), stop with it.)

## Can it manage more than one server?

Yes. Add other Docker servers over SSH or TLS under [Hosts](/docs/admin/hosts/) and switch between them in the
sidebar. Each host keeps its own containers, stacks, ingress and so on.

## Can I give someone access to only some things?

Yes. Give them permissions per area, action and host, optionally limited to one container or stack, directly or
through groups. See [Users and permissions](/docs/admin/users/).

## Is it safe to put on the internet?

Put it behind HTTPS first, and consider publishing its port only to your reverse proxy. Sign-ins are rate limited,
credentials are stored encrypted, and changes are logged. See
[Behind a reverse proxy](/docs/start/install/#behind-a-reverse-proxy). Remember that whoever can sign in as an
administrator controls the server's Docker, which means the server itself.

## Where is my data?

In the `docker_manager_data` volume (mounted at `/app/database`): the SQLite database, database backups and the
generated secrets. Stack files are in `/opt/docker-manager/stacks`, and git checkouts in
`/opt/docker-manager/git-repos`. Back up the volume and the stacks folder.

## Can I describe the whole setup in a file?

Yes, with [configuration as code](/docs/admin/configuration-as-code/): hosts, users, stacks, ingress, databases and
more in one YAML file, applied at startup and whenever it changes. **Download current setup** on the Configuration
page turns an existing installation into such a file.

## Is there an API?

The web interface uses Docker Manager's own HTTP API. Set `API_DOCS_ENABLED=true` to serve its OpenAPI description at
`/v3/api-docs` and Swagger UI at `/swagger-ui`. They're public when on, so they're off by default.

## How do I update it?

Run `docker compose pull && docker compose up -d` on the server. It can't update itself from the web interface,
because it would go offline halfway through. See [Update and upgrade](/docs/start/update/).

## How do I report a bug or ask for a feature?

Open an issue on [GitHub](https://github.com/WannaverseOfficial/docker-manager/issues). Pull requests are welcome
too. For security problems, follow the
[security policy](https://github.com/WannaverseOfficial/docker-manager/blob/main/SECURITY.md).

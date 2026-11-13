# Configuration as code

Docker Manager's whole setup can be described in one YAML file: hosts, users and groups, registries, image rules,
templates, networks, volumes, containers, stacks, DNS zones and records, ingress with its routes and certificates,
databases, observability, rollouts, git repositories and pipelines. Applying the file creates what is missing, updates what
changed and removes what you took out of it, in the right order. Think of it as CloudFormation or Terraform for
Docker Manager.

- [Two ways to use it](#two-ways-to-use-it)
- [A first file](#a-first-file)
- [How applying works](#how-applying-works)
- [Secrets](#secrets)
- [Referring to other entries](#referring-to-other-entries)
- [Switching an existing installation over](#switching-an-existing-installation-over)
- [Editor support](#editor-support)
- [A complete example](#a-complete-example)
- [Reference](#reference)

## Two ways to use it

**From a file (GitOps).** Mount the file into the container and point `DM_CONFIG_FILE` at it:

```yaml
services:
  docker-manager:
    image: wannaverse/docker-manager:latest
    environment:
      DM_CONFIG_FILE: /config/docker-manager.yaml
      ALICE_PASSWORD: ${ALICE_PASSWORD}
    volumes:
      - ./docker-manager.yaml:/config/docker-manager.yaml:ro
      # ...the usual volumes
```

Docker Manager applies the file at startup, again within about 20 seconds whenever it changes (once it has read
the same content twice, so a file caught halfway through being saved is never applied), and every 5 minutes
puts back anything managed that was removed by other means (for example a container deleted with the Docker CLI).
The **Configuration** page then shows the file read-only, with the result of each apply and an **Apply again now**
button. Keep the file in git and deploy it like any other file.

**From the Configuration page.** Without `DM_CONFIG_FILE`, administrators can paste or write the document on the
**Configuration** page, preview what it would change, and apply it. The document is kept (encrypted) and the same
5-minute check keeps things in line with it. The same works over the API:

| Method and path | What it does |
| --- | --- |
| `POST /api/config/plan` `{"yaml": "..."}` | What applying would change. Changes nothing. |
| `POST /api/config/apply` `{"yaml": "..."}` | Starts applying; returns the run, with its id |
| `GET /api/config/runs/{id}` | Progress and result of a run |
| `POST /api/config/reapply` | Applies the current document again now |
| `GET /api/config/export` | Today's setup as a configuration file |
| `GET /api/config/schema` | The file's JSON schema (public) |
| `GET /api/config` | The current document and the last result |

All of them except the schema need an administrator's token.

| Variable | Default | Meaning |
| --- | --- | --- |
| `DM_CONFIG_FILE` | none | The configuration file to apply. Without it, use the Configuration page. |
| `DM_CONFIG_RECONCILE_SECONDS` | `300` | How often to put back managed things removed by other means. `0` turns it off. |

## A first file

```yaml
version: 1

hosts:
  local:
    url: unix:///var/run/docker.sock

groups:
  developers:
    permissions:
      - resource: containers
        actions: [list, read, start, stop, restart, logs]
      - resource: stacks
        actions: [list, read, deploy, logs]

users:
  alice:
    email: alice@example.com
    password: ${env:ALICE_PASSWORD}
    groups: [developers]

stacks:
  whoami:
    compose: |
      services:
        whoami:
          image: traefik/whoami
          networks: [web]
      networks:
        web:
          external: true

ingress:
  local:
    acmeEmail: ops@example.com
    routes:
      whoami:
        hostname: whoami.example.com
        container: whoami-whoami-1
        port: 80
        tls: lets-encrypt
```

Every file starts with `version: 1`. Each section holds named entries; the name (the key) is how other entries
refer to them, and usually also the name the resource gets in Docker Manager (an explicit `name:` overrides it).
Settings left out take their defaults. Keys starting with `x-` are ignored, so YAML anchors can live there:

```yaml
x-defaults: &web
  restart: unless-stopped
  networks: [web]

containers:
  api:
    <<: *web
    image: ghcr.io/acme/api:1.4.0
```

## How applying works

**Ownership.** Docker Manager remembers what the file created or took over. Removing an entry from the file deletes
what it made; things made by hand in the UI are never touched. When an entry describes something that already
exists (a user with the same name, a stack with the same name on the same host...), applying **takes it over**
instead of creating a duplicate. A takeover changes only what the entry says: a user taken over without `admin:`
or `groups:` keeps them until the entry mentions them.

**Renaming and moving.** Changing an entry's key while it still describes the same thing (for example `web:`
becoming `frontend:` with `name: web`) only renames the entry; nothing is re-created. Moving a container, stack or
network to another host, or renaming a stack or network, is refused, because it would mean making a new one and
deleting the old one with its data: add a new entry and remove the old one, so the preview shows both.

**Data is kept.** Removing a database server, volume, hosted registry, observability stack or DNS zone from the file only
stops managing it, unless its entry said `onRemove: delete` (or `delete-data`, which also removes its data volumes
and backups) when it was last applied. To delete one: set `onRemove: delete`, apply, then remove the entry.

**Order.** Entries are applied in dependency order: SMTP, hosts, groups, users, registries, image rules, templates,
networks, volumes, DNS (settings, health checks, zones, records), ingress, certificates, databases, containers, stacks, hosted registries, observability,
rollouts, git repositories, pipelines, and routes last. Removals run in the reverse order, and only when everything
else applied without errors, so a typo never deletes half your setup. If a removal fails (a network a container
still uses, say), the sections below it wait for the next apply, so a host is never removed while something on it
is still managed.

**Changes.** Only entries whose settings changed since they were last applied are touched. A container whose
settings differ from the running one is re-created the same way the UI does it, keeping the networks that ingress,
databases and observability connected it to. After the changes, links between entries are checked again (a
database's `containers:` list naming a container that a later section created, say) and made. An entry that failed
is tried again when the file changes or someone chooses **Apply again now**. The 5-minute check only re-creates
missing things and re-makes links; it doesn't retry a change that already failed with the same file, since
retrying a container change means stopping the container each time.

**Problems stop everything.** A file with an error (bad YAML, an unknown setting, a missing variable, a reference to
nothing) is not applied at all. Plans and the Configuration page show each problem with where it is, such as
`users.alice.groups: there is nothing called "devs" in groups`.

**Locked in the UI.** Things the file manages show a **Config file** badge, and their edit and delete buttons are
gone; the API refuses such changes with `409 MANAGED_BY_CONFIG`. Everyday actions still work: starting and stopping
containers, deploying stacks, releasing rollouts, running pipelines, backups, queries.

**Not configuration.** Applying never starts a rollout release, a deployment or a pipeline run, and never drops a
database, a role or data. Users still change their own passwords (a password set in the file is applied again only
when it changes in the file).

## Secrets

Never put passwords in the file itself. String values can refer to:

| Reference | Value |
| --- | --- |
| `${env:NAME}` | Docker Manager's environment variable `NAME` (applying fails if it isn't set) |
| `${env:NAME:-fallback}` | The same, with a value to use when it isn't set |
| `${file:/run/secrets/name}` | A file's contents without the trailing newline: Docker or Kubernetes secrets. Relative paths are read next to the configuration file. |
| `$${...}` | A literal `${...}` |

Other `${...}` text, such as a compose file's own `${VARIABLE}`, is left alone. Compose's own escape `$${VAR}` is
written `$$${VAR}`.

Docker Manager's own keys (`ENCRYPTION_KEY`, `JWT_*`) and files in its data folder can't be read this way.

Secrets are never written back out. What was last applied is remembered with a keyed hash in place of each secret,
so changing a password in the environment still counts as a change. Exports write `${env:...}` placeholders for
passwords and keys, and list the variables they need. Container, stack and pipeline variables are exported as they
are, except those named like secrets (`*PASSWORD*`, `*TOKEN*`, `*KEY*`, `*SECRET*`...): check the others before
committing an export. Problems are reported without repeating the value, and passwords generated by a file or
scheduled apply (nobody is there to see them) are written to the log, like the first administrator's.

## Referring to other entries

- `host:` takes a key of `hosts:`, or the name or id of a host added by hand. Leave it out when there is only one
  host.
- `groups:` on a user, `repository:` on a pipeline, `rollout:` on a git repository and so on take the key of that
  entry, or the name of one made by hand.
- Per-host sections (`ingress`, `hostedRegistries`, `observability`) are keyed by the host.
- Routes and certificates live inside their host's `ingress` entry.

## Switching an existing installation over

1. On the **Configuration** page, choose **Start from current setup** (or **Download current setup**). The export
   describes everything you have, with placeholders for secrets.
2. Trim what you don't want managed, set the listed environment variables (or replace them with `${file:...}`),
   and **Preview changes**. Entries that match something existing show **take over**, not **create**.
3. **Apply.** From then on, change things in the file.

## Editor support

The schema at `/api/config/schema` gives completion, checking and hover help in editors that read YAML schemas. In
VS Code with the YAML extension, add this first line to the file:

```yaml
# yaml-language-server: $schema=https://docker.example.com/api/config/schema
```

## A complete example

```yaml
# yaml-language-server: $schema=https://docker.example.com/api/config/schema
version: 1

x-app: &app
  restart: unless-stopped
  networks: [apps]

smtp:
  host: smtp.example.com
  username: docker-manager
  password: ${env:SMTP_PASSWORD}
  from: docker-manager@example.com

hosts:
  web-01:
    url: unix:///var/run/docker.sock
  build-01:
    url: ssh://deploy@build-01.example.com
    sshKey: ${file:/run/secrets/build_01_key}

groups:
  developers:
    description: Run and deploy apps
    permissions:
      - resource: containers
        actions: [list, read, start, stop, restart, logs]
      - resource: stacks
        actions: ["*"]
        host: web-01

users:
  admin:
    email: ops@example.com
    password: ${env:ADMIN_PASSWORD}
    admin: true
  alice:
    email: alice@example.com
    groups: [developers]
    notifications:
      deployment-started: false

registries:
  ghcr:
    url: ghcr.io
    username: acme-bot
    password: ${env:GHCR_TOKEN}
    default: true

imagePolicies:
  trusted:
    type: allow
    rules:
      - pattern: docker.io/library/*
      - pattern: ghcr.io/acme/*

networks:
  apps:
    host: web-01

volumes:
  uploads:
    host: web-01

containers:
  api:
    <<: *app
    host: web-01
    image: ghcr.io/acme/api:1.4.0
    env:
      DATABASE_URL: postgres://app:${env:APP_DB_PASSWORD}@dm-pg-main:5432/app
      PORT: 8080
    volumes: ["uploads:/data/uploads"]
    memoryMb: 512

stacks:
  wiki:
    host: web-01
    compose: |
      services:
        app:
          image: requarks/wiki:2
          env_file: .env
    env:
      DB_PASS: ${file:/run/secrets/wiki_db}

ingress:
  web-01:
    acmeEmail: ops@example.com
    routes:
      api:
        hostname: api.example.com
        container: api
        port: 8080
        tls: lets-encrypt
        httpsRedirect: true
      old-site:
        hostname: old.example.com
        redirect: https://example.com
        redirectPermanent: true

databases:
  main:
    host: web-01
    superuserPassword: ${env:PG_ADMIN_PASSWORD}
    backups: { intervalHours: 12, keep: 14 }
    roles:
      app: { password: "${env:APP_DB_PASSWORD}" }
    databases:
      app: { owner: app }
    containers: [api]

observability:
  web-01:
    access: public
    hostname: grafana.example.com
    containers: [api]

rollouts:
  shop:
    host: web-01
    strategy: canary
    port: 8080
    hostname: shop.example.com
    tls: lets-encrypt
    tests:
      - { kind: http, path: /health, expectStatus: 2xx, retries: 5 }

gitRepositories:
  shop:
    host: web-01
    url: https://github.com/acme/shop.git
    auth: pat
    token: ${env:SHOP_GIT_TOKEN}
    type: dockerfile
    image: acme/shop
    rollout: shop
    webhook: true

pipelines:
  shop-ci:
    host: web-01
    repository: shop
    branchFilter: main
    stages:
      - name: test
        steps:
          - name: unit
            config: { image: "node:22", script: "npm ci && npm test" }
```

## Reference

Generated from the schema (`/api/config/schema`), which is generated from the code, so it matches this
version. Settings left out take their defaults. Every entry of a host-scoped section also takes `host:` (see
[Referring to other entries](#referring-to-other-entries)).

### smtp

The mail server used to send notifications.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `host` | text | yes | Mail server name |
| `port` | whole number |  | Port (default 587) |
| `username` | text |  | Sign-in name, if the server needs one |
| `password` | secret |  | Sign-in password |
| `from` | text | yes | The From address of notifications |
| `fromName` | text |  | The From name (default: Docker Manager) |
| `security` | `none` \| `starttls` \| `ssl-tls` |  | Connection security (default starttls) |
| `enabled` | true/false |  | Send notifications (default true) |

### hosts

Docker daemons to manage, keyed by the name other entries use for them.

Written as `hosts: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `url` | text | yes | Docker's address: unix:///var/run/docker.sock, tcp://server:2375 (2376 with TLS) or ssh://user@server |
| `name` | text |  | Name shown in Docker Manager. Defaults to the entry's key. |
| `tls` | map |  | TLS client certificates, for tcp:// addresses that need them |
| `tls.ca` | secret | yes | The CA certificate (ca.pem) |
| `tls.cert` | secret | yes | The client certificate (cert.pem) |
| `tls.key` | secret | yes | The client key (key.pem) |
| `sshKey` | secret |  | Private key (without a passphrase) for ssh:// addresses |
| `publicIpv4` | text |  | The IPv4 address the internet reaches this host at. DNS records for its ingress routes point here. |
| `publicIpv6` | text |  | The host's public IPv6 address, if it has one |

### groups

User groups and the permissions they give their members.

Written as `groups: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `name` | text |  | The group's name. Defaults to the entry's key. |
| `description` | text |  | What the group is for |
| `permissions` | list of map |  | What members may do. Members are set on each user (groups: [...]). |
| `permissions[].resource` | `docker-hosts` \| `containers` \| `images` \| `volumes` \| `networks` \| `git-repos` \| `deployments` \| `users` \| `user-groups` \| `audit-logs` \| `notifications` \| `docker-operations` \| `state-snapshots` \| `compose-deployments` \| `rollback` \| `image-policies` \| `registries` \| `ingress` \| `ingress-routes` \| `ingress-certificates` \| `templates` \| `pipelines` \| `pipeline-executions` \| `deployment-environments` \| `stacks` \| `databases` \| `rollouts` \| `security` \| `observability` \| `configuration` \| `dns` | yes | The kind of resource, such as containers, stacks or git-repos. databases, security, observability, configuration and dns are for administrators only and can't be granted. |
| `permissions[].actions` | list of text | yes | Actions, such as [list, read, start, stop], or ["*"] for all |
| `permissions[].host` | text |  | Only on this host (a key of hosts:, or a host's name). Default: every host. |
| `permissions[].id` | text |  | Only on this one resource (its id or name). Default: all of them. |

### users

User accounts, keyed by user name.

Written as `users: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `username` | text |  | Sign-in name. Defaults to the entry's key. |
| `email` | text |  | Email address, for notifications |
| `password` | secret |  | Password. Left out: a new user gets a temporary one (shown once after applying) and an existing user keeps theirs. |
| `mustChangePassword` | true/false |  | Ask for a new password at the next sign-in. Default: true for a generated password, false for one set here. |
| `admin` | true/false |  | Full access to everything (default false) |
| `enabled` | true/false |  | Can sign in (default true) |
| `groups` | list of text |  | Groups (keys of groups:, or names of groups made by hand) |
| `permissions` | list of map |  | Permissions of this user alone, besides those of their groups |
| `permissions[].resource` | `docker-hosts` \| `containers` \| `images` \| `volumes` \| `networks` \| `git-repos` \| `deployments` \| `users` \| `user-groups` \| `audit-logs` \| `notifications` \| `docker-operations` \| `state-snapshots` \| `compose-deployments` \| `rollback` \| `image-policies` \| `registries` \| `ingress` \| `ingress-routes` \| `ingress-certificates` \| `templates` \| `pipelines` \| `pipeline-executions` \| `deployment-environments` \| `stacks` \| `databases` \| `rollouts` \| `security` \| `observability` \| `configuration` \| `dns` | yes | The kind of resource, such as containers, stacks or git-repos. databases, security, observability, configuration and dns are for administrators only and can't be granted. |
| `permissions[].actions` | list of text | yes | Actions, such as [list, read, start, stop], or ["*"] for all |
| `permissions[].host` | text |  | Only on this host (a key of hosts:, or a host's name). Default: every host. |
| `permissions[].id` | text |  | Only on this one resource (its id or name). Default: all of them. |
| `notifications` | map of true/false |  | Email notifications to turn on or off, such as deployment-failed: false. Not listed: on. Listing any locks the user's own notification settings. |

### registries

Registries to sign in to when pulling images.

Written as `registries: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `name` | text |  | The registry's name in Docker Manager. Defaults to the entry's key. |
| `type` | `docker-hub` \| `private` \| `aws-ecr` \| `gcr` \| `acr` |  | docker-hub, private (any registry with a user name and password), aws-ecr, gcr or acr. Default: private. |
| `url` | text |  | The registry's address, such as registry.example.com:5000 (default for docker-hub: https://index.docker.io) |
| `username` | text |  | User name (docker-hub, private) |
| `password` | secret |  | Password or access token (docker-hub, private); for aws-ecr, a login token |
| `awsRegion` | text |  | AWS region (aws-ecr) |
| `awsAccessKeyId` | secret |  | AWS access key id (aws-ecr) |
| `awsSecretKey` | secret |  | AWS secret access key (aws-ecr) |
| `gcpProjectId` | text |  | Google Cloud project id (gcr) |
| `gcpServiceAccountJson` | secret |  | Service account key, as JSON (gcr) |
| `azureClientId` | text |  | Azure client (application) id (acr) |
| `azureClientSecret` | secret |  | Azure client secret (acr) |
| `azureTenantId` | text |  | Azure tenant id (acr) |
| `default` | true/false |  | Mark it as the default registry (default false) |
| `enabled` | true/false |  | Use the sign-in when pulling (default true) |

### imagePolicies

Image rules: which images may be pulled and run.

Written as `imagePolicies: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `name` | text |  | The policy's name. Defaults to the entry's key. |
| `type` | `allow` \| `deny` | yes | What the policy says about matching images: allow or deny |
| `description` | text |  | What the policy is for |
| `priority` | whole number |  | Lower numbers are checked first (default 100) |
| `enabled` | true/false |  | Whether the policy is checked (default true) |
| `rules` | list of map |  | Image name patterns, such as docker.io/library/* or ghcr.io/acme/* |
| `rules[].pattern` | text | yes | Image name pattern; * matches anything |
| `rules[].description` | text |  | What the rule is for |

### templates

Your own container and compose templates (built-in ones are not affected).

Written as `templates: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `name` | text |  | The template's name. Defaults to the entry's key. |
| `type` | `container` \| `compose` |  | container (one image) or compose (a compose file). Default: container. |
| `description` | text |  | What it is |
| `category` | text |  | Category it is listed under (default: Custom) |
| `logo` | text |  | Address of a logo image |
| `image` | text |  | Image to run (container templates) |
| `ports` | list of text |  | Ports, as "8080:80" (container templates) |
| `env` | list of text |  | Environment variables, as "KEY=value" (container templates) |
| `volumes` | list of text |  | Volumes, as "name:/path" (container templates) |
| `user` | text |  | User to run as (container templates) |
| `network` | text |  | Network to join (container templates) |
| `compose` | text |  | The compose file (compose templates) |
| `platform` | text |  | Platform, such as linux/amd64 |
| `documentation` | text |  | Address of the documentation |

### networks

User-defined Docker networks that containers and stacks can join.

Written as `networks: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `host` | text |  | The Docker host (a key of hosts:, or a host's name). Default: the only host. |
| `name` | text |  | The network's name in Docker. Defaults to the entry's key. |
| `driver` | text |  | Network driver (default bridge) |
| `internal` | true/false |  | No access to the outside world (default false) |
| `attachable` | true/false |  | Standalone containers can join an overlay network (default false) |
| `subnet` | text |  | Address range, such as 172.28.0.0/16. Default: Docker picks one. |
| `gateway` | text |  | Gateway address within the subnet, such as 172.28.0.1 |
| `options` | map of text |  | Driver options, such as com.docker.network.bridge.name: br-apps |
| `labels` | map of text |  | Labels |

### volumes

Named Docker volumes. Removing an entry keeps the volume unless onRemove: delete.

Written as `volumes: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `host` | text |  | The Docker host (a key of hosts:, or a host's name). Default: the only host. |
| `name` | text |  | The volume's name in Docker. Defaults to the entry's key. |
| `driver` | text |  | Volume driver (default local) |
| `options` | map of text |  | Driver options, such as type: nfs, o: addr=10.0.0.5, device: :/exports/data |
| `labels` | map of text |  | Labels |
| `onRemove` | `keep` \| `delete` |  | What removing the entry does: keep (default) leaves the volume and its data in place and stops managing it; delete removes it |

### dns

The built-in DNS server (an authoritative nameserver for your domains).

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `enabled` | true/false |  | Answer DNS queries (default true) |
| `nameservers` | list of text |  | The nameservers to give your registrar, such as ns1.example.com. Default: ns1 and ns2 of each zone. |
| `publicIpv4` | text |  | This server's public IPv4 address, for nameserver records. Default: the public address of the host Docker Manager runs on. |
| `publicIpv6` | text |  | This server's public IPv6 address |
| `hostmasterEmail` | text |  | The zones' contact address (SOA), such as hostmaster@example.com |
| `transferAllowed` | list of text |  | Secondary nameservers allowed to copy the zones (AXFR): IP addresses or networks. Single addresses are also told about changes (NOTIFY). |
| `rateLimit` | whole number |  | Most answers per second to one address over UDP (default 100; 0 turns it off) |

### dnsHealthChecks

Health checks for DNS failover and weighted records, keyed by name.

Written as `dnsHealthChecks: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `type` | text |  | http (default), https or tcp |
| `address` | text | yes | IP address or hostname to check |
| `port` | whole number |  | Port (default 80, or 443 for https) |
| `path` | text |  | http/https: the path requested (default /); 2xx and 3xx answers pass |
| `hostHeader` | text |  | http/https: the Host header and TLS name to send (default: address) |
| `interval` | whole number |  | Seconds between checks (default 30, at least 10) |
| `timeout` | whole number |  | Seconds to wait for an answer (default 5) |
| `failureThreshold` | whole number |  | Failures in a row before unhealthy, and successes before healthy (default 3) |
| `notify` | true/false |  | Email administrators when the status changes (default true) |
| `enabled` | true/false |  | Run the check (default true) |

### dnsZones

Domains the DNS server answers for (hosted zones), keyed by domain name, with their records.

Written as `dnsZones: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `comment` | text |  | A note for people |
| `defaultTtl` | whole number |  | TTL of records that don't set one, in seconds (default 300) |
| `negativeTtl` | whole number |  | How long resolvers remember that a name doesn't exist, in seconds (default 300) |
| `nameservers` | list of text |  | This zone's nameservers, if not the ones of the dns section |
| `autoRecords` | true/false |  | Ingress routes under this domain get records pointing at their host (default true) |
| `dnssec` | true/false |  | Sign the zone (DNSSEC, default false). Give the DS record shown on the zone's Setup tab to your registrar. |
| `onRemove` | `keep` \| `delete` |  | What removing the entry does: keep (default) leaves the zone answering and stops managing it; delete removes it with all its records |

### dnsZones.<domain>.records

Record sets of the zone, keyed by a name of your choice (the record's name by default).

Written as `dnsZones.<domain>.records: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `name` | text |  | The name in the zone: www, @ for the domain itself, *.dev for a wildcard. Default: the entry's key. |
| `type` | text | yes | A, AAAA, CNAME, MX, TXT, SRV, CAA, NS, PTR or DS |
| `ttl` | whole number |  | Seconds resolvers may keep the answer (default: the zone's) |
| `values` | list of text | yes | The values as in a zone file, such as 203.0.113.10 or "10 mail.example.com". Hostnames are always complete. |
| `routing` | text |  | simple (default), weighted or failover |
| `setId` | text |  | weighted/failover: tells record sets with the same name and type apart |
| `weight` | whole number |  | weighted: share of answers, 0 to 255 (default 1) |
| `failover` | text |  | failover: primary or secondary |
| `healthCheck` | text |  | Answer with this set only while this health check (a key of dnsHealthChecks) passes |
| `comment` | text |  | A note for people |

### ingress

The ingress proxy of each host (keyed by the host), with its routes and certificates.

Written as `ingress: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `enabled` | true/false |  | Run the proxy (default true). False keeps the settings but stops it. |
| `httpPort` | whole number |  | Port on the host for HTTP (default 80) |
| `httpsPort` | whole number |  | Port on the host for HTTPS (default 443) |
| `acmeEmail` | text |  | Email Let's Encrypt sends expiry warnings to |
| `staging` | true/false |  | Use Let's Encrypt's staging environment, for testing (default false) |

### ingress.<host>.certificates

Certificates you bring, keyed by a name (usually the hostname).

Written as `ingress.<host>.certificates: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `hostname` | text |  | The hostname it is for. Defaults to the entry's key. |
| `certificate` | secret | yes | The certificate (PEM), such as ${file:/run/secrets/app.crt} |
| `privateKey` | secret | yes | Its private key (PEM) |
| `chain` | secret |  | Intermediate certificates (PEM), if the certificate doesn't include them |

### ingress.<host>.routes

Routes of the proxy, keyed by a name of your choice.

Written as `ingress.<host>.routes: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `hostname` | text | yes | The hostname visitors use, such as app.example.com |
| `path` | text |  | The path it answers under (default /) |
| `container` | text |  | Send traffic to this container on the same host (its name) |
| `address` | text |  | Send traffic to this IPv4 address or DNS name instead of a container |
| `port` | whole number |  | The container's or address's port (required with container or address) |
| `protocol` | `http` \| `https` |  | How the proxy talks to the target: http (default) or https |
| `redirect` | text |  | Send visitors to this web address instead (a redirect) |
| `redirectPermanent` | true/false |  | Redirect: permanent (301) instead of temporary (302); default false |
| `redirectKeepPath` | true/false |  | Redirect: add the visitor's path and query to the address; default false |
| `tls` | `none` \| `lets-encrypt` \| `custom-cert` \| `bring-your-own` |  | HTTPS: none (default), lets-encrypt (requested automatically), or custom-cert / bring-your-own (a certificate under certificates: for this hostname) |
| `httpsRedirect` | true/false |  | Send HTTP visitors to HTTPS (default false; needs tls) |
| `auth` | map |  | Ask visitors for a user name and password |
| `auth.users` | map of secret | yes | Who may sign in: user name to password (at least 8 characters) |
| `unlimitedUploads` | true/false |  | No upload size limit or buffering, for large uploads (default false) |
| `enabled` | true/false |  | Answer visitors (default true). False keeps the route but pauses it. |

### databases

PostgreSQL servers with their backups, users and databases.

Written as `databases: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `host` | text |  | The Docker host (a key of hosts:, or a host's name). Default: the only host. |
| `name` | text |  | Server name: 3 to 32 lowercase letters, digits or dashes. Defaults to the entry's key. |
| `version` | text |  | PostgreSQL major version (18, 17 or 16; default 18). Can't be changed after creating: back up, create a new server and restore. |
| `superuserPassword` | secret |  | Password of the postgres administrator. Left out: generated when created, and an existing one is kept. |
| `port` | whole number |  | Publish PostgreSQL on this host port (1024-65535). Left out: not published. |
| `address` | `localhost` \| `all` |  | Where the published port listens: localhost (127.0.0.1, default) or all (every interface) |
| `backups` | map |  | Automatic backups (default every 24 hours, keeping 7) |
| `backups.intervalHours` | whole number |  | Hours between automatic backups, 0 for none (default 24) |
| `backups.keep` | whole number |  | How many automatic backups to keep, 1-365 (default 7) |
| `s3` | map |  | Copy backups to S3-compatible storage |
| `s3.enabled` | true/false |  | Copy backups to S3 (default true) |
| `s3.endpoint` | text |  | Endpoint URL, for storage other than AWS (such as https://s3.example.com) |
| `s3.region` | text |  | Region, such as eu-west-1 |
| `s3.bucket` | text | yes | Bucket name |
| `s3.prefix` | text |  | Folder in the bucket |
| `s3.accessKey` | text |  | Access key id |
| `s3.secretKey` | secret |  | Secret access key. Left out: the saved one is kept. |
| `containers` | list of text |  | Containers (names) connected to the server's network, so they can reach it at dm-pg-<name>:5432. Containers not listed are left connected. |
| `onRemove` | `keep` \| `delete` \| `delete-data` |  | When the entry is removed from the file: keep (default; it keeps running, no longer managed), delete (remove the server, keep its data volume and backups) or delete-data (remove everything). Takes effect once applied, so apply it before removing the entry. |

### databases.<name>.roles

Login users, created when missing. Never dropped by the file. A password given here is set whenever it changes.

Written as `databases.<name>.roles: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `password` | secret |  | The user's password (at least 8 characters). Left out: generated when created (shown once after applying), and an existing one is kept. |

### databases.<name>.databases

Databases, created when missing. Never dropped by the file.

Written as `databases.<name>.databases: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `owner` | text |  | The user who owns it, used when creating it. Default: a user of the same name, created with a generated password when missing. |
| `access` | map of `none` \| `read` \| `write` |  | Access for other users: none, read or write |

### containers

Standalone containers (not part of a stack), keyed by container name.

Written as `containers: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `host` | text |  | The Docker host (a key of hosts:, or a host's name). Default: the only host. |
| `name` | text |  | The container's name in Docker. Defaults to the entry's key. |
| `image` | text | yes | Image, such as nginx:1.27 or ghcr.io/me/app:2.1 |
| `command` | list of text |  | Command arguments. Default: the image's. |
| `entrypoint` | list of text |  | Entrypoint. Default: the image's. |
| `env` | map of secret |  | Environment variables. Use ${env:NAME} or ${file:...} for secrets. |
| `ports` | list of text |  | Published ports as "host:container", "ip:host:container" or "container" (exposed only), with /udp for UDP: ["8080:80", "127.0.0.1:5432:5432", "53:53/udp"] |
| `volumes` | list of text |  | Volumes as "volume-name:/path" or "/host/path:/path", with :ro for read-only. Volumes made by Docker for the image are kept. |
| `networks` | list of text |  | Networks to join (Docker names); the first is the main one. Default: bridge. |
| `restart` | `no` \| `always` \| `unless-stopped` \| `on-failure` |  | Restart policy (default unless-stopped) |
| `labels` | map of text |  | Labels |
| `user` | text |  | Run as this user (name or uid[:gid]). Default: the image's. |
| `workingDir` | text |  | Working directory. Default: the image's. |
| `privileged` | true/false |  | Full access to the host's devices (default false) |
| `capAdd` | list of text |  | Linux capabilities to add, such as [NET_ADMIN] |
| `memoryMb` | whole number |  | Memory limit in MiB (default: none) |
| `cpus` | number |  | CPU limit in cores, such as 1.5 (default: none) |
| `running` | true/false |  | Keep it running (default true). false creates it stopped, or stops it. |
| `pull` | true/false |  | Pull the image again when the container is re-created for a change (for moving tags such as latest). Default false: pull only when missing. |

### stacks

Compose stacks: a compose file and its variables, deployed on a host.

Written as `stacks: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `host` | text |  | The Docker host (a key of hosts:, or a host's name). Default: the only host. |
| `name` | text |  | The compose project name: lowercase letters, digits, '-' and '_'. Defaults to the entry's key. |
| `compose` | text | yes | The compose file (use a \| block). Its own ${VARIABLES} are left for compose to fill in from env. |
| `env` | map of secret |  | Variables for the compose file, written to the stack's .env. Use ${env:NAME} or ${file:...} for secrets. |
| `deploy` | true/false |  | Deploy (docker compose up) whenever the entry changes (default true). false only saves the stack. |
| `removeVolumes` | true/false |  | When the entry is removed, also delete the stack's named volumes and their data (default false: volumes are kept) |

### hostedRegistries

Private Docker registries run by Docker Manager, keyed by host.

Written as `hostedRegistries: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `mode` | `local` \| `public` |  | local (reachable as localhost:port on the host) or public (behind ingress, with HTTPS at hostname). Default: local. |
| `hostname` | text |  | The registry's hostname, such as registry.example.com (public only) |
| `port` | whole number |  | Port on the host, 1024-65535 (local only, default 5000) |
| `users` | map of secret | yes | Who can push and pull: user name -> password (at least 10 characters). Users not listed are removed. |
| `onRemove` | `keep` \| `delete` |  | What removing this entry does: keep (default; the registry and its images stay) or delete (removes the registry and its images) |

### observability

Grafana, Prometheus, Mimir, Loki and Tempo per host, keyed by the host.

Written as `observability: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `mimir` | true/false |  | Keep metrics long-term in Mimir (default true) |
| `loki` | true/false |  | Collect logs in Loki (default true) |
| `tempo` | true/false |  | Collect traces in Tempo (default true) |
| `collectLogs` | true/false |  | Collect every container's logs (default true; needs loki) |
| `collectMetrics` | true/false |  | Collect host and container metrics (default true) |
| `otlp` | true/false |  | Accept OpenTelemetry from apps (default true) |
| `metricsRetentionDays` | whole number |  | Days of metrics to keep, 1-3650 (default 30) |
| `logsRetentionDays` | whole number |  | Days of logs to keep, 1-3650 (default 14) |
| `tracesRetentionDays` | whole number |  | Days of traces to keep, 1-365 (default 7) |
| `access` | `local` \| `public` |  | How Grafana is reached: local (a port on the host, default) or public (through Ingress at hostname, which must be turned on for the host) |
| `hostname` | text |  | Grafana's full hostname, for public access, such as grafana.example.com |
| `port` | whole number |  | Grafana's port on the host, for local access (default 3000) |
| `dockerManagerUrl` | text |  | The address people open Docker Manager at, for signing in to Grafana with public access. Default: BASE_URL. |
| `containers` | list of text |  | Containers (names) connected to the OpenTelemetry network, so they can send to dm-obs-alloy:4317. Containers not listed are left connected. |
| `onRemove` | `keep` \| `delete` \| `delete-data` |  | When the entry is removed from the file: keep (default; it keeps running, no longer managed), delete (remove the containers, keep the collected data volumes) or delete-data (remove everything). Takes effect once applied, so apply it before removing the entry. |

### rollouts

Blue/green and canary rollouts. Applying the file never starts a release.

Written as `rollouts: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `host` | text |  | The Docker host (a key of hosts:, or a host's name). Default: the only host. |
| `name` | text |  | The rollout's name. Defaults to the entry's key. |
| `description` | text |  | What the rollout is for |
| `strategy` | `blue-green` \| `canary` |  | blue-green (switch all traffic at once, default) or canary (shift it in steps) |
| `containerName` | text |  | Base name of the app's containers (-blue and -green are added). Default: from the name. |
| `container` | map |  | How the app's container runs. Ports are not published: visitors come through ingress. |
| `container.name` | text |  | Container name (Git repositories only; a rollout names its containers itself) |
| `container.command` | list of text |  | Command arguments. Empty keeps the image's default. |
| `container.entrypoint` | list of text |  | Entrypoint override. Empty keeps the image's default. |
| `container.env` | list of text |  | Environment variables, as KEY=value |
| `container.ports` | list of text |  | Published ports (Git repositories only), as "8080:80", "127.0.0.1:8080:80" or "53:53/udp" |
| `container.volumes` | list of text |  | Volumes and bind mounts, as "volume:/path" or "/host/path:/path:ro" |
| `container.networks` | list of text |  | Networks to join; the first is the main one. Empty: the default bridge. |
| `container.restartPolicy` | text |  | Restart policy: no, always, unless-stopped (default) or on-failure |
| `container.labels` | map of text |  | Container labels |
| `container.user` | text |  | User to run as, such as 1000:1000 |
| `container.workingDir` | text |  | Working directory inside the container |
| `container.privileged` | true/false |  | Run privileged (administrators only; default false) |
| `container.capAdd` | list of text |  | Linux capabilities to add, such as NET_ADMIN |
| `container.memoryLimitMb` | whole number |  | Memory limit in MiB (default: none) |
| `container.cpus` | number |  | CPU limit in cores, such as 1.5 (default: none) |
| `port` | whole number | yes | The port the app listens on inside the container |
| `hostname` | text |  | The address visitors use, such as www.example.com. Its route is created at the first release; after that the address is changed on the route. |
| `path` | text |  | Path prefix of the address (default /) |
| `tls` | `none` \| `lets-encrypt` |  | HTTPS for the address: none (default) or lets-encrypt |
| `previewHostname` | text |  | A second address that always shows the new version, such as next.example.com |
| `healthPath` | text |  | Path checked to see that a new version is up (default /) |
| `startTimeoutSeconds` | whole number |  | How long a new version may take to start, in seconds (default 180) |
| `tests` | list of map |  | Checks run against a new version before it gets visitors |
| `tests[].name` | text |  | Shown in the release's steps |
| `tests[].kind` | `http` \| `command` \| `pipeline` \| `approval` |  | http (default), command, pipeline or approval |
| `tests[].path` | text |  | http: the path to request (default /) |
| `tests[].expectStatus` | text |  | http: expected status codes, such as 200, 2xx or 200-299 |
| `tests[].bodyContains` | text |  | http: text the response must contain |
| `tests[].retries` | whole number |  | http: tries before failing, 1-30 |
| `tests[].image` | text |  | command: the image to run the script in |
| `tests[].script` | text |  | command: the script; TARGET_URL points at the new version |
| `tests[].pipeline` | text |  | pipeline: the pipeline to run (a key of pipelines:, or a pipeline's name) |
| `tests[].timeoutSeconds` | whole number |  | Time limit in seconds. approval: empty waits until someone decides. |
| `canarySteps` | list of whole number |  | Canary: traffic percentages to step through (default [10, 25, 50]) |
| `stepSeconds` | whole number |  | Canary: seconds at each step (default 120) |
| `stickySessions` | true/false |  | Canary: keep each visitor on one version (default true) |
| `autoRollback` | true/false |  | Roll back automatically when the limits below are passed (default true) |
| `maxErrorRate` | number |  | Highest acceptable share of failed requests, in percent (default 5) |
| `maxAvgResponseMs` | whole number |  | Highest acceptable average response time in ms (default 0: no limit) |
| `minRequests` | whole number |  | Requests needed before the limits are judged (default 20) |
| `bakeSeconds` | whole number |  | Seconds to watch a new version after it gets all traffic (default 120) |
| `keepPreviousRunning` | true/false |  | Keep the previous version's container running after a release (default false) |

### gitRepositories

Git repositories to build and deploy. Applying the file never deploys.

Written as `gitRepositories: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `host` | text |  | The Docker host that builds and runs it (a key of hosts:). Default: the only host. |
| `name` | text |  | Name shown in Docker Manager. Defaults to the entry's key. |
| `url` | text | yes | Clone address: https://... or git@host:owner/repo.git |
| `branch` | text |  | Branch to deploy (default main) |
| `auth` | `none` \| `pat` \| `ssh-key` |  | How to sign in to the Git server: none (default), pat or ssh-key |
| `token` | secret |  | auth: pat. The access token. |
| `tokenUsername` | text |  | auth: pat. The user name sent with the token (default oauth2). |
| `sshKey` | secret |  | auth: ssh-key. The private key (without a passphrase). |
| `type` | `dockerfile` \| `docker-compose` | yes | How to deploy: dockerfile or docker-compose |
| `dockerfile` | text |  | dockerfile: path of the Dockerfile in the repository (default Dockerfile) |
| `compose` | text |  | docker-compose: path of the compose file (default compose.yaml) |
| `image` | text |  | dockerfile: name of the built image (default: from the repository name) |
| `runContainer` | true/false |  | dockerfile: run the built image as a container (default true) |
| `container` | map |  | dockerfile: how the container runs |
| `container.name` | text |  | Container name (Git repositories only; a rollout names its containers itself) |
| `container.command` | list of text |  | Command arguments. Empty keeps the image's default. |
| `container.entrypoint` | list of text |  | Entrypoint override. Empty keeps the image's default. |
| `container.env` | list of text |  | Environment variables, as KEY=value |
| `container.ports` | list of text |  | Published ports (Git repositories only), as "8080:80", "127.0.0.1:8080:80" or "53:53/udp" |
| `container.volumes` | list of text |  | Volumes and bind mounts, as "volume:/path" or "/host/path:/path:ro" |
| `container.networks` | list of text |  | Networks to join; the first is the main one. Empty: the default bridge. |
| `container.restartPolicy` | text |  | Restart policy: no, always, unless-stopped (default) or on-failure |
| `container.labels` | map of text |  | Container labels |
| `container.user` | text |  | User to run as, such as 1000:1000 |
| `container.workingDir` | text |  | Working directory inside the container |
| `container.privileged` | true/false |  | Run privileged (administrators only; default false) |
| `container.capAdd` | list of text |  | Linux capabilities to add, such as NET_ADMIN |
| `container.memoryLimitMb` | whole number |  | Memory limit in MiB (default: none) |
| `container.cpus` | number |  | CPU limit in cores, such as 1.5 (default: none) |
| `rollout` | text |  | dockerfile: release new images through this rollout (a key of rollouts:) |
| `polling` | true/false |  | Check the branch for new commits regularly (default false) |
| `pollingInterval` | whole number |  | Seconds between checks, at least 60 (default 300) |
| `webhook` | true/false |  | Deploy when the Git server calls the webhook (default false) |

### pipelines

Build and test pipelines. Applying the file never runs one.

Written as `pipelines: { <name>: { ...settings } }`.

| Setting | Type | Required | Meaning |
| --- | --- | --- | --- |
| `host` | text |  | The Docker host the steps run on (a key of hosts:). Default: the only host. |
| `name` | text |  | The pipeline's name. Defaults to the entry's key. |
| `description` | text |  | What the pipeline does |
| `repository` | text |  | The Git repository it checks out (a key of gitRepositories:, or a repository's name) |
| `enabled` | true/false |  | Runs when triggered (default true) |
| `webhook` | true/false |  | Run when the Git server calls the pipeline's webhook (default true) |
| `polling` | true/false |  | Check the repository for new commits regularly and run for each new one (default false). Needs a repository. |
| `pollingInterval` | whole number |  | Seconds between checks (default 300, at least 60) |
| `branchFilter` | text |  | Only run for branches this regular expression fully matches, such as main\|release/.*. Polling without it watches only the repository's branch. |
| `stages` | list of map |  | Stages, run in order unless dependsOn says otherwise |
| `stages[].name` | text | yes | The stage's name, unique in the pipeline |
| `stages[].mode` | `sequential` \| `parallel` |  | sequential (default): steps one after another; parallel: all at once |
| `stages[].dependsOn` | list of text |  | Names of stages that must finish first |
| `stages[].stopOnFailure` | true/false |  | Stop the pipeline when a step fails (default true) |
| `stages[].steps` | list of map |  | The stage's steps |
| `stages[].steps[].name` | text | yes | The step's name |
| `stages[].steps[].type` | `shell` \| `jar` \| `dockerfile` \| `docker-compose` \| `custom-image` \| `rollout` |  | shell (default), jar, dockerfile, docker-compose, custom-image or rollout |
| `stages[].steps[].config` | map |  | The step's settings, as in the step editor: shell {image, script}, jar {jarPath, jvmOpts, args}, custom-image {image, command}, rollout {rollout: <key of rollouts:>, image, wait}... |
| `stages[].steps[].workingDirectory` | text |  | Working directory in the checkout |
| `stages[].steps[].timeoutSeconds` | whole number |  | Time limit in seconds (default 3600) |
| `stages[].steps[].continueOnFailure` | true/false |  | Carry on with the next step when this one fails (default false) |
| `stages[].steps[].env` | map of text |  | Environment variables for this step |
| `stages[].steps[].artifactsIn` | text |  | Artifacts from earlier steps to copy in (a glob) |
| `stages[].steps[].artifactsOut` | text |  | Files to keep as artifacts (a glob) |

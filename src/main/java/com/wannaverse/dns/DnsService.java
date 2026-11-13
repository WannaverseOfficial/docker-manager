package com.wannaverse.dns;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannaverse.dns.ZoneSource.Kind;
import com.wannaverse.dns.ZoneSource.Policy;
import com.wannaverse.dns.ZoneSource.RecordSet;
import com.wannaverse.persistence.DnsHealthCheck;
import com.wannaverse.persistence.DnsHealthCheckRepository;
import com.wannaverse.persistence.DnsRecord;
import com.wannaverse.persistence.DnsRecordRepository;
import com.wannaverse.persistence.DnsSettings;
import com.wannaverse.persistence.DnsSettingsRepository;
import com.wannaverse.persistence.DnsZone;
import com.wannaverse.persistence.DnsZoneRepository;
import com.wannaverse.persistence.DockerHost;
import com.wannaverse.persistence.DockerHostRepository;
import com.wannaverse.persistence.IngressConfig;
import com.wannaverse.persistence.IngressConfigRepository;
import com.wannaverse.persistence.IngressRoute;
import com.wannaverse.persistence.IngressRouteRepository;
import com.wannaverse.service.EncryptionService;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.xbill.DNS.DClass;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hosted zones and their records, and the zones the DNS server answers from.
 *
 * <p>Whenever something changes (a record, an ingress route, a host's address, a health check, a
 * certificate challenge), the zones are built again shortly after and swapped in whole; the SOA
 * serial goes up only when the answers really changed, and secondaries are told (NOTIFY).
 */
@Service
public class DnsService {
    private static final Logger log = LoggerFactory.getLogger(DnsService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    /** TTL of automatic records: short, so a move to another host is seen quickly. */
    static final long AUTO_TTL = 60;

    public static final String POLICY_WEIGHTED = "WEIGHTED", POLICY_FAILOVER = "FAILOVER";

    private final DnsZoneRepository zones;
    private final DnsRecordRepository records;
    private final DnsHealthCheckRepository healthChecks;
    private final DnsSettingsRepository settingsRepo;
    private final DockerHostRepository hosts;
    private final IngressConfigRepository ingressConfigs;
    private final IngressRouteRepository routes;
    private final EncryptionService encryption;

    private final ZoneBuilder builder = new ZoneBuilder();
    private volatile Map<Name, ZoneView> catalog = Map.of();
    private volatile Map<String, ZoneInfo> infos = Map.of();

    /** Let's Encrypt DNS-01 challenges being answered: name -> TXT values. */
    private final Map<Name, Set<String>> challenges = new ConcurrentHashMap<>();

    private final ScheduledExecutorService rebuilds =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "dns-rebuild");
                        t.setDaemon(true);
                        return t;
                    });
    private final AtomicBoolean rebuildQueued = new AtomicBoolean();

    public DnsService(
            DnsZoneRepository zones,
            DnsRecordRepository records,
            DnsHealthCheckRepository healthChecks,
            DnsSettingsRepository settingsRepo,
            DockerHostRepository hosts,
            IngressConfigRepository ingressConfigs,
            IngressRouteRepository routes,
            EncryptionService encryption) {
        this.zones = zones;
        this.records = records;
        this.healthChecks = healthChecks;
        this.settingsRepo = settingsRepo;
        this.hosts = hosts;
        this.ingressConfigs = ingressConfigs;
        this.routes = routes;
        this.encryption = encryption;
        DnsInputs.register(this::requestRebuild);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        rebuildNow();
    }

    @PreDestroy
    void stop() {
        rebuilds.shutdownNow();
    }

    // ------------------------------------------------------------------------------------------
    // What the server answers from

    /** The zones as answered now, by name. */
    public Map<Name, ZoneView> catalog() {
        return catalog;
    }

    /** Automatic records and problems of a zone, from the last build. */
    public ZoneInfo info(String zoneId) {
        return infos.getOrDefault(zoneId, new ZoneInfo(List.of(), List.of()));
    }

    /** Builds the zones again shortly (several changes in a row cause one build). */
    public void requestRebuild() {
        if (rebuildQueued.compareAndSet(false, true)) {
            try {
                rebuilds.schedule(
                        () -> {
                            rebuildQueued.set(false);
                            rebuildNow();
                        },
                        300,
                        TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                rebuildQueued.set(false);
            }
        }
    }

    /** Also renews signatures before they expire and catches anything a change didn't report. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void periodicRebuild() {
        rebuildNow();
        builder.pruneSignatures();
    }

    public synchronized void rebuildNow() {
        try {
            build();
        } catch (Exception e) {
            log.error("Building the DNS zones failed: {}", e.getMessage(), e);
        }
    }

    private void build() {
        DnsSettings settings = settings();
        List<DnsZone> all = zones.findAll();
        Map<String, Boolean> health = new HashMap<>();
        for (DnsHealthCheck c : healthChecks.findAll()) {
            if (!c.isEnabled() || "UNKNOWN".equals(c.getStatus())) continue;
            health.put(c.getId(), "HEALTHY".equals(c.getStatus()));
        }
        List<AutoSource> autos = autoSources();
        Addresses self = selfAddresses(settings);

        Map<Name, ZoneView> next = new HashMap<>();
        Map<String, ZoneInfo> nextInfos = new HashMap<>();
        for (DnsZone zone : all) {
            List<String> warnings = new ArrayList<>();
            List<Generated> generated = new ArrayList<>();
            try {
                ZoneSource src = source(zone, settings, health, autos, self, warnings, generated);
                ZoneBuilder.Prepared p = builder.prepare(src);
                long serial = zone.getSerial();
                if (!p.contentHash().equals(zone.getContentHash()) || serial == 0) {
                    serial = nextSerial(serial);
                    zone.setSerial(serial);
                    zone.setContentHash(p.contentHash());
                    // Only these two columns: a whole save could undo an edit made meanwhile.
                    zones.updateSerial(zone.getId(), serial, p.contentHash());
                    if (catalog.containsKey(src.origin())) notifySecondaries(zone, settings);
                }
                ZoneView view = builder.finish(p, serial);
                next.put(view.origin(), view);
            } catch (Exception e) {
                log.warn("Zone {} could not be built: {}", zone.getName(), e.getMessage(), e);
                warnings.add("This zone is not answered: " + e.getMessage());
                ZoneView old = catalog.get(name(zone.getName()));
                if (old != null) next.put(old.origin(), old);
            }
            nextInfos.put(
                    zone.getId(), new ZoneInfo(List.copyOf(generated), List.copyOf(warnings)));
        }
        catalog = Collections.unmodifiableMap(next);
        infos = Collections.unmodifiableMap(nextInfos);
    }

    /** yyyyMMdd00 for the first change of a day, then one up. */
    static long nextSerial(long old) {
        long today =
                Long.parseLong(
                                LocalDate.now(ZoneOffset.UTC)
                                        .format(DateTimeFormatter.BASIC_ISO_DATE))
                        * 100;
        return Math.max(old + 1, today);
    }

    /** An automatic record made from an ingress route. */
    record AutoSource(
            String hostname, String hostId, String hostName, String routeId, Addresses addresses) {}

    public record Addresses(String ipv4, String ipv6) {
        boolean none() {
            return blank(ipv4) && blank(ipv6);
        }
    }

    private List<AutoSource> autoSources() {
        Map<String, DockerHost> byId = new HashMap<>();
        for (DockerHost h : hosts.findAll()) byId.put(h.getId(), h);
        Map<String, IngressConfig> enabled = new HashMap<>();
        for (IngressConfig c : ingressConfigs.findAll()) {
            if (c.getStatus() == IngressConfig.IngressStatus.ENABLED) enabled.put(c.getId(), c);
        }
        List<AutoSource> out = new ArrayList<>();
        for (IngressRoute r : routes.findAll()) {
            if (!r.isEnabled()) continue;
            IngressConfig c = enabled.get(r.getIngressConfigId());
            if (c == null) continue;
            DockerHost h = byId.get(c.getDockerHostId());
            if (h == null) continue;
            out.add(
                    new AutoSource(
                            r.getHostname().toLowerCase(Locale.ROOT),
                            h.getId(),
                            h.getName() == null ? h.getDockerHostUrl() : h.getName(),
                            r.getId(),
                            new Addresses(h.getPublicIpv4(), h.getPublicIpv6())));
        }
        return out;
    }

    /** This server's public addresses, as used for nameserver records. */
    public Addresses selfAddressesView() {
        return selfAddresses(settings());
    }

    /**
     * This server's public addresses: from the DNS settings, else those of the Docker host it runs
     * on (the local socket).
     */
    Addresses selfAddresses(DnsSettings s) {
        if (!blank(s.getPublicIpv4()) || !blank(s.getPublicIpv6())) {
            return new Addresses(s.getPublicIpv4(), s.getPublicIpv6());
        }
        return hosts.findAll().stream()
                .filter(
                        h ->
                                h.getDockerHostUrl() != null
                                        && h.getDockerHostUrl().startsWith("unix://"))
                .findFirst()
                .map(h -> new Addresses(h.getPublicIpv4(), h.getPublicIpv6()))
                .orElse(new Addresses(null, null));
    }

    private ZoneSource source(
            DnsZone zone,
            DnsSettings settings,
            Map<String, Boolean> health,
            List<AutoSource> autos,
            Addresses self,
            List<String> warnings,
            List<Generated> generated)
            throws TextParseException {
        String zoneName = zone.getName();
        Name origin = name(zoneName);
        List<RecordSet> sets = new ArrayList<>();
        List<DnsRecord> manual = records.findByZoneId(zone.getId());
        Set<String> manualKeys = new java.util.HashSet<>();
        Set<Name> delegations = new java.util.HashSet<>();
        for (DnsRecord r : manual) {
            Name owner = RecordValues.absolute(r.getName(), zoneName);
            manualKeys.add(owner + "|" + r.getType());
            if (r.getType().equals("NS") && !owner.equals(origin)) delegations.add(owner);
            long ttl = r.getTtl() == null ? zone.getDefaultTtl() : r.getTtl();
            List<Record> recs;
            try {
                recs = RecordValues.parse(owner, r.getType(), ttl, values(r));
            } catch (IllegalArgumentException e) {
                warnings.add(r.getName() + " " + r.getType() + ": " + e.getMessage());
                continue;
            }
            Policy policy =
                    POLICY_WEIGHTED.equals(r.getRoutingPolicy())
                            ? Policy.WEIGHTED
                            : POLICY_FAILOVER.equals(r.getRoutingPolicy())
                                    ? Policy.FAILOVER
                                    : Policy.SIMPLE;
            Boolean healthy =
                    r.getHealthCheckId() == null ? null : health.get(r.getHealthCheckId());
            sets.add(
                    new RecordSet(
                            owner,
                            Type.value(r.getType()),
                            ttl,
                            recs,
                            Kind.MANUAL,
                            policy,
                            r.getSetIdentifier(),
                            r.getWeight() == null ? 1 : r.getWeight(),
                            "PRIMARY".equals(r.getFailoverRole()),
                            healthy,
                            r.getId()));
        }

        // Ingress routes under this zone (but not under a more specific hosted zone).
        if (zone.isAutoRecords()) {
            for (AutoSource a : autos) {
                Name host = name(a.hostname());
                if (!host.subdomain(origin) || !mostSpecificZone(host).equals(origin)) continue;
                String source = "Ingress route on " + a.hostName();
                if (a.addresses().none()) {
                    warnings.add(
                            a.hostname()
                                    + ": the host "
                                    + a.hostName()
                                    + " has no public address yet, so no record points at it. Set"
                                    + " it on the Hosts page.");
                    continue;
                }
                boolean cname = manualKeys.contains(host + "|CNAME");
                boolean delegated = delegations.stream().anyMatch(host::subdomain);
                addAuto(
                        sets,
                        generated,
                        host,
                        "A",
                        a.addresses().ipv4(),
                        Kind.AUTO,
                        source,
                        a.routeId(),
                        cname || delegated || manualKeys.contains(host + "|A"),
                        zoneName);
                addAuto(
                        sets,
                        generated,
                        host,
                        "AAAA",
                        a.addresses().ipv6(),
                        Kind.AUTO,
                        source,
                        a.routeId(),
                        cname || delegated || manualKeys.contains(host + "|AAAA"),
                        zoneName);
            }
        }

        // Nameservers named inside the zone need addresses (glue at the registrar, too).
        List<Name> ns = nameservers(zone, settings);
        for (Name n : ns) {
            if (!n.subdomain(origin) || n.equals(origin)) continue;
            if (self.none()) {
                warnings.add(
                        n.toString(true)
                                + " is a nameserver inside this domain but this server's public"
                                + " address isn't known. Set it in the DNS settings or on its"
                                + " host.");
                continue;
            }
            boolean taken = manualKeys.contains(n + "|CNAME");
            addAuto(
                    sets,
                    generated,
                    n,
                    "A",
                    self.ipv4(),
                    Kind.NAMESERVER,
                    "Nameserver",
                    null,
                    taken || manualKeys.contains(n + "|A"),
                    zoneName);
            addAuto(
                    sets,
                    generated,
                    n,
                    "AAAA",
                    self.ipv6(),
                    Kind.NAMESERVER,
                    "Nameserver",
                    null,
                    taken || manualKeys.contains(n + "|AAAA"),
                    zoneName);
        }

        for (var c : challenges.entrySet()) {
            Name n = c.getKey();
            if (!n.subdomain(origin) || !mostSpecificZone(n).equals(origin)) continue;
            List<Record> txt = new ArrayList<>();
            for (String v : c.getValue()) {
                txt.addAll(RecordValues.parse(n, "TXT", AUTO_TTL, List.of(v)));
            }
            if (txt.isEmpty()) continue;
            sets.add(RecordSet.simple(n, Type.TXT, AUTO_TTL, txt, Kind.CHALLENGE, "acme"));
            generated.add(
                    new Generated(
                            relative(n, zoneName),
                            "TXT",
                            new ArrayList<>(c.getValue()),
                            "CHALLENGE",
                            "Let's Encrypt check",
                            true));
        }

        if (zone.getNameserversJson() == null && ns.isEmpty()) {
            warnings.add("No nameservers are set.");
        }

        ZoneSource.SigningKey key = null;
        if (zone.isDnssecEnabled() && zone.getDnssecPrivateKey() != null) {
            key =
                    DnssecKeys.load(
                            origin,
                            zone.getDnssecPublicKey(),
                            encryption.decrypt(zone.getDnssecPrivateKey()));
        }
        generated.sort(Comparator.comparing(Generated::name).thenComparing(Generated::type));
        return new ZoneSource(
                zone.getId(),
                origin,
                zone.getDefaultTtl(),
                zone.getNegativeTtl(),
                ns,
                hostmaster(settings, zoneName),
                sets,
                key);
    }

    private void addAuto(
            List<RecordSet> sets,
            List<Generated> generated,
            Name owner,
            String type,
            String address,
            Kind kind,
            String source,
            String sourceId,
            boolean overridden,
            String zone) {
        if (blank(address)) return;
        List<Record> recs;
        try {
            recs = RecordValues.parse(owner, type, AUTO_TTL, List.of(address.trim()));
        } catch (IllegalArgumentException e) {
            return;
        }
        sets.add(RecordSet.simple(owner, Type.value(type), AUTO_TTL, recs, kind, sourceId));
        String rel = relative(owner, zone);
        // Several routes may give the same name; list it once per address.
        for (Generated g : generated) {
            if (g.name().equals(rel) && g.type().equals(type) && g.kind().equals(kind.name())) {
                if (!g.values().contains(address.trim())) g.values().add(address.trim());
                return;
            }
        }
        generated.add(
                new Generated(
                        rel,
                        type,
                        new ArrayList<>(List.of(address.trim())),
                        kind.name(),
                        source,
                        !overridden));
    }

    /** The hosted zone a name falls in (longest match), or the root when none. */
    private Name mostSpecificZone(Name n) {
        Name best = Name.root;
        for (DnsZone z : zones.findAll()) {
            Name o = name(z.getName());
            if (n.subdomain(o) && o.labels() > best.labels()) best = o;
        }
        return best;
    }

    /** A record's values as stored. */
    public static List<String> values(DnsRecord r) {
        try {
            List<String> v = JSON.readValue(r.getValuesJson(), STRINGS);
            return v == null ? List.of() : v;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * The zone's nameservers: its own list, else the DNS settings', else ns1 and ns2 of the zone.
     */
    public List<Name> nameservers(DnsZone zone, DnsSettings settings) {
        List<String> names = list(zone.getNameserversJson());
        if (names.isEmpty()) names = list(settings.getNameserversJson());
        if (names.isEmpty()) names = List.of("ns1." + zone.getName(), "ns2." + zone.getName());
        List<Name> out = new ArrayList<>();
        for (String n : names) out.add(name(n));
        return out;
    }

    static Name hostmaster(DnsSettings s, String zone) {
        String email = s.getHostmasterEmail();
        if (blank(email) || !email.contains("@")) return name("hostmaster." + zone);
        String local = email.substring(0, email.indexOf('@')).replace(".", "\\.");
        String domain = email.substring(email.indexOf('@') + 1);
        try {
            return Name.fromString(local + "." + domain + ".");
        } catch (TextParseException e) {
            return name("hostmaster." + zone);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Let's Encrypt DNS-01

    /**
     * Whether this server answers DNS for the hostname (DNS is on and a hosted zone covers it), so
     * a certificate can be checked through DNS.
     */
    public boolean answersFor(String hostname) {
        if (!settings().isEnabled()) return false;
        String h = hostname.toLowerCase(Locale.ROOT).replaceFirst("^\\*\\.", "");
        return QueryHandler.zoneFor(catalog, name(h)) != null;
    }

    /** The zone a hostname falls in, if hosted here. */
    public Optional<DnsZone> zoneOf(String hostname) {
        String h = hostname.toLowerCase(Locale.ROOT).replaceFirst("^\\*\\.", "");
        ZoneView v = QueryHandler.zoneFor(catalog, name(h));
        return v == null ? Optional.empty() : zones.findById(v.id());
    }

    /**
     * Whether certificates for the hostname should be checked through DNS: this server answers for
     * it and the registry points the domain here.
     */
    public boolean canValidate(String hostname) {
        if (!answersFor(hostname)) return false;
        return zoneOf(hostname)
                .map(
                        z ->
                                "OK".equals(z.getDelegationStatus())
                                        || "PARTIAL".equals(z.getDelegationStatus()))
                .orElse(false);
    }

    /**
     * Waits until the zone's other nameservers (secondaries) answer the challenge record. This
     * server has it at once. Returns null when all that answered have it, else what lags.
     */
    public String awaitChallenge(String recordName, String value, java.time.Duration max)
            throws InterruptedException {
        Name rr = name(recordName);
        ZoneView zone = QueryHandler.zoneFor(catalog, rr);
        if (zone == null) return null;
        DnsZone z = zones.findById(zone.id()).orElse(null);
        if (z == null) return null;
        Addresses self = selfAddresses(settings());
        Set<InetAddress> servers = new java.util.LinkedHashSet<>();
        for (Name ns : nameservers(z, settings())) {
            for (int type : new int[] {Type.A, Type.AAAA}) {
                try {
                    org.xbill.DNS.Lookup l = new org.xbill.DNS.Lookup(ns, type);
                    l.setCache(null);
                    Record[] found = l.run();
                    if (found == null) continue;
                    for (Record r : found) {
                        InetAddress a =
                                r instanceof org.xbill.DNS.ARecord ar
                                        ? ar.getAddress()
                                        : ((org.xbill.DNS.AAAARecord) r).getAddress();
                        String ip = a.getHostAddress();
                        if (ip.equals(self.ipv4())
                                || ip.equalsIgnoreCase(String.valueOf(self.ipv6()))) {
                            continue;
                        }
                        servers.add(a);
                    }
                } catch (Exception ignored) {
                    // A nameserver that can't be found is reported by the delegation check.
                }
            }
        }
        if (servers.isEmpty()) return null;
        long deadline = System.nanoTime() + max.toNanos();
        String expected = "\"" + value + "\"";
        List<String> pending;
        do {
            pending = new ArrayList<>();
            for (InetAddress server : servers) {
                try {
                    org.xbill.DNS.SimpleResolver res = new org.xbill.DNS.SimpleResolver(server);
                    res.setTimeout(java.time.Duration.ofSeconds(3));
                    Message q = Message.newQuery(Record.newRecord(rr, Type.TXT, DClass.IN));
                    q.getHeader().unsetFlag(Flags.RD);
                    Message a = res.send(q);
                    boolean has =
                            a.getSection(Section.ANSWER).stream()
                                    .anyMatch(
                                            x ->
                                                    x.getType() == Type.TXT
                                                            && x.rdataToString()
                                                                    .contains(expected));
                    if (!has) pending.add(server.getHostAddress());
                } catch (Exception e) {
                    // Not reachable from here (or not at all): Let's Encrypt will tell.
                }
            }
            if (pending.isEmpty()) return null;
            Thread.sleep(5000);
        } while (System.nanoTime() < deadline);
        return "These nameservers still didn't have the check record: "
                + String.join(", ", pending)
                + ". Are they copying the zone from this server (zone transfers in the DNS"
                + " settings)?";
    }

    /** Publishes a challenge TXT record and waits until it is answered. */
    public void addChallenge(String recordName, String value) {
        challenges.computeIfAbsent(name(recordName), k -> ConcurrentHashMap.newKeySet()).add(value);
        rebuildNow();
    }

    public void removeChallenge(String recordName, String value) {
        Name n = name(recordName);
        Set<String> v = challenges.get(n);
        if (v != null) {
            v.remove(value);
            if (v.isEmpty()) challenges.remove(n);
        }
        requestRebuild();
    }

    // ------------------------------------------------------------------------------------------
    // Settings

    public synchronized DnsSettings settings() {
        return settingsRepo.findAll().stream()
                .findFirst()
                .orElseGet(
                        () -> {
                            DnsSettings s = new DnsSettings();
                            s.setNameserversJson("[]");
                            s.setTransferAllowedJson("[]");
                            return settingsRepo.save(s);
                        });
    }

    public record SettingsRequest(
            Boolean enabled,
            List<String> nameservers,
            String publicIpv4,
            String publicIpv6,
            String hostmasterEmail,
            List<String> transferAllowed,
            Integer rateLimit) {}

    public DnsSettings updateSettings(SettingsRequest req) {
        DnsSettings s = settings();
        if (req.nameservers() != null) {
            List<String> ns = new ArrayList<>();
            for (String n : req.nameservers()) {
                if (blank(n)) continue;
                ns.add(hostname(n, "Nameserver"));
            }
            if (ns.size() > 13) throw bad("At most 13 nameservers");
            s.setNameserversJson(json(new ArrayList<>(new LinkedHashSet<>(ns))));
        }
        if (req.publicIpv4() != null) s.setPublicIpv4(ipv4(req.publicIpv4()));
        if (req.publicIpv6() != null) s.setPublicIpv6(ipv6(req.publicIpv6()));
        if (req.hostmasterEmail() != null) {
            String e = req.hostmasterEmail().trim();
            if (!e.isEmpty() && !e.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
                throw bad("Enter an email address for the zone contact, or leave it empty");
            }
            s.setHostmasterEmail(e.isEmpty() ? null : e);
        }
        if (req.transferAllowed() != null) {
            List<String> allowed = new ArrayList<>();
            for (String a : req.transferAllowed()) {
                if (blank(a)) continue;
                String v = a.trim();
                if (Cidr.parse(v) == null) {
                    throw bad(
                            "Zone transfers: \"" + v + "\" is not an IP address or network (CIDR)");
                }
                allowed.add(v);
            }
            s.setTransferAllowedJson(json(allowed));
        }
        if (req.rateLimit() != null) {
            if (req.rateLimit() < 0 || req.rateLimit() > 100_000) {
                throw bad("Rate limit: 0 (off) to 100000 answers per second");
            }
            s.setRateLimit(req.rateLimit());
        }
        if (req.enabled() != null) s.setEnabled(req.enabled());
        s = settingsRepo.save(s);
        requestRebuild();
        return s;
    }

    /** Whether a secondary at this address may copy zones. */
    public boolean transferAllowed(InetAddress from) {
        for (String a : list(settings().getTransferAllowedJson())) {
            Cidr c = Cidr.parse(a);
            if (c != null && c.contains(from)) return true;
        }
        return false;
    }

    private void notifySecondaries(DnsZone zone, DnsSettings settings) {
        if (!settings.isEnabled()) return;
        List<InetAddress> targets = new ArrayList<>();
        for (String a : list(settings.getTransferAllowedJson())) {
            Cidr c = Cidr.parse(a);
            if (c != null && c.single()) targets.add(c.address());
        }
        if (targets.isEmpty()) return;
        Thread.ofVirtual()
                .start(
                        () -> {
                            try (DatagramSocket socket = new DatagramSocket()) {
                                Name origin = name(zone.getName());
                                Message m = new Message();
                                m.getHeader().setOpcode(Opcode.NOTIFY);
                                m.getHeader().setFlag(Flags.AA);
                                m.addRecord(
                                        Record.newRecord(origin, Type.SOA, DClass.IN),
                                        Section.QUESTION);
                                byte[] wire = m.toWire();
                                for (InetAddress t : targets) {
                                    socket.send(
                                            new DatagramPacket(
                                                    wire,
                                                    wire.length,
                                                    new InetSocketAddress(t, 53)));
                                }
                            } catch (Exception e) {
                                log.debug(
                                        "NOTIFY for {} failed: {}", zone.getName(), e.getMessage());
                            }
                        });
    }

    // ------------------------------------------------------------------------------------------
    // Zones

    public List<DnsZone> listZones() {
        List<DnsZone> all = new ArrayList<>(zones.findAll());
        all.sort(Comparator.comparing(DnsZone::getName));
        return all;
    }

    public DnsZone zone(String id) {
        return zones.findById(id)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Zone not found"));
    }

    public Optional<DnsZone> zoneByName(String name) {
        return zones.findByName(name.toLowerCase(Locale.ROOT).replaceFirst("\\.$", ""));
    }

    public record ZoneRequest(
            String name,
            String comment,
            Integer defaultTtl,
            Integer negativeTtl,
            List<String> nameservers,
            Boolean autoRecords,
            Boolean dnssec) {}

    public DnsZone createZone(ZoneRequest req) {
        String name;
        try {
            name = RecordValues.zoneName(req.name());
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        if (zones.findByName(name).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, name + " is already a hosted zone");
        }
        DnsZone z = new DnsZone();
        z.setName(name);
        z.setSerial(0);
        apply(z, req);
        z = zones.save(z);
        rebuildNow();
        return z;
    }

    public DnsZone updateZone(String id, ZoneRequest req) {
        DnsZone z = zone(id);
        if (req.name() != null && !blank(req.name())) {
            String n;
            try {
                n = RecordValues.zoneName(req.name());
            } catch (IllegalArgumentException e) {
                throw bad(e.getMessage());
            }
            if (!n.equals(z.getName()))
                throw bad("A zone can't be renamed. Add a new zone instead.");
        }
        apply(z, req);
        z = zones.save(z);
        rebuildNow();
        return z;
    }

    private void apply(DnsZone z, ZoneRequest req) {
        if (req.comment() != null) z.setComment(blank(req.comment()) ? null : req.comment().trim());
        if (req.defaultTtl() != null) z.setDefaultTtl(ttl(req.defaultTtl(), "Default TTL"));
        if (req.negativeTtl() != null) {
            if (req.negativeTtl() < 0 || req.negativeTtl() > 86400) {
                throw bad("Negative answer TTL: 0 to 86400 seconds");
            }
            z.setNegativeTtl(req.negativeTtl());
        }
        if (req.nameservers() != null) {
            List<String> ns = new ArrayList<>();
            for (String n : req.nameservers()) if (!blank(n)) ns.add(hostname(n, "Nameserver"));
            z.setNameserversJson(
                    ns.isEmpty() ? null : json(new ArrayList<>(new LinkedHashSet<>(ns))));
        }
        if (req.autoRecords() != null) z.setAutoRecords(req.autoRecords());
        if (req.dnssec() != null) {
            if (req.dnssec() && z.getDnssecPrivateKey() == null) {
                KeyPair kp = DnssecKeys.generate();
                z.setDnssecPrivateKey(
                        encryption.encrypt(DnssecKeys.encodePrivate(kp.getPrivate())));
                z.setDnssecPublicKey(DnssecKeys.encodePublic(kp.getPublic()));
                z.setDnssecKeyCreatedAt(System.currentTimeMillis());
            }
            z.setDnssecEnabled(req.dnssec());
        }
    }

    public void deleteZone(String id) {
        DnsZone z = zone(id);
        records.deleteByZoneId(z.getId());
        zones.delete(z);
        rebuildNow();
    }

    /** The DS record for the registrar, when the zone is signed. */
    public Optional<DSRecord> ds(DnsZone z) {
        if (z.getDnssecPublicKey() == null || !z.isDnssecEnabled()) return Optional.empty();
        try {
            var pub =
                    java.security.KeyFactory.getInstance("EC")
                            .generatePublic(
                                    new java.security.spec.X509EncodedKeySpec(
                                            java.util.Base64.getDecoder()
                                                    .decode(z.getDnssecPublicKey())));
            return Optional.of(DnssecKeys.ds(DnssecKeys.dnskey(name(z.getName()), pub)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Records

    public List<DnsRecord> records(String zoneId) {
        List<DnsRecord> all = new ArrayList<>(records.findByZoneId(zoneId));
        all.sort(
                Comparator.comparing((DnsRecord r) -> r.getName().equals("@") ? "" : r.getName())
                        .thenComparing(DnsRecord::getType)
                        .thenComparing(
                                r -> r.getSetIdentifier() == null ? "" : r.getSetIdentifier()));
        return all;
    }

    public DnsRecord record(String zoneId, String id) {
        DnsRecord r = records.findById(id).orElse(null);
        if (r == null || !r.getZoneId().equals(zoneId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found");
        }
        return r;
    }

    public record RecordRequest(
            String name,
            String type,
            Integer ttl,
            List<String> values,
            String routingPolicy,
            String setIdentifier,
            Integer weight,
            String failoverRole,
            String healthCheckId,
            String comment) {}

    public DnsRecord createRecord(String zoneId, RecordRequest req) {
        DnsZone z = zone(zoneId);
        DnsRecord r = new DnsRecord();
        r.setZoneId(z.getId());
        validateInto(z, r, req);
        r = records.save(r);
        rebuildNow();
        return r;
    }

    public DnsRecord updateRecord(String zoneId, String id, RecordRequest req) {
        DnsZone z = zone(zoneId);
        DnsRecord existing = record(zoneId, id);
        // Checked on a copy: a failed check must not leave a half-changed entity to be flushed.
        DnsRecord copy = new DnsRecord();
        org.springframework.beans.BeanUtils.copyProperties(existing, copy);
        validateInto(z, copy, req);
        org.springframework.beans.BeanUtils.copyProperties(copy, existing);
        DnsRecord saved = records.save(existing);
        rebuildNow();
        return saved;
    }

    public void deleteRecord(String zoneId, String id) {
        records.delete(record(zoneId, id));
        rebuildNow();
    }

    /** Checks a record request against the zone's other records and fills in the record. */
    void validateInto(DnsZone z, DnsRecord r, RecordRequest req) {
        String name;
        try {
            name = RecordValues.relativeName(req.name(), z.getName());
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        String type = req.type() == null ? "" : req.type().trim().toUpperCase(Locale.ROOT);
        if (!RecordValues.supported(type)) {
            throw bad("Type: choose one of " + String.join(", ", RecordValues.TYPES));
        }
        if (type.equals("NS") && name.equals("@")) {
            throw bad(
                    "The zone's own nameservers are set on the zone (Settings), not as a record.");
        }
        if (type.equals("CNAME") && name.equals("@")) {
            throw bad(
                    "A CNAME can't be at the zone's own name (DNS doesn't allow it). Use A/AAAA"
                            + " records.");
        }
        if (type.equals("DS") && name.equals("@")) {
            throw bad("The zone's own DS record goes to your registrar, not into the zone.");
        }
        int ttl = req.ttl() == null ? z.getDefaultTtl() : ttl(req.ttl(), "TTL");
        List<String> values = new ArrayList<>();
        if (req.values() != null)
            for (String v : req.values()) if (!blank(v)) values.add(v.strip());
        Name owner = RecordValues.absolute(name, z.getName());
        try {
            RecordValues.parse(owner, type, ttl, values);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }

        String policy =
                req.routingPolicy() == null
                        ? ""
                        : req.routingPolicy().trim().toUpperCase(Locale.ROOT);
        if (policy.isEmpty() || policy.equals("SIMPLE")) policy = null;
        else if (!policy.equals(POLICY_WEIGHTED) && !policy.equals(POLICY_FAILOVER)) {
            throw bad("Routing: simple, weighted or failover");
        }
        String setId = blank(req.setIdentifier()) ? null : req.setIdentifier().trim();
        Integer weight = null;
        String role = null;
        if (policy != null) {
            if (type.equals("NS") || type.equals("DS"))
                throw bad(type + " records use simple routing");
            if (setId == null)
                throw bad(
                        "Give this record set an identifier (such as \"server-a\") to tell it"
                                + " apart");
            if (setId.length() > 64) throw bad("Identifier: at most 64 characters");
        } else {
            setId = null;
        }
        if (POLICY_WEIGHTED.equals(policy)) {
            weight = req.weight() == null ? 1 : req.weight();
            if (weight < 0 || weight > 255) throw bad("Weight: 0 to 255");
        }
        if (POLICY_FAILOVER.equals(policy)) {
            role =
                    req.failoverRole() == null
                            ? ""
                            : req.failoverRole().trim().toUpperCase(Locale.ROOT);
            if (!role.equals("PRIMARY") && !role.equals("SECONDARY"))
                throw bad("Failover: primary or secondary");
        }
        String check = blank(req.healthCheckId()) ? null : req.healthCheckId().trim();
        if (check != null && healthChecks.findById(check).isEmpty())
            throw bad("Health check not found");
        if (check != null && policy == null) {
            throw bad(
                    "Health checks are for weighted and failover record sets: choose one of those"
                            + " routings");
        }
        if ("PRIMARY".equals(role) && check == null) {
            throw bad(
                    "The primary record set of a failover needs a health check, to know when to"
                            + " fail over");
        }

        for (DnsRecord o : records.findByZoneId(z.getId())) {
            if (o.getId().equals(r.getId()) || !o.getName().equals(name)) continue;
            String ot = o.getType();
            if (type.equals("CNAME") != ot.equals("CNAME")) {
                throw bad(
                        "\""
                                + display(name, z.getName())
                                + "\" already has "
                                + (ot.equals("CNAME") ? "a CNAME record" : "other records")
                                + "; DNS doesn't allow a CNAME next to other records at the same"
                                + " name.");
            }
            boolean delegationHere = ot.equals("NS") || type.equals("NS");
            if (delegationHere && !Set.of("NS", "DS").containsAll(List.of(ot, type))) {
                throw bad(
                        "\""
                                + display(name, z.getName())
                                + "\" is delegated to other nameservers (NS); only DS records can"
                                + " sit next to them.");
            }
            if (!ot.equals(type)) continue;
            String op = o.getRoutingPolicy();
            if (!java.util.Objects.equals(op, policy)) {
                throw bad(
                        "Other "
                                + type
                                + " records for this name use "
                                + (op == null ? "simple" : op.toLowerCase(Locale.ROOT))
                                + " routing; all of them must use the same.");
            }
            if (policy == null) {
                throw bad(
                        "This name already has a "
                                + type
                                + " record set. Add the values to it instead.");
            }
            if (setId.equalsIgnoreCase(o.getSetIdentifier()))
                throw bad("Another record set here uses the identifier \"" + setId + "\"");
            if (role != null && role.equals(o.getFailoverRole())) {
                throw bad(
                        "This name already has a " + role.toLowerCase(Locale.ROOT) + " record set");
            }
        }
        if (type.equals("DS")
                && records.findByZoneId(z.getId()).stream()
                        .noneMatch(o -> o.getName().equals(name) && o.getType().equals("NS"))) {
            throw bad(
                    "A DS record belongs next to the NS records that delegate the name. Add those"
                            + " first.");
        }

        r.setName(name);
        r.setType(type);
        r.setTtl(req.ttl() == null ? null : ttl);
        r.setValuesJson(json(values));
        r.setRoutingPolicy(policy);
        r.setSetIdentifier(setId);
        r.setWeight(weight);
        r.setFailoverRole(role);
        r.setHealthCheckId(check);
        r.setComment(blank(req.comment()) ? null : req.comment().trim());
    }

    static String display(String relative, String zone) {
        return relative.equals("@") ? zone : relative + "." + zone;
    }

    // ------------------------------------------------------------------------------------------
    // Zone files

    /** What the zone answers now, as a zone file (DNSSEC records left out). */
    public String exportZone(String zoneId) {
        DnsZone z = zone(zoneId);
        ZoneView v = catalog.get(name(z.getName()));
        StringBuilder sb = new StringBuilder();
        sb.append("; ").append(z.getName()).append(" as answered by Docker Manager\n");
        sb.append("$ORIGIN ").append(z.getName()).append(".\n");
        if (v == null) return sb.append("; not built yet\n").toString();
        for (var set : v.allSets()) {
            int t = set.getType();
            if (t == Type.NSEC || t == Type.DNSKEY || t == Type.RRSIG) continue;
            for (Record r : set.rrs(false)) sb.append(r).append('\n');
        }
        return sb.toString();
    }

    public record ImportResult(int added, int merged, List<String> skipped) {}

    /**
     * Adds the records of a zone file (BIND format, as exported by Route 53, Cloudflare and most
     * DNS hosts). Records the zone sets itself (SOA, its own NS, DNSSEC) are skipped; values join
     * an existing simple record set of the same name and type.
     */
    public ImportResult importZone(String zoneId, String text) {
        DnsZone z = zone(zoneId);
        if (text == null || text.isBlank()) throw bad("Paste the zone file");
        if (text.length() > 1_000_000) throw bad("The zone file is too large (1 MB at most)");
        Name origin = name(z.getName());
        Map<String, List<Record>> groups = new java.util.LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        try (org.xbill.DNS.Master m =
                new org.xbill.DNS.Master(
                        new java.io.ByteArrayInputStream(
                                text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        origin,
                        z.getDefaultTtl())) {
            // A zone file must not read other files of this server.
            m.disableIncludes(true);
            m.expandGenerate(false);
            Record r;
            int count = 0;
            while ((r = m.nextRecord()) != null) {
                if (++count > 5000) throw bad("The zone file has more than 5000 records");
                if (!r.getName().subdomain(origin)) {
                    skipped.add(r.getName() + " is not in " + z.getName());
                    continue;
                }
                String type = Type.string(r.getType());
                if (r.getType() == Type.SOA
                        || (r.getType() == Type.NS && r.getName().equals(origin))
                        || Set.of(
                                        "RRSIG",
                                        "NSEC",
                                        "NSEC3",
                                        "NSEC3PARAM",
                                        "DNSKEY",
                                        "CDS",
                                        "CDNSKEY")
                                .contains(type)) {
                    continue;
                }
                if (!RecordValues.supported(type)) {
                    skipped.add(
                            relative(r.getName(), z.getName())
                                    + " "
                                    + type
                                    + ": this type isn't supported");
                    continue;
                }
                groups.computeIfAbsent(
                                relative(r.getName(), z.getName()) + "|" + type,
                                k -> new ArrayList<>())
                        .add(r);
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw bad("The zone file can't be read: " + e.getMessage());
        }
        int added = 0, merged = 0;
        for (var g : groups.entrySet()) {
            String rel = g.getKey().substring(0, g.getKey().indexOf('|'));
            String type = g.getKey().substring(g.getKey().indexOf('|') + 1);
            List<String> values = new ArrayList<>();
            for (Record r : g.getValue()) values.add(r.rdataToString());
            long ttl =
                    g.getValue().stream().mapToLong(Record::getTTL).min().orElse(z.getDefaultTtl());
            DnsRecord existing =
                    records.findByZoneId(z.getId()).stream()
                            .filter(
                                    o ->
                                            o.getName().equals(rel)
                                                    && o.getType().equals(type)
                                                    && o.getRoutingPolicy() == null)
                            .findFirst()
                            .orElse(null);
            try {
                if (existing != null) {
                    List<String> all = new ArrayList<>(values(existing));
                    for (String v : values) if (!all.contains(v)) all.add(v);
                    DnsRecord copy = new DnsRecord();
                    org.springframework.beans.BeanUtils.copyProperties(existing, copy);
                    validateInto(
                            z,
                            copy,
                            new RecordRequest(
                                    rel,
                                    type,
                                    existing.getTtl(),
                                    all,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    existing.getComment()));
                    org.springframework.beans.BeanUtils.copyProperties(copy, existing);
                    records.save(existing);
                    merged++;
                } else {
                    DnsRecord r = new DnsRecord();
                    r.setZoneId(z.getId());
                    validateInto(
                            z,
                            r,
                            new RecordRequest(
                                    rel,
                                    type,
                                    (int) Math.min(ttl, 604800),
                                    values,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null));
                    records.save(r);
                    added++;
                }
            } catch (ResponseStatusException e) {
                skipped.add(
                        DnsService.display(rel, z.getName()) + " " + type + ": " + e.getReason());
            }
        }
        rebuildNow();
        return new ImportResult(added, merged, skipped);
    }

    /** Asks this server a question the way a resolver would, for the "test" tool. */
    public Message ask(String name, String type, boolean dnssec) {
        int t = Type.value(type == null ? "A" : type.trim().toUpperCase(Locale.ROOT));
        if (t < 0) throw bad("Unknown record type");
        Name n;
        try {
            n = Name.fromString(name.trim().endsWith(".") ? name.trim() : name.trim() + ".");
        } catch (TextParseException e) {
            throw bad("Enter a name, such as www.example.com");
        }
        Message q = Message.newQuery(Record.newRecord(n, t, DClass.IN));
        q.getHeader().unsetFlag(Flags.RD);
        if (dnssec)
            q.addRecord(
                    new org.xbill.DNS.OPTRecord(1232, 0, 0, org.xbill.DNS.ExtendedFlags.DO),
                    Section.ADDITIONAL);
        return new QueryHandler(this::catalog).answer(q);
    }

    // ------------------------------------------------------------------------------------------
    // Health checks

    public List<DnsHealthCheck> listHealthChecks() {
        List<DnsHealthCheck> all = new ArrayList<>(healthChecks.findAll());
        all.sort(Comparator.comparing(DnsHealthCheck::getName));
        return all;
    }

    public DnsHealthCheck healthCheck(String id) {
        return healthChecks
                .findById(id)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Health check not found"));
    }

    public record HealthCheckRequest(
            String name,
            String type,
            String address,
            Integer port,
            String path,
            String hostHeader,
            Integer intervalSeconds,
            Integer timeoutSeconds,
            Integer failureThreshold,
            @com.fasterxml.jackson.annotation.JsonProperty("notify") Boolean notifyAdmins,
            Boolean enabled) {}

    public DnsHealthCheck saveHealthCheck(String id, HealthCheckRequest req) {
        DnsHealthCheck c = id == null ? new DnsHealthCheck() : healthCheck(id);
        DnsHealthCheck copy = new DnsHealthCheck();
        org.springframework.beans.BeanUtils.copyProperties(c, copy);
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty() || name.length() > 64) throw bad("Name: 1 to 64 characters");
        healthChecks
                .findByName(name)
                .filter(o -> !o.getId().equals(c.getId()))
                .ifPresent(
                        o -> {
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT, "Another health check is called " + name);
                        });
        copy.setName(name);
        String type = req.type() == null ? "HTTP" : req.type().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("HTTP", "HTTPS", "TCP").contains(type)) throw bad("Type: HTTP, HTTPS or TCP");
        copy.setType(type);
        String address = req.address() == null ? "" : req.address().trim();
        if (address.isEmpty()
                || address.length() > 253
                || !address.matches("^[A-Za-z0-9.:\\-\\[\\]]+$")) {
            throw bad("Address: an IP address or hostname to check");
        }
        copy.setAddress(address.replaceAll("^\\[|\\]$", ""));
        int port = req.port() == null ? (type.equals("HTTPS") ? 443 : 80) : req.port();
        if (port < 1 || port > 65535) throw bad("Port: 1 to 65535");
        copy.setPort(port);
        String path = blank(req.path()) ? "/" : req.path().trim();
        if (!path.startsWith("/") || path.length() > 512 || path.matches(".*[\\s].*"))
            throw bad("Path: starts with /, no spaces");
        copy.setPath(path);
        String hh = blank(req.hostHeader()) ? null : req.hostHeader().trim();
        if (hh != null && !hh.matches("^[A-Za-z0-9.\\-]{1,253}$"))
            throw bad("Host name: a hostname such as app.example.com");
        copy.setHostHeader(hh);
        int interval = req.intervalSeconds() == null ? 30 : req.intervalSeconds();
        if (interval < 10 || interval > 3600) throw bad("Interval: 10 to 3600 seconds");
        copy.setIntervalSeconds(interval);
        int timeout = req.timeoutSeconds() == null ? 5 : req.timeoutSeconds();
        if (timeout < 1 || timeout > 30 || timeout >= interval)
            throw bad("Timeout: 1 to 30 seconds, shorter than the interval");
        copy.setTimeoutSeconds(timeout);
        int threshold = req.failureThreshold() == null ? 3 : req.failureThreshold();
        if (threshold < 1 || threshold > 10) throw bad("Failures before unhealthy: 1 to 10");
        copy.setFailureThreshold(threshold);
        if (req.notifyAdmins() != null) copy.setNotify(req.notifyAdmins());
        if (req.enabled() != null) copy.setEnabled(req.enabled());
        boolean targetChanged =
                id == null
                        || !java.util.Objects.equals(c.getAddress(), copy.getAddress())
                        || c.getPort() != copy.getPort()
                        || !java.util.Objects.equals(c.getType(), copy.getType())
                        || !java.util.Objects.equals(c.getPath(), copy.getPath());
        if (targetChanged) copy.setStatus("UNKNOWN");
        org.springframework.beans.BeanUtils.copyProperties(copy, c);
        DnsHealthCheck saved = healthChecks.save(c);
        requestRebuild();
        return saved;
    }

    public void deleteHealthCheck(String id) {
        DnsHealthCheck c = healthCheck(id);
        List<DnsRecord> users = records.findByHealthCheckId(id);
        if (!users.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Record sets use this health check ("
                            + users.size()
                            + "). Remove it from them first.");
        }
        healthChecks.delete(c);
        requestRebuild();
    }

    // ------------------------------------------------------------------------------------------
    // Views

    /** An automatic record, for the zone page. */
    public record Generated(
            String name,
            String type,
            List<String> values,
            String kind,
            String source,
            boolean active) {}

    public record ZoneInfo(List<Generated> generated, List<String> warnings) {}

    // ------------------------------------------------------------------------------------------
    // Helpers

    static Name name(String s) {
        try {
            String n = s.endsWith(".") ? s : s + ".";
            return Name.fromString(n.toLowerCase(Locale.ROOT));
        } catch (TextParseException e) {
            throw new IllegalArgumentException("Not a DNS name: " + s);
        }
    }

    static String relative(Name n, String zone) {
        String abs = n.toString(true).toLowerCase(Locale.ROOT);
        if (abs.equals(zone)) return "@";
        return abs.endsWith("." + zone) ? abs.substring(0, abs.length() - zone.length() - 1) : abs;
    }

    public static List<String> listOf(String json) {
        return list(json);
    }

    static List<String> list(String json) {
        if (blank(json)) return List.of();
        try {
            List<String> v = JSON.readValue(json, STRINGS);
            return v == null ? List.of() : v;
        } catch (Exception e) {
            return List.of();
        }
    }

    static String json(List<String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hostname(String raw, String what) {
        String n = raw.trim().toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        if (!n.matches("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$")) {
            throw bad(what + ": \"" + raw.trim() + "\" is not a hostname");
        }
        return n;
    }

    static int ttl(int ttl, String what) {
        if (ttl < 0 || ttl > 604800) throw bad(what + ": 0 to 604800 seconds (a week)");
        return ttl;
    }

    public static String ipv4(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) return null;
        if (!v.matches("^\\d{1,3}(\\.\\d{1,3}){3}$"))
            throw bad("\"" + v + "\" is not an IPv4 address");
        for (String part : v.split("\\."))
            if (Integer.parseInt(part) > 255) throw bad("\"" + v + "\" is not an IPv4 address");
        return v;
    }

    public static String ipv6(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) return null;
        if (!v.contains(":") || !v.matches("^[0-9a-fA-F:.]+$"))
            throw bad("\"" + v + "\" is not an IPv6 address");
        try {
            // A literal: no lookup happens for text made of hex digits, colons and dots.
            InetAddress a = InetAddress.getByName(v);
            if (!(a instanceof java.net.Inet6Address))
                throw bad("\"" + v + "\" is not an IPv6 address");
            return v.toLowerCase(Locale.ROOT);
        } catch (java.net.UnknownHostException e) {
            throw bad("\"" + v + "\" is not an IPv6 address");
        }
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

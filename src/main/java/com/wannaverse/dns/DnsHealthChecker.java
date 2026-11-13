package com.wannaverse.dns;

import com.wannaverse.persistence.DnsHealthCheck;
import com.wannaverse.persistence.DnsHealthCheckRepository;
import com.wannaverse.service.NotificationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Runs the DNS health checks. A check turns unhealthy after its threshold of failures in a row and
 * healthy again after as many successes; record sets that use it follow (the zones are rebuilt).
 *
 * <p>HTTP and HTTPS checks pass with a 2xx or 3xx status, as Route 53's do. HTTPS checks don't
 * verify the certificate (Route 53's don't either): they check that the site answers.
 */
@Component
public class DnsHealthChecker {
    private static final Logger log = LoggerFactory.getLogger(DnsHealthChecker.class);

    private final DnsHealthCheckRepository repo;
    private final DnsService dns;
    private final NotificationService notifications;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    /** Per check: when it is due next, and its run of results. */
    private final Map<String, State> states = new ConcurrentHashMap<>();

    private static final class State {
        long due;
        int failures;
        int successes;
        boolean running;
    }

    public DnsHealthChecker(
            DnsHealthCheckRepository repo, DnsService dns, NotificationService notifications) {
        this.repo = repo;
        this.dns = dns;
        this.notifications = notifications;
    }

    @Scheduled(initialDelay = 20_000, fixedDelay = 2_000)
    public void tick() {
        List<DnsHealthCheck> checks = repo.findAll();
        long now = System.currentTimeMillis();
        states.keySet().retainAll(checks.stream().map(DnsHealthCheck::getId).toList());
        for (DnsHealthCheck c : checks) {
            if (!c.isEnabled()) continue;
            State st = states.computeIfAbsent(c.getId(), k -> new State());
            synchronized (st) {
                if (st.running || st.due > now) continue;
                st.running = true;
                st.due = now + c.getIntervalSeconds() * 1000L;
            }
            workers.execute(() -> run(c, st));
        }
    }

    /** Runs a check now (the "Check now" button) and returns what it found. */
    public Result checkNow(DnsHealthCheck c) {
        Result r = probe(c);
        State st = states.computeIfAbsent(c.getId(), k -> new State());
        record(c, st, r);
        return r;
    }

    private void run(DnsHealthCheck c, State st) {
        try {
            record(c, st, probe(c));
        } catch (Exception e) {
            log.warn("Health check {} failed to run: {}", c.getName(), e.getMessage());
        } finally {
            synchronized (st) {
                st.running = false;
            }
        }
    }

    private void record(DnsHealthCheck c, State st, Result r) {
        String before = c.getStatus() == null ? "UNKNOWN" : c.getStatus();
        String after = before;
        synchronized (st) {
            if (r.ok()) {
                st.successes++;
                st.failures = 0;
                if (!before.equals("HEALTHY")
                        && (before.equals("UNKNOWN") || st.successes >= c.getFailureThreshold())) {
                    after = "HEALTHY";
                }
            } else {
                st.failures++;
                st.successes = 0;
                if (!before.equals("UNHEALTHY") && st.failures >= c.getFailureThreshold()) {
                    after = "UNHEALTHY";
                }
            }
        }
        long now = System.currentTimeMillis();
        boolean changed = !after.equals(before);
        // Only the result columns: the check's settings may be edited at the same time.
        repo.updateResult(
                c.getId(),
                after,
                r.detail(),
                now,
                changed ? Long.valueOf(now) : c.getLastChangedAt());
        c.setStatus(after);
        if (!changed) return;
        log.info("DNS health check {} is now {} ({})", c.getName(), after, r.detail());
        dns.requestRebuild();
        if (c.isNotify() && !before.equals("UNKNOWN")) {
            notifications.notifyDnsHealthCheckChanged(c, after.equals("HEALTHY"), r.detail());
        }
    }

    public record Result(boolean ok, String detail) {}

    static Result probe(DnsHealthCheck c) {
        int timeout = c.getTimeoutSeconds() * 1000;
        long start = System.nanoTime();
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(c.getAddress(), c.getPort()), timeout);
            raw.setSoTimeout(timeout);
            if (c.getType().equals("TCP")) {
                return new Result(true, "Connected in " + ms(start) + " ms");
            }
            Socket s = raw;
            String host = c.getHostHeader() == null ? c.getAddress() : c.getHostHeader();
            if (c.getType().equals("HTTPS")) {
                SSLSocket tls =
                        (SSLSocket)
                                TRUST_ALL
                                        .getSocketFactory()
                                        .createSocket(raw, host, c.getPort(), true);
                SSLParameters params = tls.getSSLParameters();
                if (host.matches(".*[a-zA-Z].*") && !host.contains(":")) {
                    params.setServerNames(List.of(new SNIHostName(host)));
                }
                tls.setSSLParameters(params);
                tls.startHandshake();
                s = tls;
            }
            String hostHeader = host.contains(":") ? "[" + host + "]" : host;
            String req =
                    "GET "
                            + c.getPath()
                            + " HTTP/1.1\r\nHost: "
                            + hostHeader
                            + "\r\nUser-Agent: DockerManager-HealthCheck\r\nAccept: */*\r\n"
                            + "Connection: close\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in =
                    new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String line = in.readLine();
            if (line == null || !line.startsWith("HTTP/")) {
                return new Result(false, "Not an HTTP answer");
            }
            String[] parts = line.split(" ", 3);
            int status = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            boolean ok = status >= 200 && status < 400;
            return new Result(ok, "HTTP " + status + " in " + ms(start) + " ms");
        } catch (java.net.SocketTimeoutException e) {
            return new Result(false, "No answer within " + c.getTimeoutSeconds() + " s");
        } catch (java.net.UnknownHostException e) {
            return new Result(false, "Unknown host " + c.getAddress());
        } catch (java.net.ConnectException e) {
            return new Result(false, "Connection refused");
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Result(false, m.length() > 200 ? m.substring(0, 200) : m);
        }
    }

    private static long ms(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static final SSLContext TRUST_ALL = trustAll();

    private static SSLContext trustAll() {
        try {
            TrustManager tm =
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {tm}, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

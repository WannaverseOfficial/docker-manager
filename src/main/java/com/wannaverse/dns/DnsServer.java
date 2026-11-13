package com.wannaverse.dns;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.RRSIGRecord;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The DNS server: UDP and TCP on {@code DNS_PORT} (1053 by default; publish it as port 53). It runs
 * while DNS is turned on in the settings.
 */
@Component
public class DnsServer {
    private static final Logger log = LoggerFactory.getLogger(DnsServer.class);

    /** Open TCP connections at most; more wait in the accept queue. */
    private static final int MAX_TCP = 256;

    private static final int TCP_IDLE_MS = 10_000;

    private final DnsService dns;
    private final QueryHandler handler;
    private final int port;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore tcpSlots = new Semaphore(MAX_TCP);
    private final Map<InetAddress, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong queries = new AtomicLong();
    private final AtomicLong limited = new AtomicLong();

    private volatile DatagramSocket udp;
    private volatile ServerSocket tcp;
    private volatile String lastError;
    private volatile Long startedAt;
    private volatile int rateLimit = 100;

    public DnsServer(DnsService dns, @Value("${dns.port:1053}") int port) {
        this.dns = dns;
        this.port = port;
        this.handler = new QueryHandler(dns::catalog);
    }

    public record Status(
            boolean running,
            int port,
            Long startedAt,
            long queries,
            long rateLimited,
            String error) {}

    public Status status() {
        return new Status(udp != null, port, startedAt, queries.get(), limited.get(), lastError);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        sync();
    }

    /** Starts or stops the server to match the settings. */
    @Scheduled(initialDelay = 30_000, fixedDelay = 15_000)
    public synchronized void sync() {
        var settings = dns.settings();
        rateLimit = settings.getRateLimit();
        boolean running = udp != null;
        if (settings.isEnabled() && !running) start();
        else if (!settings.isEnabled() && running) stop();
    }

    private void start() {
        DatagramSocket u = null;
        ServerSocket t = null;
        try {
            u = new DatagramSocket(null);
            u.setReuseAddress(true);
            u.bind(new InetSocketAddress(port));
            t = new ServerSocket();
            t.setReuseAddress(true);
            t.bind(new InetSocketAddress(port), 128);
        } catch (IOException e) {
            if (u != null) u.close();
            closeQuietly(t);
            String msg =
                    "Could not listen on port "
                            + port
                            + " ("
                            + e.getMessage()
                            + "). Is another DNS server using it?";
            if (!msg.equals(lastError)) log.error(msg);
            lastError = msg;
            return;
        }
        udp = u;
        tcp = t;
        lastError = null;
        startedAt = System.currentTimeMillis();
        DatagramSocket fu = u;
        ServerSocket ft = t;
        Thread.ofPlatform().daemon().name("dns-udp").start(() -> udpLoop(fu));
        Thread.ofPlatform().daemon().name("dns-tcp").start(() -> tcpLoop(ft));
        log.info("DNS server listening on port {} (UDP and TCP)", port);
    }

    @PreDestroy
    public synchronized void stop() {
        DatagramSocket u = udp;
        ServerSocket t = tcp;
        udp = null;
        tcp = null;
        startedAt = null;
        if (u != null) u.close();
        closeQuietly(t);
        if (u != null) log.info("DNS server stopped");
    }

    private void udpLoop(DatagramSocket socket) {
        byte[] buf = new byte[4096];
        while (!socket.isClosed()) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
            } catch (IOException e) {
                if (socket.isClosed()) return;
                continue;
            }
            byte[] packet = java.util.Arrays.copyOf(p.getData(), p.getLength());
            InetAddress from = p.getAddress();
            int fromPort = p.getPort();
            queries.incrementAndGet();
            Admission admit = admit(from);
            if (admit == Admission.DROP) continue;
            workers.execute(
                    () -> {
                        try {
                            byte[] out =
                                    admit == Admission.SLIP
                                            ? truncated(packet)
                                            : handler.handle(packet, false);
                            if (out != null)
                                socket.send(new DatagramPacket(out, out.length, from, fromPort));
                        } catch (Exception e) {
                            log.debug("DNS answer to {} failed: {}", from, e.getMessage());
                        }
                    });
        }
    }

    private void tcpLoop(ServerSocket server) {
        while (!server.isClosed()) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (server.isClosed()) return;
                continue;
            }
            if (!tcpSlots.tryAcquire()) {
                closeQuietly(s);
                continue;
            }
            workers.execute(
                    () -> {
                        try (s) {
                            serveTcp(s);
                        } catch (Exception e) {
                            log.debug("DNS TCP connection ended: {}", e.getMessage());
                        } finally {
                            tcpSlots.release();
                        }
                    });
        }
    }

    private void serveTcp(Socket s) throws IOException {
        s.setSoTimeout(TCP_IDLE_MS);
        DataInputStream in = new DataInputStream(s.getInputStream());
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        for (int n = 0; n < 100; n++) {
            int len;
            try {
                len = in.readUnsignedShort();
            } catch (EOFException | SocketTimeoutException e) {
                return;
            }
            byte[] packet = in.readNBytes(len);
            if (packet.length != len) return;
            queries.incrementAndGet();
            Message query = null;
            try {
                query = new Message(packet);
            } catch (Exception ignored) {
                // Answered with FORMERR by the handler.
            }
            if (query != null
                    && query.getQuestion() != null
                    && (query.getQuestion().getType() == Type.AXFR
                            || query.getQuestion().getType() == Type.IXFR)) {
                transfer(query, s.getInetAddress(), out);
                return;
            }
            byte[] answer = handler.handle(packet, true);
            if (answer == null) return;
            write(out, answer);
        }
    }

    private static void write(DataOutputStream out, byte[] msg) throws IOException {
        out.writeShort(msg.length);
        out.write(msg);
        out.flush();
    }

    /**
     * A zone transfer (AXFR) to an allowed secondary. IXFR requests get the whole zone too, which
     * RFC 1995 allows.
     */
    private void transfer(Message query, InetAddress from, DataOutputStream out)
            throws IOException {
        Name qname = query.getQuestion().getName();
        ZoneView zone = dns.catalog().get(qname);
        if (zone == null || !dns.transferAllowed(from)) {
            if (zone != null)
                log.info("Refused a zone transfer of {} to {}", qname, from.getHostAddress());
            Message r = reply(query, Rcode.REFUSED);
            write(out, r.toWire());
            return;
        }
        List<Record> all = new ArrayList<>();
        for (RRset set : zone.allSets()) {
            all.addAll(set.rrs(false));
            for (RRSIGRecord sig : set.sigs()) all.add(sig);
        }
        all.add(zone.soa().first());
        Message m = reply(query, Rcode.NOERROR);
        int count = 0;
        for (Record r : all) {
            m.addRecord(r, Section.ANSWER);
            if (++count >= 200 || m.numBytes() > 16_000) {
                write(out, m.toWire());
                m = new Message(query.getHeader().getID());
                m.getHeader().setFlag(Flags.QR);
                m.getHeader().setFlag(Flags.AA);
                count = 0;
            }
        }
        if (count > 0) write(out, m.toWire());
        log.info("Sent zone {} (serial {}) to {}", qname, zone.serial(), from.getHostAddress());
    }

    private static Message reply(Message query, int rcode) {
        Message m = new Message(query.getHeader().getID());
        m.getHeader().setFlag(Flags.QR);
        m.getHeader().setFlag(Flags.AA);
        m.getHeader().setRcode(rcode);
        if (query.getQuestion() != null) m.addRecord(query.getQuestion(), Section.QUESTION);
        return m;
    }

    // ------------------------------------------------------------------------------------------
    // Rate limiting (UDP only: spoofed sources can't use TCP)

    enum Admission {
        ANSWER,
        /** Over the limit: an empty, truncated answer, so a real resolver retries over TCP. */
        SLIP,
        DROP
    }

    private static final class Bucket {
        double tokens;
        long last;
        int over;
    }

    Admission admit(InetAddress from) {
        int limit = rateLimit;
        if (limit <= 0) return Admission.ANSWER;
        long now = System.nanoTime();
        Bucket b =
                buckets.computeIfAbsent(
                        from,
                        k -> {
                            Bucket nb = new Bucket();
                            nb.tokens = limit;
                            nb.last = now;
                            return nb;
                        });
        synchronized (b) {
            b.tokens = Math.min(limit, b.tokens + (now - b.last) / 1e9 * limit);
            b.last = now;
            if (b.tokens >= 1) {
                b.tokens -= 1;
                b.over = 0;
                return Admission.ANSWER;
            }
            limited.incrementAndGet();
            return (++b.over % 2 == 0) ? Admission.SLIP : Admission.DROP;
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void forgetIdleClients() {
        long cutoff = System.nanoTime() - 60_000_000_000L;
        buckets.values()
                .removeIf(
                        b -> {
                            synchronized (b) {
                                return b.last < cutoff;
                            }
                        });
    }

    private static byte[] truncated(byte[] packet) {
        try {
            Message q = new Message(packet);
            if (q.getHeader().getFlag(Flags.QR)) return null;
            Message m = new Message(q.getHeader().getID());
            m.getHeader().setFlag(Flags.QR);
            m.getHeader().setFlag(Flags.TC);
            if (q.getQuestion() != null) m.addRecord(q.getQuestion(), Section.QUESTION);
            return m.toWire();
        } catch (Exception e) {
            return null;
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException | RuntimeException ignored) {
        }
    }
}

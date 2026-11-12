package com.wannaverse.service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure helpers for rollouts: parsing settings, HTTP answers and traffic counts. */
public final class RolloutChecks {
    private RolloutChecks() {}

    /** Paths for HTTP checks: no spaces, quotes or anything a shell or nginx would read. */
    static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9._~/?&=%:+,@!*-]*$");

    public static boolean safePath(String path) {
        return path != null && path.length() <= 500 && SAFE_PATH.matcher(path).matches();
    }

    /**
     * Canary steps as percentages, e.g. "10, 25,50" -> [10, 25, 50]. Each must be 1-99 and larger
     * than the one before; reaching 100% is the promotion that follows the last step.
     */
    public static List<Integer> parseSteps(String text) {
        List<Integer> steps = new ArrayList<>();
        if (text == null || text.isBlank()) return steps;
        int last = 0;
        for (String part : text.split("[,\\s]+")) {
            if (part.isBlank()) continue;
            String p = part.trim().replace("%", "");
            int v;
            try {
                v = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Canary steps are percentages such as 10, 25, 50");
            }
            if (v < 1 || v > 99) {
                throw new IllegalArgumentException("Each canary step must be between 1% and 99%");
            }
            if (v <= last) {
                throw new IllegalArgumentException(
                        "Canary steps must grow, for example 10, 25, 50");
            }
            steps.add(v);
            last = v;
        }
        if (steps.size() > 10) throw new IllegalArgumentException("Use at most 10 canary steps");
        return steps;
    }

    /**
     * True when {@code status} matches {@code expect}: a list such as "200", "2xx", "200-299, 404".
     * Empty means 200-399.
     */
    public static boolean statusMatches(int status, String expect) {
        if (expect == null || expect.isBlank()) return status >= 200 && status < 400;
        for (String part : expect.split(",")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (p.isEmpty()) continue;
            if (p.matches("[1-5]xx")) {
                if (status / 100 == p.charAt(0) - '0') return true;
            } else if (p.matches("\\d{3}-\\d{3}")) {
                int a = Integer.parseInt(p.substring(0, 3));
                int b = Integer.parseInt(p.substring(4));
                if (status >= a && status <= b) return true;
            } else if (p.matches("\\d{3}")) {
                if (status == Integer.parseInt(p)) return true;
            }
        }
        return false;
    }

    /** Checks the syntax of an expected-status list. */
    public static boolean validStatusList(String expect) {
        if (expect == null || expect.isBlank()) return true;
        for (String part : expect.split(",")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (!p.matches("[1-5]xx|\\d{3}-\\d{3}|\\d{3}")) return false;
        }
        return true;
    }

    private static final Pattern STATUS_LINE = Pattern.compile("HTTP/[0-9.]+ (\\d{3})");

    /**
     * The last HTTP status in busybox wget's output (it follows redirects and prints each response
     * with -S), or 0 when it never got an answer.
     */
    public static int lastStatus(String wgetStderr) {
        if (wgetStderr == null) return 0;
        Matcher m = STATUS_LINE.matcher(wgetStderr);
        int status = 0;
        while (m.find()) status = Integer.parseInt(m.group(1));
        return status;
    }

    /** Requests one container answered in a time window. */
    public record Traffic(long requests, long errors, double totalSeconds) {
        public static final Traffic NONE = new Traffic(0, 0, 0);

        public Traffic plus(Traffic o) {
            return new Traffic(
                    requests + o.requests, errors + o.errors, totalSeconds + o.totalSeconds);
        }

        public double errorRate() {
            return requests == 0 ? 0 : errors * 100.0 / requests;
        }

        public long avgMillis() {
            return requests == 0 ? 0 : Math.round(totalSeconds * 1000 / requests);
        }
    }

    /**
     * Parses the summary printed by {@link #TRAFFIC_SCRIPT}: one "container requests errors
     * seconds" line per container.
     */
    public static Map<String, Traffic> parseTraffic(String output) {
        Map<String, Traffic> out = new HashMap<>();
        if (output == null) return out;
        for (String line : output.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length != 4) continue;
            try {
                Traffic t =
                        new Traffic(
                                Long.parseLong(f[1]),
                                Long.parseLong(f[2]),
                                Double.parseDouble(f[3]));
                out.merge(f[0], t, Traffic::plus);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /**
     * Summarises a route's traffic log by container and empties it, so each read covers the time
     * since the last one. The log path is passed as $1, never pasted into the script.
     */
    public static final String TRAFFIC_SCRIPT =
            "f=\"$1\"; [ -f \"$f\" ] || exit 0; awk '{n[$2]++; if ($3 >= 500) e[$2]++; t[$2]+=$4}"
                    + " END {for (k in n) printf \"%s %d %d %.4f\\n"
                    + "\", k, n[k], e[k]+0, t[k]}' \"$f\"; : > \"$f\"";

    /** Why the new version's traffic fails the limits, or null when it is fine. */
    public static String judge(Traffic t, double maxErrorRate, int maxAvgMs, int minRequests) {
        if (t.requests() < Math.max(1, minRequests)) return null;
        if (t.errorRate() > maxErrorRate) {
            return String.format(
                    Locale.ROOT,
                    "%.1f%% of %d requests failed (limit %.1f%%)",
                    t.errorRate(),
                    t.requests(),
                    maxErrorRate);
        }
        if (maxAvgMs > 0 && t.avgMillis() > maxAvgMs) {
            return "Responses took " + t.avgMillis() + " ms on average (limit " + maxAvgMs + " ms)";
        }
        return null;
    }
}

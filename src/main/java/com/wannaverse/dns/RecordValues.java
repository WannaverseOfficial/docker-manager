package com.wannaverse.dns;

import org.xbill.DNS.DClass;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads record names and values as people type them, and says what is wrong when they can't be. */
public final class RecordValues {

    /** The record types that can be added by hand. SOA and the zone's own NS come from the zone. */
    public static final List<String> TYPES =
            List.of("A", "AAAA", "CNAME", "MX", "TXT", "SRV", "CAA", "NS", "PTR", "DS");

    private static final Set<String> TYPE_SET = Set.copyOf(TYPES);

    /** One label: letters, digits, hyphens and underscores (_dmarc, _acme-challenge). */
    private static final Pattern LABEL = Pattern.compile("^[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?$");

    private RecordValues() {}

    public static boolean supported(String type) {
        return type != null && TYPE_SET.contains(type.toUpperCase(Locale.ROOT));
    }

    /** A zone name as stored: lower case, no trailing dot. Throws when it isn't a domain name. */
    public static String zoneName(String raw) {
        String n = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (n.endsWith(".")) n = n.substring(0, n.length() - 1);
        if (n.isEmpty() || n.length() > 253 || !n.contains(".")) {
            throw new IllegalArgumentException("Enter a domain name, such as example.com");
        }
        for (String label : n.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches() || label.contains("_")) {
                throw new IllegalArgumentException("Enter a domain name, such as example.com");
            }
        }
        return n;
    }

    /**
     * A record name relative to the zone, as stored: "@" for the zone itself, otherwise lower case
     * labels, where only the first may be "*". A full name ending in the zone's name is shortened
     * ("www.example.com" in example.com becomes "www").
     */
    public static String relativeName(String raw, String zone) {
        String n = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (n.endsWith(".")) n = n.substring(0, n.length() - 1);
        if (n.isEmpty() || n.equals("@") || n.equals(zone)) return "@";
        if (n.endsWith("." + zone)) n = n.substring(0, n.length() - zone.length() - 1);
        String[] labels = n.split("\\.", -1);
        for (int i = 0; i < labels.length; i++) {
            String label = labels[i];
            if (label.equals("*") && i == 0) continue;
            if (!LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException(
                        "Name: use letters, digits, - and _ between dots, \"@\" for "
                                + zone
                                + " itself, or * as the first part for a wildcard");
            }
        }
        if (n.length() + zone.length() + 1 > 253) {
            throw new IllegalArgumentException("Name: too long");
        }
        return n;
    }

    /** The absolute name of a record: "@" in example.com is example.com. */
    public static Name absolute(String relative, String zone) {
        try {
            return Name.fromString(relative.equals("@") ? zone + "." : relative + "." + zone + ".");
        } catch (TextParseException e) {
            throw new IllegalArgumentException("Name: " + e.getMessage());
        }
    }

    /**
     * Parses values as written in a zone file (after the type). Names inside values are always
     * complete: "mail.example.com" and "mail.example.com." mean the same, as in Route 53.
     */
    public static List<Record> parse(Name owner, String type, long ttl, List<String> values) {
        String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!TYPE_SET.contains(t)) {
            throw new IllegalArgumentException("Type: choose one of " + String.join(", ", TYPES));
        }
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Add at least one value");
        }
        if (values.size() > 100) throw new IllegalArgumentException("At most 100 values");
        int code = Type.value(t);
        if (t.equals("CNAME") && values.size() > 1) {
            throw new IllegalArgumentException("A CNAME record has exactly one value");
        }
        List<Record> out = new ArrayList<>();
        for (String raw : values) {
            String v = raw == null ? "" : raw.strip();
            if (v.isEmpty()) throw new IllegalArgumentException("A value is empty");
            if (v.contains("\n") || v.contains("\r") || v.contains(";") && !t.equals("TXT")) {
                throw new IllegalArgumentException("Value \"" + shorten(v) + "\": one line, no ;");
            }
            if (t.equals("TXT")) v = txt(v);
            try {
                out.add(Record.fromString(owner, code, DClass.IN, ttl, v, Name.root));
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Value \""
                                + shorten(raw)
                                + "\" is not a valid "
                                + t
                                + " value. "
                                + hint(t));
            }
        }
        return out;
    }

    /**
     * A TXT value. Text already in quotes is used as written; anything else is one string, split
     * into the 255-byte pieces DNS needs (resolvers join them again).
     */
    static String txt(String v) {
        if (v.startsWith("\"")) return v;
        byte[] bytes = v.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        int start = 0;
        while (start < bytes.length) {
            int end = Math.min(bytes.length, start + 255);
            // Don't cut a UTF-8 character in half.
            while (end < bytes.length && end > start && (bytes[end] & 0xC0) == 0x80) end--;
            String piece = new String(bytes, start, end - start, StandardCharsets.UTF_8);
            if (!sb.isEmpty()) sb.append(' ');
            sb.append('"');
            for (char c : piece.toCharArray()) {
                if (c == '"' || c == '\\') sb.append('\\');
                sb.append(c);
            }
            sb.append('"');
            start = end;
        }
        return sb.toString();
    }

    static String hint(String type) {
        return switch (type) {
            case "A" -> "Use an IPv4 address, such as 203.0.113.10.";
            case "AAAA" -> "Use an IPv6 address, such as 2001:db8::10.";
            case "CNAME", "NS", "PTR" -> "Use a hostname, such as target.example.com.";
            case "MX" -> "Use a priority and a mail server, such as 10 mail.example.com.";
            case "SRV" -> "Use priority weight port target, such as 10 5 5060 sip.example.com.";
            case "CAA" -> "Use flags tag \"value\", such as 0 issue \"letsencrypt.org\".";
            case "DS" -> "Use key tag, algorithm, digest type and digest, as your DNS host shows.";
            default -> "";
        };
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }
}

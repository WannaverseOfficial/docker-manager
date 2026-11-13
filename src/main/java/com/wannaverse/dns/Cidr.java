package com.wannaverse.dns;

import java.net.InetAddress;

/** An IP address or network (203.0.113.5, 203.0.113.0/24, 2001:db8::/32). */
record Cidr(InetAddress address, int prefix) {

    /** Null when the text isn't an address literal or network. Never looks names up. */
    static Cidr parse(String text) {
        if (text == null) return null;
        String t = text.trim();
        int slash = t.indexOf('/');
        String addr = slash < 0 ? t : t.substring(0, slash);
        if (!addr.matches("^[0-9a-fA-F:.]+$") || !(addr.contains(".") || addr.contains(":"))) {
            return null;
        }
        try {
            InetAddress a = InetAddress.getByName(addr);
            int max = a.getAddress().length * 8;
            int prefix = slash < 0 ? max : Integer.parseInt(t.substring(slash + 1));
            if (prefix < 0 || prefix > max) return null;
            return new Cidr(a, prefix);
        } catch (Exception e) {
            return null;
        }
    }

    boolean single() {
        return prefix == address.getAddress().length * 8;
    }

    boolean contains(InetAddress other) {
        byte[] a = address.getAddress();
        byte[] b = other.getAddress();
        if (a.length != b.length) {
            // An IPv4 client seen as ::ffff:a.b.c.d on a dual-stack socket.
            if (a.length == 4 && b.length == 16 && mapped(b)) {
                byte[] v4 = new byte[4];
                System.arraycopy(b, 12, v4, 0, 4);
                b = v4;
            } else {
                return false;
            }
        }
        int full = prefix / 8, rest = prefix % 8;
        for (int i = 0; i < full; i++) if (a[i] != b[i]) return false;
        if (rest == 0) return true;
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (a[full] & mask) == (b[full] & mask);
    }

    private static boolean mapped(byte[] b) {
        for (int i = 0; i < 10; i++) if (b[i] != 0) return false;
        return (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
    }
}

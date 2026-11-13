package com.wannaverse.dns;

import static org.junit.jupiter.api.Assertions.*;

import com.wannaverse.persistence.DnsSettings;
import com.wannaverse.security.InputValidator;
import com.wannaverse.service.NginxConfigGenerator;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

class DnsHelpersTest {

    @Test
    void cidrMatchesAddressesAndNetworks() throws Exception {
        Cidr net = Cidr.parse("203.0.113.0/24");
        assertTrue(net.contains(InetAddress.getByName("203.0.113.77")));
        assertFalse(net.contains(InetAddress.getByName("203.0.114.1")));
        assertFalse(net.single());
        Cidr one = Cidr.parse("198.51.100.5");
        assertTrue(one.single());
        assertTrue(one.contains(InetAddress.getByName("::ffff:198.51.100.5")));
        assertTrue(Cidr.parse("2001:db8::/32").contains(InetAddress.getByName("2001:db8:1::5")));
        assertFalse(Cidr.parse("2001:db8::/32").contains(InetAddress.getByName("203.0.113.1")));
        assertTrue(Cidr.parse("10.0.0.0/12").contains(InetAddress.getByName("10.15.255.255")));
        assertFalse(Cidr.parse("10.0.0.0/12").contains(InetAddress.getByName("10.16.0.0")));
        // Names are never looked up.
        assertNull(Cidr.parse("example.com"));
        assertNull(Cidr.parse("203.0.113.0/33"));
    }

    @Test
    void serialsStartAtTodayAndOnlyGoUp() {
        long today =
                Long.parseLong(
                                LocalDate.now(ZoneOffset.UTC)
                                        .format(DateTimeFormatter.BASIC_ISO_DATE))
                        * 100;
        assertEquals(today, DnsService.nextSerial(0));
        assertEquals(today + 1, DnsService.nextSerial(today));
        assertEquals(today + 51, DnsService.nextSerial(today + 50));
        assertEquals(9_999_999_999L, DnsService.nextSerial(9_999_999_998L));
    }

    @Test
    void hostmasterEmailBecomesAName() {
        DnsSettings s = new DnsSettings();
        assertEquals("hostmaster.example.com.", DnsService.hostmaster(s, "example.com").toString());
        s.setHostmasterEmail("dns.admin@example.org");
        assertEquals(
                "dns\\.admin.example.org.", DnsService.hostmaster(s, "example.com").toString());
    }

    @Test
    void routeHostnamesMayBeWildcards() {
        assertTrue(InputValidator.isValidRouteHostname("app.example.com"));
        assertTrue(InputValidator.isValidRouteHostname("*.example.com"));
        assertFalse(InputValidator.isValidRouteHostname("*.com"));
        assertFalse(InputValidator.isValidRouteHostname("a.*.example.com"));
        assertFalse(InputValidator.isValidRouteHostname("*example.com"));
        assertFalse(InputValidator.isValidRouteHostname("localhost"));
        assertEquals(
                "/etc/nginx/certs/_wildcard.example.com",
                NginxConfigGenerator.certDir("*.example.com"));
    }

    @Test
    void addressesAreChecked() {
        assertEquals("203.0.113.9", DnsService.ipv4(" 203.0.113.9 "));
        assertNull(DnsService.ipv4(""));
        assertThrows(Exception.class, () -> DnsService.ipv4("203.0.113.256"));
        assertThrows(Exception.class, () -> DnsService.ipv4("example.com"));
        assertEquals("2001:db8::1", DnsService.ipv6("2001:DB8::1"));
        assertThrows(Exception.class, () -> DnsService.ipv6("203.0.113.9"));
        assertThrows(Exception.class, () -> DnsService.ipv6("example.com"));
    }
}

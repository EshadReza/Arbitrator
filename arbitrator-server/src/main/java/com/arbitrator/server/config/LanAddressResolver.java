package com.arbitrator.server.config;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every non-loopback IPv4 address this machine holds, best guess first.
 *
 * Students type the server's address into the client, so at the start of every
 * lab somebody has to know it. A machine usually has more than one — Wi-Fi and
 * Ethernet both up, a Docker bridge, a VPN tunnel — and only the person in the
 * room knows which one the lab is actually on. So this reports all of them and
 * merely *orders* them: the first entry is a guess, not an answer, and the
 * console shows the rest underneath for when the guess is wrong.
 */
@Component
public class LanAddressResolver {

    private static final Logger log = LoggerFactory.getLogger(LanAddressResolver.class);

    /**
     * Interface-name prefixes that are nearly always a virtual bridge, a VPN or
     * a hypervisor NIC rather than the lab network. A Docker bridge is 172.17.x,
     * which is site-local and would otherwise outrank the real 192.168.x card on
     * a plain numeric sort. These are pushed down the list, never hidden.
     */
    private static final List<String> VIRTUAL_PREFIXES = List.of(
            "docker", "br-", "veth", "virbr", "vbox", "vmnet", "vnic",
            "utun", "tun", "tap", "awdl", "llw", "bridge", "zt", "wg");

    /** One address the machine answers on. */
    public record Address(String iface, String ip, boolean siteLocal, boolean likelyVirtual) {
    }

    /** All usable IPv4 addresses, most-likely-the-lab-LAN first. */
    public List<Address> all() {
        List<Address> found = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                // isVirtual() only catches sub-interfaces such as eth0:1, which is
                // why the name check above exists as well.
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                boolean virtual = likelyVirtual(ni.getName());
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    // IPv4 only: the client builds "host:port" URLs, and a bare
                    // IPv6 literal there needs brackets nobody will type right.
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        found.add(new Address(ni.getName(), addr.getHostAddress(),
                                addr.isSiteLocalAddress(), virtual));
                    }
                }
            }
        } catch (SocketException e) {
            log.warn("Could not enumerate network interfaces: {}", e.getMessage());
        }

        // Real NICs before virtual ones, then site-local (10/172.16-31/192.168)
        // before anything else — a 169.254.x link-local address means DHCP failed
        // and is never the one to read out to a room.
        found.sort(Comparator.comparing(Address::likelyVirtual)
                .thenComparing(Comparator.comparing(Address::siteLocal).reversed())
                .thenComparing(Address::ip));
        return found;
    }

    /** Best guess at the address students should use; loopback if there is none. */
    public String primary() {
        return all().stream().map(Address::ip).findFirst().orElse("127.0.0.1");
    }

    public String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    private static boolean likelyVirtual(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return VIRTUAL_PREFIXES.stream().anyMatch(n::startsWith);
    }
}

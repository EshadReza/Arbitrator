/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

package com.arbitrator.client.net;

import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

/**
 * Best-effort hardware MAC of this machine, sent at login so the instructor
 * can see which device an account is signing in from (item 4). Never blocks
 * or throws — a machine with no readable NIC (a VM, a sandboxed runtime)
 * just reports null and the server skips the check for that login.
 */
public final class MacAddress {

    private MacAddress() {
    }

    /** First non-loopback, non-virtual interface with a hardware address, as AA:BB:CC:DD:EE:FF. */
    public static String detect() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface iface : Collections.list(ifaces)) {
                if (iface.isLoopback() || iface.isVirtual() || !iface.isUp()) {
                    continue;
                }
                byte[] mac = iface.getHardwareAddress();
                if (mac != null && mac.length == 6) {
                    return format(mac);
                }
            }
        } catch (Exception ignored) {
            // No usable network stack — report nothing rather than guess.
        }
        return null;
    }

    private static String format(byte[] mac) {
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02X", mac[i]));
        }
        return sb.toString();
    }
}

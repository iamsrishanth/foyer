package com.kaera.foyer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Finds the LAN address this device is reachable on. */
public final class Net {
    private Net() {
    }

    public static String localIpv4() {
        String fallback = null;
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nif == null || !nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName();
                if (name != null && (name.startsWith("dummy") || name.startsWith("rmnet"))) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String host = a.getHostAddress();
                    if (host == null) continue;
                    if (a.isSiteLocalAddress()) return host;
                    if (fallback == null && !a.isLinkLocalAddress()) fallback = host;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback == null ? "127.0.0.1" : fallback;
    }
}

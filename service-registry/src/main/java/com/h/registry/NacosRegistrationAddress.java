package com.h.registry;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

/** The same advertised address is used by naming and AI Registry registrations. */
public record NacosRegistrationAddress(String ip, int port) {
    public static NacosRegistrationAddress resolve(NacosRegistryProperties properties, int boundPort) {
        return new NacosRegistrationAddress(properties.ip().isBlank() ? localIp() : properties.ip(),
                properties.port() == 0 ? boundPort : properties.port());
    }

    private static String localIp() {
        try {
            String fallback = null;
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                for (var address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        if (address.isSiteLocalAddress()) {
                            return address.getHostAddress();
                        }
                        fallback = address.getHostAddress();
                    }
                }
            }
            if (fallback != null) {
                return fallback;
            }
        } catch (SocketException e) {
            throw new IllegalStateException("Cannot determine registration IP; set NACOS_IP", e);
        }
        throw new IllegalStateException("No non-loopback IPv4 address; set NACOS_IP");
    }
}

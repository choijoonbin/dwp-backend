package com.dwp.services.approval.signatureproviders;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import org.apache.hc.client5.http.DnsResolver;

/** One DNS snapshot is consumed by the actual connection operator; TLS still receives the original hostname. */
final class SignatureProviderPinnedDnsResolver implements DnsResolver {
    private final String host;
    private final InetAddress[] addresses;

    private SignatureProviderPinnedDnsResolver(String host, InetAddress[] addresses) {
        this.host = host; this.addresses = addresses.clone();
    }

    static SignatureProviderPinnedDnsResolver lookup(String host) throws UnknownHostException {
        return snapshot(host, InetAddress.getAllByName(host));
    }

    static SignatureProviderPinnedDnsResolver snapshot(String host, InetAddress[] answers) throws UnknownHostException {
        if (host == null || answers == null || answers.length == 0 || answers.length > 8)
            throw new UnknownHostException("Bounded provider DNS response required");
        for (var address : answers) if (!isPublic(address)) throw new UnknownHostException("Non-public provider DNS answer");
        return new SignatureProviderPinnedDnsResolver(host, answers);
    }

    @Override public InetAddress[] resolve(String requestedHost) throws UnknownHostException {
        requireHost(requestedHost); return addresses.clone();
    }
    @Override public String resolveCanonicalHostname(String requestedHost) throws UnknownHostException {
        requireHost(requestedHost); return host;
    }
    @Override public List<InetSocketAddress> resolve(String requestedHost, int port) throws UnknownHostException {
        requireHost(requestedHost); if (port != 443) throw new UnknownHostException("Provider HTTPS port must be 443");
        return Arrays.stream(addresses).map(address -> new InetSocketAddress(address, 443)).toList();
    }
    private void requireHost(String requestedHost) throws UnknownHostException {
        if (!host.equals(requestedHost)) throw new UnknownHostException("Provider DNS hostname changed");
    }

    static boolean isPublic(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress(); int a = bytes[0] & 255, b = bytes[1] & 255;
        if (bytes.length == 4) {
            int c = bytes[2] & 255;
            return a != 0 && a != 10 && a != 127 && a < 224 && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31)
                    && !(a == 192 && (b == 0 && (c == 0 || c == 2) || b == 168 || b == 88 && c == 99))
                    && !(a == 198 && (b == 18 || b == 19 || b == 51 && c == 100))
                    && !(a == 203 && b == 0 && c == 113);
        }
        if (bytes.length != 16 || (a & 224) != 32) return false;
        int c = bytes[2] & 255, d = bytes[3] & 255;
        return !(a == 32 && b == 1 && (c <= 1 || c == 13 && d == 184)) && !(a == 32 && b == 2);
    }
}

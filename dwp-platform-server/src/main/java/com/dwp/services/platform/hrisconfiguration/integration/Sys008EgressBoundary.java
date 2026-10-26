package com.dwp.services.platform.hrisconfiguration.integration;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Pure fail-closed allowlist checks used before connector egress. */
public final class Sys008EgressBoundary {

    private Sys008EgressBoundary() {}

    public static ResolvedEgressPin requireAllowed(
            URI endpoint,
            Allowlist allowlist,
            List<InetAddress> resolvedDestinations) {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(allowlist, "allowlist must not be null");
        String scheme = canonical(endpoint.getScheme());
        String host = canonical(endpoint.getHost());
        int port = effectivePort(scheme, endpoint.getPort());
        if (!endpoint.isAbsolute() || scheme == null || host == null
                || !allowlist.schemes().contains(scheme)
                || !allowlist.hosts().contains(host)
                || !allowlist.ports().contains(port)
                || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("egress endpoint is not allowed");
        }
        List<InetAddress> addresses = List.copyOf(Objects.requireNonNull(
                resolvedDestinations, "resolvedDestinations must not be null"));
        if (addresses.isEmpty() || addresses.stream().anyMatch(
                Sys008EgressBoundary::forbiddenAddress)) {
            throw new IllegalArgumentException(
                    "resolved egress destination is not allowed");
        }
        return new ResolvedEgressPin(scheme, host, port, addresses);
    }

    public static ResolvedEgressPin requireSafeRedirectOrDnsRefresh(
            ResolvedEgressPin original,
            URI nextEndpoint,
            Allowlist allowlist,
            List<InetAddress> nextDestinations) {
        Objects.requireNonNull(original, "original must not be null");
        ResolvedEgressPin next = requireAllowed(
                nextEndpoint, allowlist, nextDestinations);
        if (!original.scheme().equals(next.scheme())
                || !original.host().equals(next.host())
                || original.port() != next.port()
                || !original.resolvedDestinations().equals(
                        next.resolvedDestinations())) {
            throw new IllegalArgumentException(
                    "redirect or DNS rebinding changed the pinned destination");
        }
        return next;
    }

    private static int effectivePort(String scheme, int explicitPort) {
        if (explicitPort > 0) return explicitPort;
        if ("https".equals(scheme)) return 443;
        if ("sftp".equals(scheme)) return 22;
        return -1;
    }

    private static boolean forbiddenAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress()
                || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()
                || "169.254.169.254".equals(address.getHostAddress())) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            int first = raw[0] & 0xff;
            int second = raw[1] & 0xff;
            return first == 0 || (first == 100 && second >= 64 && second <= 127);
        }
        return raw.length == 16 && ((raw[0] & 0xfe) == 0xfc);
    }

    private static String canonical(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    public record Allowlist(
            Set<String> schemes,
            Set<String> hosts,
            Set<Integer> ports) {

        public Allowlist {
            schemes = canonicalSet(schemes);
            hosts = canonicalSet(hosts);
            ports = Set.copyOf(Objects.requireNonNull(ports));
            if (schemes.isEmpty() || hosts.isEmpty() || ports.isEmpty()
                    || ports.stream().anyMatch(port -> port == null
                    || port < 1 || port > 65535)) {
                throw new IllegalArgumentException("egress allowlist is invalid");
            }
        }

        private static Set<String> canonicalSet(Set<String> values) {
            return Objects.requireNonNull(values).stream()
                    .map(Sys008EgressBoundary::canonical)
                    .filter(value -> value != null && !value.isBlank())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    public record ResolvedEgressPin(
            String scheme,
            String host,
            int port,
            List<InetAddress> resolvedDestinations) {

        public ResolvedEgressPin {
            resolvedDestinations = List.copyOf(resolvedDestinations);
        }
    }
}

package com.applyflow.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

@Component
public class TrustedClientIpResolver {

    static final int MAX_HEADER_BYTES = 8192;
    static final int MAX_HOPS = 20;
    private final HeaderMode mode;
    private final List<IpAddressMatcher> trustedProxies;

    public TrustedClientIpResolver(
            @Value("${app.security.trusted-proxies.mode:none}") String mode,
            @Value("${app.security.trusted-proxies.cidrs:}") String cidrs
    ) {
        this.mode = HeaderMode.parse(mode);
        this.trustedProxies = parseCidrs(cidrs);
        if (this.mode != HeaderMode.NONE && trustedProxies.isEmpty()) {
            throw new IllegalArgumentException("A forwarding header mode requires at least one trusted proxy CIDR");
        }
    }

    public String resolve(HttpServletRequest request) {
        String rawPeer;
        try {
            rawPeer = canonicalizeLiteral(request.getRemoteAddr());
        } catch (IllegalArgumentException exception) {
            return request.getRemoteAddr();
        }
        if (mode == HeaderMode.NONE || !isTrusted(rawPeer)) {
            return rawPeer;
        }
        String header = readHeader(request, mode == HeaderMode.FORWARDED ? "Forwarded" : "X-Forwarded-For");
        if (header == null) {
            return rawPeer;
        }
        try {
            List<String> elements = split(header, ',');
            if (elements.isEmpty() || elements.size() > MAX_HOPS) {
                return rawPeer;
            }
            for (int index = elements.size() - 1; index >= 0; index--) {
                String hop = mode == HeaderMode.FORWARDED
                        ? parseForwardedElement(elements.get(index))
                        : parseNode(elements.get(index));
                if (!isTrusted(hop)) {
                    return hop;
                }
            }
        } catch (IllegalArgumentException exception) {
            return rawPeer;
        }
        return rawPeer;
    }

    private String readHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        StringBuilder combined = new StringBuilder();
        int bytes = 0;
        while (values != null && values.hasMoreElements()) {
            String value = values.nextElement();
            if (value.isBlank() || value.length() > MAX_HEADER_BYTES
                    || (bytes += value.getBytes(StandardCharsets.UTF_8).length + (combined.isEmpty() ? 0 : 1)) > MAX_HEADER_BYTES) {
                return null;
            }
            if (!combined.isEmpty()) {
                combined.append(',');
            }
            combined.append(value);
        }
        return combined.isEmpty() ? null : combined.toString();
    }

    private static String parseForwardedElement(String element) {
        String result = null;
        for (String parameter : split(element, ';')) {
            int separator = parameter.indexOf('=');
            if (separator <= 0 || separator == parameter.length() - 1) {
                throw new IllegalArgumentException("Malformed Forwarded parameter");
            }
            if (parameter.substring(0, separator).trim().equalsIgnoreCase("for")) {
                if (result != null) {
                    throw new IllegalArgumentException("Duplicate Forwarded for parameter");
                }
                result = parseNode(parameter.substring(separator + 1));
            }
        }
        if (result == null) {
            throw new IllegalArgumentException("Forwarded element has no for parameter");
        }
        return result;
    }

    private static List<String> split(String value, char delimiter) {
        List<String> parts = new ArrayList<>();
        boolean quoted = false;
        int start = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\\') {
                throw new IllegalArgumentException("Escaped forwarding values are not accepted");
            }
            if (current == '"') {
                quoted = !quoted;
            } else if (current == delimiter && !quoted) {
                parts.add(value.substring(start, index).trim());
                start = index + 1;
            }
        }
        parts.add(value.substring(start).trim());
        if (quoted || parts.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("Unclosed or empty forwarding value");
        }
        return parts;
    }

    private static String parseNode(String value) {
        String node = value.trim();
        if (node.length() >= 2 && node.startsWith("\"") && node.endsWith("\"")) {
            node = node.substring(1, node.length() - 1);
        } else if (node.indexOf('"') >= 0) {
            throw new IllegalArgumentException("Malformed quoted node");
        }
        String host;
        if (node.startsWith("[")) {
            int closing = node.indexOf(']');
            if (closing < 0 || node.substring(1, closing).indexOf(':') < 0) {
                throw new IllegalArgumentException("Malformed bracketed address");
            }
            host = node.substring(1, closing);
            validatePort(node.substring(closing + 1));
        } else if (node.chars().filter(character -> character == ':').count() == 1) {
            int separator = node.lastIndexOf(':');
            host = node.substring(0, separator);
            validatePort(node.substring(separator));
        } else {
            host = node;
        }
        return canonicalizeLiteral(host);
    }

    private static void validatePort(String suffix) {
        if (suffix.isEmpty()) {
            return;
        }
        if (!suffix.matches(":[0-9]{1,5}")) {
            throw new IllegalArgumentException("Invalid node port");
        }
        int port = Integer.parseInt(suffix.substring(1));
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid node port");
        }
    }

    private boolean isTrusted(String address) {
        return trustedProxies.stream().anyMatch(cidr -> cidr.matches(address));
    }

    private static String canonicalizeLiteral(String address) {
        if (address == null || address.isBlank() || address.indexOf('%') >= 0
                || !address.matches("[0-9A-Fa-f:.]+")) {
            throw new IllegalArgumentException("IP literal required");
        }
        if (address.indexOf(':') < 0) {
            String[] octets = address.split("\\.", -1);
            if (octets.length != 4 || Arrays.stream(octets).anyMatch(octet -> !octet.matches("0|[1-9][0-9]{0,2}")
                    || Integer.parseInt(octet) > 255)) {
                throw new IllegalArgumentException("Canonical IPv4 literal required");
            }
        }
        try {
            return InetAddress.getByName(address).getHostAddress();
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Invalid IP literal", exception);
        }
    }

    private static List<IpAddressMatcher> parseCidrs(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        List<IpAddressMatcher> cidrs = new ArrayList<>();
        for (String value : configured.split(",", -1)) {
            String[] parts = value.trim().split("/", -1);
            if (parts.length != 2 || !parts[1].matches("[0-9]{1,3}")) {
                throw new IllegalArgumentException("Trusted proxy CIDRs must use address/prefix notation");
            }
            String network = canonicalizeLiteral(parts[0]);
            int prefix = Integer.parseInt(parts[1]);
            if (prefix > (network.contains(":") ? 128 : 32)) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR prefix");
            }
            cidrs.add(new IpAddressMatcher(network + "/" + prefix));
        }
        return List.copyOf(cidrs);
    }

    private enum HeaderMode {
        NONE, FORWARDED, X_FORWARDED_FOR;

        static HeaderMode parse(String configured) {
            try {
                return valueOf(configured.trim().replace('-', '_').toUpperCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Trusted proxy mode must be none, forwarded, or x-forwarded-for", exception);
            }
        }
    }
}

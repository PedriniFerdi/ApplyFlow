package com.applyflow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;

class TrustedClientIpResolverTest {

    @Test
    void defaultsToThePeerAndCanonicalizesMappedIpv6() {
        TrustedClientIpResolver resolver = new TrustedClientIpResolver("none", "");
        assertThat(resolver.resolve(request("::ffff:192.0.2.5", "Forwarded", "for=198.51.100.1")))
                .isEqualTo("192.0.2.5");
    }

    @Test
    void resolvesTrustedSingleAndRepeatedHeaderHopsWithoutTrustingAnAttackerPrefix() {
        TrustedClientIpResolver resolver = new TrustedClientIpResolver("x-forwarded-for", "10.0.0.0/8");
        MockHttpServletRequest request = request("10.0.0.8", "X-Forwarded-For", "192.0.2.200, 198.51.100.7");
        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.7");
        request.addHeader("X-Forwarded-For", "10.0.0.9");
        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.7");
        assertThat(resolver.resolve(request("10.0.0.8", "X-Forwarded-For", "10.0.0.9"))).isEqualTo("10.0.0.8");
    }

    @Test
    void parsesForwardedIpv6AndIgnoresTheOtherHeaderFamily() {
        TrustedClientIpResolver resolver = new TrustedClientIpResolver("forwarded", "2001:db8:abcd::/48");
        MockHttpServletRequest request = request("2001:db8:abcd::2", "Forwarded",
                "for=\"[2001:db9::1]\";proto=https, for=\"[2001:db8:abcd::3]:443\"");
        request.addHeader("X-Forwarded-For", "198.51.100.99");
        assertThat(resolver.resolve(request)).isEqualTo("2001:db9:0:0:0:0:0:1");
    }

    @ParameterizedTest
    @CsvSource({"10.0.0.0/31,10.0.0.1,true", "10.0.0.0/31,10.0.0.2,false",
            "10.0.0.1/32,10.0.0.1,true", "10.0.0.1/32,10.0.0.0,false",
            "2001:db8::1/128,2001:db8::1,true", "2001:db8::1/128,2001:db8::2,false"})
    void respectsCidrPrefixBoundaries(String cidr, String peer, boolean trusted) {
        TrustedClientIpResolver resolver = new TrustedClientIpResolver("x-forwarded-for", cidr);
        String result = resolver.resolve(request(peer, "X-Forwarded-For", "192.0.2.5"));
        String canonicalPeer = new TrustedClientIpResolver("none", "").resolve(request(peer, "Forwarded", "for=unknown"));
        assertThat(result).isEqualTo(trusted ? "192.0.2.5" : canonicalPeer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"for=unknown", "for=example.com", "for=_hidden", "for=\"[fe80::1%eth0]:443\"",
            "for=192.0.2.1:99999", "for=10.0.0.1;for=192.0.2.1", "for=10.0.0.1,", "for=\"[2001:db8::1]"})
    void malformedTrustedChainsFailClosed(String header) {
        TrustedClientIpResolver resolver = new TrustedClientIpResolver("forwarded", "10.0.0.0/8");
        assertThat(resolver.resolve(request("10.0.0.2", "Forwarded", header))).isEqualTo("10.0.0.2");
    }

    @Test
    void excessiveHopsAndOversizedCombinedHeadersFailClosed() {
        TrustedClientIpResolver resolver = new TrustedClientIpResolver("x-forwarded-for", "10.0.0.0/8");
        assertThat(resolver.resolve(request("10.0.0.2", "X-Forwarded-For", "10.0.0.1,".repeat(20) + "10.0.0.1")))
                .isEqualTo("10.0.0.2");
        MockHttpServletRequest request = request("10.0.0.2", "X-Forwarded-For", " ".repeat(4090) + "192.0.2.1");
        request.addHeader("X-Forwarded-For", " ".repeat(4090) + "10.0.0.1");
        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-cidr", "example.com/8", "10.0.0.1/33", "2001:db8::1/129", "10.0.0.1/-1"})
    void invalidOrMissingCidrsFailAtStartupBoundary(String cidrs) {
        assertThatThrownBy(() -> new TrustedClientIpResolver("forwarded", cidrs))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownModesAndAcceptsExplicitZeroLengthPrefixes() {
        assertThatThrownBy(() -> new TrustedClientIpResolver("both", "10.0.0.0/8"))
                .isInstanceOf(IllegalArgumentException.class);
        new TrustedClientIpResolver("none", "0.0.0.0/0,::/0");
    }

    @Test
    void applicationDisablesFrameworkForwarding() throws IOException {
        String configuration = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);
        assertThat(configuration).contains("forward-headers-strategy: none");
    }

    private static MockHttpServletRequest request(String peer, String header, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader(header, value);
        return request;
    }
}

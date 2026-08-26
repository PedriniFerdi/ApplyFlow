package com.applyflow.service;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import com.applyflow.exception.BusinessRuleException;
import com.applyflow.exception.JobOfferExtractionException;
import com.applyflow.validation.RequestLimits;

@Component
public class SafeHtmlFetcher {

    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final int MAX_HEADER_BYTES = 64 * 1024;

    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int totalTimeoutMillis;
    private final int maxHtmlBytes;
    private final int maxRedirects;
    private final ThreadPoolExecutor dnsExecutor;

    public SafeHtmlFetcher(
            @Value("${app.job-offer-extraction.connect-timeout:2s}") Duration connectTimeout,
            @Value("${app.job-offer-extraction.read-timeout:3s}") Duration readTimeout,
            @Value("${app.job-offer-extraction.total-timeout:5s}") Duration totalTimeout,
            @Value("${app.job-offer-extraction.max-html-bytes:1048576}") int maxHtmlBytes,
            @Value("${app.job-offer-extraction.max-redirects:3}") int maxRedirects
    ) {
        if (connectTimeout.toMillis() < 1 || readTimeout.toMillis() < 1
                || totalTimeout.toMillis() < 1 || maxHtmlBytes < 1 || maxRedirects < 0) {
            throw new IllegalArgumentException("Job-offer extraction limits must be positive");
        }
        this.connectTimeoutMillis = Math.toIntExact(connectTimeout.toMillis());
        this.readTimeoutMillis = Math.toIntExact(readTimeout.toMillis());
        this.totalTimeoutMillis = Math.toIntExact(totalTimeout.toMillis());
        this.maxHtmlBytes = maxHtmlBytes;
        this.maxRedirects = maxRedirects;
        AtomicInteger threadNumber = new AtomicInteger();
        this.dnsExecutor = new ThreadPoolExecutor(
                4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), runnable -> {
                    Thread thread = new Thread(runnable, "job-offer-dns-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdownDnsExecutor() {
        dnsExecutor.shutdownNow();
    }

    public FetchedHtml fetch(String rawUrl) {
        URI current = parseAndValidateUrl(rawUrl);
        long deadlineNanos = System.nanoTime() + Duration.ofMillis(totalTimeoutMillis).toNanos();
        for (int redirect = 0; redirect <= maxRedirects; redirect++) {
            HttpResponse response = request(current, deadlineNanos);
            if (REDIRECT_STATUSES.contains(response.status())) {
                if (redirect == maxRedirects) {
                    throw new JobOfferExtractionException("The job page redirected too many times.");
                }
                String location = response.headers().get("location");
                if (location == null || location.isBlank()) {
                    throw new JobOfferExtractionException("The job page returned an invalid redirect.");
                }
                final URI next;
                try {
                    next = parseAndValidateUrl(current.resolve(location).toString());
                } catch (IllegalArgumentException exception) {
                    throw new JobOfferExtractionException("The job page returned an invalid redirect.", exception);
                }
                if ("https".equalsIgnoreCase(current.getScheme()) && "http".equalsIgnoreCase(next.getScheme())) {
                    throw new JobOfferExtractionException("The job page attempted an insecure redirect.");
                }
                current = next;
                continue;
            }
            if (response.status() < 200 || response.status() >= 300) {
                throw new JobOfferExtractionException("The job page could not be read (HTTP " + response.status() + ").");
            }
            String contentType = response.headers().getOrDefault("content-type", "").toLowerCase(Locale.ROOT);
            if (!(contentType.startsWith("text/html") || contentType.startsWith("application/xhtml+xml"))) {
                throw new JobOfferExtractionException("The URL does not point to an HTML job page.");
            }
            byte[] body = decodeBody(response.body(), response.headers().get("content-encoding"), deadlineNanos);
            return new FetchedHtml(current, contentType, body);
        }
        throw new JobOfferExtractionException("The job page could not be read.");
    }

    URI parseAndValidateUrl(String rawUrl) {
        if (rawUrl == null || RequestLimits.exceedsCodePoints(rawUrl, RequestLimits.URL)) {
            throw new BusinessRuleException("url must contain at most 1000 characters");
        }
        try {
            URI uri = new URI(rawUrl.trim()).normalize();
            String scheme = uri.getScheme();
            if (uri.getUserInfo() != null || uri.getHost() == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new BusinessRuleException("url must be an absolute HTTP or HTTPS URL without credentials");
            }
            int port = uri.getPort();
            if (port != -1 && !(port == 80 && "http".equalsIgnoreCase(scheme))
                    && !(port == 443 && "https".equalsIgnoreCase(scheme))) {
                throw new BusinessRuleException("url must use the standard HTTP or HTTPS port");
            }
            String rawHost = uri.getHost();
            if (rawHost.startsWith("[") && rawHost.endsWith("]")) rawHost = rawHost.substring(1, rawHost.length() - 1);
            String asciiHost = rawHost.contains(":") ? rawHost.toLowerCase(Locale.ROOT)
                    : IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            String path = uri.getRawPath();
            String authority = asciiHost.contains(":") ? "[" + asciiHost + "]" : asciiHost;
            if (port != -1) authority += ":" + port;
            return new URI(scheme.toLowerCase(Locale.ROOT) + "://" + authority
                    + (path == null || path.isEmpty() ? "/" : path)
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new BusinessRuleException("url must be a valid absolute HTTP or HTTPS URL");
        }
    }

    InetAddress resolvePublicAddress(String host, long deadlineNanos) {
        final InetAddress[] addresses;
        final Future<InetAddress[]> lookup;
        try {
            lookup = dnsExecutor.submit(() -> InetAddress.getAllByName(host));
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            throw new JobOfferExtractionException("Job-page address resolution is temporarily busy.", exception);
        }
        try {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                lookup.cancel(true);
                dnsExecutor.purge();
                throw new JobOfferExtractionException("The job page took too long to resolve.");
            }
            addresses = lookup.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            lookup.cancel(true);
            dnsExecutor.purge();
            throw new JobOfferExtractionException("The job page took too long to resolve.", exception);
        } catch (InterruptedException exception) {
            lookup.cancel(true);
            dnsExecutor.purge();
            Thread.currentThread().interrupt();
            throw new JobOfferExtractionException("Job-page address resolution was interrupted.", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof UnknownHostException unknownHostException) {
                throw new JobOfferExtractionException("The job page host could not be resolved.", unknownHostException);
            }
            throw new JobOfferExtractionException("The job page host could not be resolved.", exception);
        }
        if (addresses.length == 0) {
            throw new JobOfferExtractionException("The job page host could not be resolved.");
        }
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new BusinessRuleException("url must resolve only to public internet addresses");
            }
        }
        return addresses[0];
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first != 0 && first != 10 && first != 127 && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && second == 0 && third == 0)
                    && !(first == 192 && second == 0 && third == 2)
                    && !(first == 192 && second == 168)
                    && !(first == 198 && (second == 18 || second == 19))
                    && !(first == 198 && second == 51 && third == 100)
                    && !(first == 203 && second == 0 && third == 113);
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            boolean documentation = first == 0x20 && second == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8;
            boolean specialPurpose2001 = first == 0x20 && second == 0x01
                    && Byte.toUnsignedInt(bytes[2]) <= 0x01;
            boolean documentation3fff = first == 0x3f && second == 0xff
                    && (Byte.toUnsignedInt(bytes[2]) & 0xf0) == 0;
            boolean nat64 = first == 0x00 && second == 0x64 && Byte.toUnsignedInt(bytes[2]) == 0xff
                    && Byte.toUnsignedInt(bytes[3]) == 0x9b;
            boolean sixToFour = first == 0x20 && second == 0x02;
            boolean uniqueLocal = (first & 0xfe) == 0xfc;
            boolean globalUnicast = (first & 0xe0) == 0x20;
            return globalUnicast && !documentation && !documentation3fff && !specialPurpose2001
                    && !nat64 && !sixToFour && !uniqueLocal;
        }
        return false;
    }

    private HttpResponse request(URI uri, long deadlineNanos) {
        ensureBeforeDeadline(deadlineNanos);
        InetAddress address = resolvePublicAddress(uri.getHost(), deadlineNanos);
        int port = uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
        try (Socket transport = openSocket(uri, address, port, deadlineNanos)) {
            OutputStream output = transport.getOutputStream();
            String target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            String request = "GET " + target + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader(uri.getHost()) + "\r\n"
                    + "User-Agent: ApplyFlow-JobImporter/1.0\r\n"
                    + "Accept: text/html, application/xhtml+xml\r\n"
                    + "Accept-Encoding: gzip, identity\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(request.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            return readResponse(new DeadlineInputStream(transport.getInputStream(), transport, deadlineNanos), deadlineNanos);
        } catch (IOException exception) {
            throw new JobOfferExtractionException("The job page could not be reached.", exception);
        }
    }

    private Socket openSocket(URI uri, InetAddress address, int port, long deadlineNanos) throws IOException {
        Socket plain = new Socket();
        int remaining = remainingMillis(deadlineNanos);
        plain.connect(new InetSocketAddress(address, port), Math.min(connectTimeoutMillis, remaining));
        plain.setSoTimeout(Math.min(readTimeoutMillis, remainingMillis(deadlineNanos)));
        if (!"https".equals(uri.getScheme())) {
            return plain;
        }
        SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sslSocketFactory.createSocket(plain, uri.getHost(), port, true);
        SSLParameters parameters = ssl.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (!uri.getHost().contains(":") && !uri.getHost().matches("[0-9.]+")) {
            parameters.setServerNames(List.of(new SNIHostName(uri.getHost())));
        }
        ssl.setSSLParameters(parameters);
        ssl.setSoTimeout(Math.min(readTimeoutMillis, remainingMillis(deadlineNanos)));
        ssl.startHandshake();
        return ssl;
    }

    private HttpResponse readResponse(InputStream raw, long deadlineNanos) throws IOException {
        BufferedInputStream input = new BufferedInputStream(raw);
        String statusLine = readLine(input, deadlineNanos);
        String[] statusParts = statusLine.split(" ", 3);
        if (statusParts.length < 2) {
            throw new IOException("Invalid HTTP response");
        }
        int status = Integer.parseInt(statusParts[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        int headerBytes = statusLine.length();
        String line;
        while (!(line = readLine(input, deadlineNanos)).isEmpty()) {
            headerBytes += line.length();
            if (headerBytes > MAX_HEADER_BYTES) {
                throw new IOException("Response headers are too large");
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.putIfAbsent(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
        }
        if (REDIRECT_STATUSES.contains(status) || status < 200 || status >= 300) {
            return new HttpResponse(status, headers, new byte[0]);
        }
        String contentType = headers.getOrDefault("content-type", "").toLowerCase(Locale.ROOT);
        if (!(contentType.startsWith("text/html") || contentType.startsWith("application/xhtml+xml"))) {
            throw new JobOfferExtractionException("The URL does not point to an HTML job page.");
        }
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            try {
                long declaredLength = Long.parseLong(contentLength);
                if (declaredLength < 0 || declaredLength > maxHtmlBytes) {
                    throw new JobOfferExtractionException("The job page is too large to import.");
                }
            } catch (NumberFormatException exception) {
                throw new JobOfferExtractionException("The job page returned an invalid content length.", exception);
            }
        }
        byte[] body;
        if (headers.getOrDefault("transfer-encoding", "").toLowerCase(Locale.ROOT).contains("chunked")) {
            body = readChunked(input, deadlineNanos);
        } else {
            body = readLimited(input, maxHtmlBytes, deadlineNanos);
        }
        return new HttpResponse(status, headers, body);
    }

    byte[] readChunked(BufferedInputStream input, long deadlineNanos) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(input, deadlineNanos);
            int semicolon = sizeLine.indexOf(';');
            String rawSize = (semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon)).trim();
            final BigInteger declaredSize;
            try {
                if (rawSize.isEmpty() || rawSize.startsWith("-") || rawSize.startsWith("+")) {
                    throw new NumberFormatException("Invalid chunk size");
                }
                declaredSize = new BigInteger(rawSize, 16);
            } catch (NumberFormatException exception) {
                throw new JobOfferExtractionException("The job page returned an invalid chunk size.", exception);
            }
            if (declaredSize.signum() == 0) {
                while (!readLine(input, deadlineNanos).isEmpty()) {
                    // Consume trailers.
                }
                return output.toByteArray();
            }
            int remainingCapacity = maxHtmlBytes - output.size();
            if (declaredSize.signum() < 0 || declaredSize.compareTo(BigInteger.valueOf(remainingCapacity)) > 0) {
                throw new JobOfferExtractionException("The job page is too large to import.");
            }
            int size = declaredSize.intValueExact();
            output.write(readExactly(input, size, deadlineNanos));
            if (!readLine(input, deadlineNanos).isEmpty()) {
                throw new IOException("Invalid chunk terminator");
            }
        }
    }

    byte[] decodeBody(byte[] body, String contentEncoding, long deadlineNanos) {
        ensureBeforeDeadline(deadlineNanos);
        if (contentEncoding == null || contentEncoding.isBlank() || "identity".equalsIgnoreCase(contentEncoding)) {
            return body;
        }
        if (!"gzip".equalsIgnoreCase(contentEncoding)) {
            throw new JobOfferExtractionException("The job page used an unsupported content encoding.");
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return readLimited(gzip, maxHtmlBytes, deadlineNanos);
        } catch (IOException exception) {
            throw new JobOfferExtractionException("The job page returned invalid compressed content.", exception);
        }
    }

    private byte[] readLimited(InputStream input, int limit, long deadlineNanos) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            ensureBeforeDeadline(deadlineNanos);
            if (output.size() + read > limit) {
                throw new JobOfferExtractionException("The job page is too large to import.");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private byte[] readExactly(InputStream input, int size, long deadlineNanos) throws IOException {
        byte[] result = new byte[size];
        int offset = 0;
        while (offset < size) {
            ensureBeforeDeadline(deadlineNanos);
            int read = input.read(result, offset, size - offset);
            if (read < 0) throw new EOFException("Unexpected end of response");
            offset += read;
        }
        return result;
    }

    private String readLine(InputStream input, long deadlineNanos) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            ensureBeforeDeadline(deadlineNanos);
            int current = input.read();
            if (current < 0) throw new EOFException("Unexpected end of response");
            if (previous == '\r' && current == '\n') {
                byte[] bytes = output.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.ISO_8859_1);
            }
            output.write(current);
            if (output.size() > MAX_HEADER_BYTES) throw new IOException("HTTP line is too long");
            previous = current;
        }
    }

    private void ensureBeforeDeadline(long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos) {
            throw new JobOfferExtractionException("The job page took too long to respond.");
        }
    }

    private int remainingMillis(long deadlineNanos) {
        long millis = Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime())).toMillis();
        if (millis <= 0) throw new JobOfferExtractionException("The job page took too long to respond.");
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }

    private String hostHeader(String host) {
        return host.contains(":") ? "[" + host + "]" : host;
    }

    private final class DeadlineInputStream extends FilterInputStream {
        private final Socket socket;
        private final long deadlineNanos;

        private DeadlineInputStream(InputStream delegate, Socket socket, long deadlineNanos) {
            super(delegate);
            this.socket = socket;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public int read() throws IOException {
            refreshTimeout();
            return super.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            refreshTimeout();
            return super.read(bytes, offset, length);
        }

        private void refreshTimeout() throws IOException {
            socket.setSoTimeout(Math.min(readTimeoutMillis, remainingMillis(deadlineNanos)));
        }
    }

    public record FetchedHtml(URI finalUri, String contentType, byte[] body) {
    }

    private record HttpResponse(int status, Map<String, String> headers, byte[] body) {
    }
}

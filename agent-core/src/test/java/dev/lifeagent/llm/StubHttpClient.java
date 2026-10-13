package dev.lifeagent.llm;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Deterministic JDK HttpClient test double; it never opens a socket. */
final class StubHttpClient extends HttpClient {

    private final Deque<ResponseSpec> responses = new ArrayDeque<>();
    private final List<HttpRequest> requests = new ArrayList<>();

    void enqueue(int status, String contentType, String body) {
        responses.addLast(new ResponseSpec(status, contentType, body, false));
    }

    void enqueueOneByteAtATime(int status, String contentType, String body) {
        responses.addLast(new ResponseSpec(status, contentType, body, true));
    }

    List<HttpRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> ignored)
            throws IOException {
        ResponseSpec spec = responses.pollFirst();
        if (spec == null) {
            throw new IOException("no queued response");
        }
        requests.add(request);
        byte[] bytes = spec.body.getBytes(StandardCharsets.UTF_8);
        InputStream body = spec.oneByteAtATime
                ? new OneByteAtATimeInputStream(bytes)
                : new ByteArrayInputStream(bytes);
        return (HttpResponse<T>) new StubResponse(request, spec, body);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler) {
        try {
            return CompletableFuture.completedFuture(send(request, handler));
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return sendAsync(request, handler);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        try {
            return SSLContext.getDefault();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }

    private record ResponseSpec(
            int status,
            String contentType,
            String body,
            boolean oneByteAtATime) {
    }

    private record StubResponse(
            HttpRequest request,
            ResponseSpec spec,
            InputStream body) implements HttpResponse<InputStream> {

        @Override
        public int statusCode() {
            return spec.status;
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of("Content-Type", List.of(spec.contentType)),
                    (name, value) -> true);
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public java.net.URI uri() {
            return request.uri();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }
    }

    private static final class OneByteAtATimeInputStream extends ByteArrayInputStream {

        private OneByteAtATimeInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) {
            return super.read(target, offset, Math.min(length, 1));
        }
    }
}

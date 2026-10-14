package dev.lifeagent.tool;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Deterministic JDK {@link HttpClient} double for the W2-1 hardening tests: it records every
 * {@link HttpRequest} it is handed - so a test can assert on the headers the adapters really put on
 * the wire - and replays one canned response. It never opens a socket.
 *
 * <p>This mirrors the package-private {@code dev.lifeagent.llm.StubHttpClient}, which tests living
 * in {@code dev.lifeagent.tool} cannot reach because they sit in a different package.
 */
final class RecordingHttpClient extends HttpClient {

    private final int status;
    private final String contentType;
    private final String body;
    private final List<HttpRequest> requests = new ArrayList<>();

    private RecordingHttpClient(int status, String contentType, String body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    /** A {@code 200 text/event-stream} response carrying {@code body} as the SSE payload. */
    static RecordingHttpClient sse(String body) {
        return new RecordingHttpClient(200, "text/event-stream", body);
    }

    /** A {@code 200 application/json} response, for the non-streaming shapes. */
    static RecordingHttpClient json(int status, String body) {
        return new RecordingHttpClient(status, "application/json", body);
    }

    List<HttpRequest> requests() {
        return List.copyOf(requests);
    }

    /** The single request the adapter is expected to have sent. */
    HttpRequest onlyRequest() {
        if (requests.size() != 1) {
            throw new AssertionError("expected exactly one request, got " + requests.size());
        }
        return requests.get(0);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> ignored)
            throws IOException {
        requests.add(request);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return (HttpResponse<T>) new RecordedResponse(
                request, status, contentType, new ByteArrayInputStream(bytes));
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

    private record RecordedResponse(
            HttpRequest request,
            int status,
            String contentType,
            InputStream body) implements HttpResponse<InputStream> {

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of("Content-Type", List.of(contentType)),
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
}

package dev.lifeagent.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

abstract class AbstractHttpLlmAdapter implements LlmClient {

    protected final ObjectMapper mapper;
    private final LlmHttpConfig config;
    private final HttpClient httpClient;
    private final Sleeper sleeper;
    private final DoubleSupplier randomSource;

    AbstractHttpLlmAdapter(LlmHttpConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(config.requestTimeout())
                .build(), Sleeper.SYSTEM, Math::random);
    }

    AbstractHttpLlmAdapter(
            LlmHttpConfig config,
            HttpClient httpClient,
            Sleeper sleeper,
            DoubleSupplier randomSource) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.randomSource = Objects.requireNonNull(randomSource, "randomSource");
        this.mapper = new ObjectMapper();
    }

    @Override
    public final void stream(
            LlmRequest request,
            Consumer<? super LlmStreamEvent> eventConsumer) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(eventConsumer, "eventConsumer");
        byte[] requestBody;
        try {
            requestBody = mapper.writeValueAsBytes(createRequestBody(request));
        } catch (JsonProcessingException e) {
            throw new LlmProtocolException("Could not encode LLM request", e);
        }

        int retries = 0;
        while (true) {
            HttpRequest httpRequest = createHttpRequest(requestBody);
            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("Interrupted while calling the LLM service", e);
            } catch (IOException e) {
                throw new LlmException("Could not call the LLM service", e);
            }

            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                parseSuccessfulResponse(response, eventConsumer);
                return;
            }

            closeErrorBody(response.body());
            if (isRetryableStatus(status) && retries < config.retryPolicy().maxRetries()) {
                sleepBeforeRetry(retries++);
                continue;
            }
            throw new LlmHttpException(status,
                    "LLM HTTP request failed with status " + status
                            + (isRetryableStatus(status) ? " after retry limit" : ""));
        }
    }

    protected abstract JsonNode createRequestBody(LlmRequest request);

    protected abstract boolean handleSseEvent(
            SseParser.SseEvent event,
            Consumer<? super LlmStreamEvent> eventConsumer,
            Object streamState);

    protected abstract Object newStreamState();

    protected abstract void validateStreamComplete(Object streamState);

    private HttpRequest createHttpRequest(byte[] requestBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(config.endpoint())
                .timeout(config.requestTimeout())
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setHeader("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody));
        config.headers().forEach(builder::setHeader);
        return builder.build();
    }

    private void parseSuccessfulResponse(
            HttpResponse<InputStream> response,
            Consumer<? super LlmStreamEvent> eventConsumer) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!isEventStream(contentType)) {
            closeErrorBody(response.body());
            throw new LlmProtocolException(
                    "LLM streaming response must have Content-Type text/event-stream");
        }
        Object state = newStreamState();
        try (InputStream body = response.body()) {
            SseParser.parse(body, event -> handleSseEvent(event, eventConsumer, state));
            validateStreamComplete(state);
        } catch (IOException e) {
            throw new LlmProtocolException("Could not read LLM SSE response", e);
        }
    }

    private void sleepBeforeRetry(int retryIndex) {
        double sample = randomSource.getAsDouble();
        try {
            sleeper.sleep(config.retryPolicy().delayForRetry(retryIndex, sample));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted during LLM retry backoff", e);
        }
    }

    private static boolean isEventStream(String contentType) {
        int semicolon = contentType.indexOf(';');
        String mediaType = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
        return "text/event-stream".equals(mediaType.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isRetryableStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503
                || status == 504 || status == 529;
    }

    private static void closeErrorBody(InputStream body) {
        if (body == null) {
            return;
        }
        try (body) {
            body.readNBytes(8_192);
        } catch (IOException ignored) {
            // The status code is authoritative; failure to drain an error body does not change it.
        }
    }

    protected final String writeJson(JsonNode node, String context) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new LlmProtocolException("Could not encode " + context, e);
        }
    }

    protected final JsonNode readEventJson(SseParser.SseEvent event) {
        try {
            JsonNode root = mapper.readTree(event.data().getBytes(StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                throw new LlmProtocolException("LLM SSE data must be a JSON object");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new LlmProtocolException(
                    "Invalid JSON in LLM SSE event " + event.event(), e);
        } catch (IOException e) {
            throw new LlmProtocolException(
                    "Could not read LLM SSE event " + event.event(), e);
        }
    }
}

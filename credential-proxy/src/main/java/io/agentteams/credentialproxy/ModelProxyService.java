package io.agentteams.credentialproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Enforces Worker, active Attempt, model, and provider credential scope before forwarding. */
public final class ModelProxyService {
    private static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final long LEASE_POLL_INTERVAL_MILLIS = 1_000;

    private final ModelProxyAuthenticator authenticator;
    private final AttemptExecutionContextResolver contexts;
    private final CredentialSource credentials;
    private final URI upstreamEndpoint;
    private final Set<String> allowedModels;
    private final HttpClient http;
    private final Clock clock;
    private final ScheduledExecutorService streamMonitor;
    private final Semaphore activeStreamPermits;
    private final ObjectMapper json = new ObjectMapper();

    public ModelProxyService(ModelProxyAuthenticator authenticator,
            AttemptExecutionContextResolver contexts,
            CredentialSource credentials,
            URI upstreamEndpoint,
            Set<String> allowedModels,
            HttpClient http,
            Clock clock,
            ScheduledExecutorService streamMonitor,
            Semaphore activeStreamPermits) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.upstreamEndpoint = validateEndpoint(upstreamEndpoint);
        this.allowedModels = Set.copyOf(Objects.requireNonNull(allowedModels, "allowedModels"));
        if (this.allowedModels.isEmpty() || this.allowedModels.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("allowedModels must not be empty");
        }
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.streamMonitor = Objects.requireNonNull(streamMonitor, "streamMonitor");
        this.activeStreamPermits = Objects.requireNonNull(activeStreamPermits, "activeStreamPermits");
    }

    public ModelProxyResponse forward(String workerToken, byte[] requestBody) {
        if (requestBody == null || requestBody.length == 0) throw new ModelProxyException(400);
        if (requestBody.length > MAX_REQUEST_BYTES) throw new ModelProxyException(413);

        final AuthenticatedWorker worker;
        try {
            worker = authenticator.authenticate(workerToken).orElseThrow(() -> new ModelProxyException(401));
        } catch (ModelProxyException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new ModelProxyException(503);
        }

        final AttemptExecutionContext context;
        try {
            context = contexts.resolve(worker);
        } catch (AttemptExecutionDeniedException denied) {
            throw new ModelProxyException(403);
        } catch (RuntimeException unavailable) {
            throw new ModelProxyException(503);
        }

        byte[] normalizedBody = normalizeRequest(requestBody);
        char[] providerToken = null;
        boolean permitAcquired = false;
        boolean streamTransferred = false;
        InputStream responseBody = null;
        ScheduledFuture<?> authorizationMonitor = null;
        AtomicReference<CompletableFuture<HttpResponse<InputStream>>> pendingRequest = new AtomicReference<>();
        AtomicReference<ModelProxyException> pendingDenial = new AtomicReference<>();
        AtomicBoolean responseHeadersReceived = new AtomicBoolean();
        try {
            if (!activeStreamPermits.tryAcquire()) throw new ModelProxyException(503);
            permitAcquired = true;
            providerToken = credentials.resolve(context);
            if (providerToken == null || providerToken.length == 0) throw new ModelProxyException(503);
            revalidateAttemptBeforeForward(worker, context);
            HttpRequest request = HttpRequest.newBuilder(upstreamEndpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + new String(providerToken))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(normalizedBody))
                    .build();
            authorizationMonitor = streamMonitor.scheduleWithFixedDelay(() -> {
                if (responseHeadersReceived.get()) return;
                try {
                    revalidateAttemptBeforeForward(worker, context);
                } catch (ModelProxyException denied) {
                    pendingDenial.compareAndSet(null, denied);
                    CompletableFuture<HttpResponse<InputStream>> requestFuture = pendingRequest.get();
                    if (requestFuture != null) requestFuture.cancel(true);
                }
            }, LEASE_POLL_INTERVAL_MILLIS, LEASE_POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
            CompletableFuture<HttpResponse<InputStream>> requestFuture = http.sendAsync(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            pendingRequest.set(requestFuture);
            if (pendingDenial.get() != null) requestFuture.cancel(true);
            HttpResponse<InputStream> response;
            try {
                response = requestFuture.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (CancellationException canceled) {
                ModelProxyException denied = pendingDenial.get();
                if (denied != null) throw denied;
                throw new ModelProxyException(503);
            } catch (ExecutionException failed) {
                ModelProxyException denied = pendingDenial.get();
                if (denied != null) throw denied;
                throw new ModelProxyException(502);
            } catch (TimeoutException timeout) {
                requestFuture.cancel(true);
                throw new ModelProxyException(502);
            }
            responseHeadersReceived.set(true);
            authorizationMonitor.cancel(false);
            ModelProxyException denied = pendingDenial.get();
            responseBody = response.body();
            if (denied != null) throw denied;
            revalidateAttemptBeforeForward(worker, context);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ModelProxyException(502);
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream")
                    && !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                throw new ModelProxyException(502);
            }
            InputStream guarded = new LeaseGuardedInputStream(responseBody, authenticator, contexts,
                    worker, context, clock, streamMonitor, activeStreamPermits, LEASE_POLL_INTERVAL_MILLIS);
            ModelProxyResponse proxyResponse;
            try {
                proxyResponse = new ModelProxyResponse(response.statusCode(), contentType, guarded);
            } catch (RuntimeException invalidResponse) {
                try {
                    guarded.close();
                } catch (IOException closeFailure) {
                    invalidResponse.addSuppressed(closeFailure);
                }
                responseBody = null;
                permitAcquired = false;
                throw invalidResponse;
            }
            responseBody = null;
            permitAcquired = false;
            streamTransferred = true;
            return proxyResponse;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ModelProxyException(503);
        } catch (IllegalArgumentException malformedProviderCredential) {
            throw new ModelProxyException(503);
        } catch (ModelProxyException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new ModelProxyException(503);
        } finally {
            responseHeadersReceived.set(true);
            if (authorizationMonitor != null) authorizationMonitor.cancel(false);
            CompletableFuture<HttpResponse<InputStream>> requestFuture = pendingRequest.get();
            if (requestFuture != null && !streamTransferred && responseBody == null) {
                requestFuture.cancel(true);
                requestFuture.thenAccept(response -> closeQuietly(response.body()));
            }
            if (responseBody != null) closeQuietly(responseBody);
            if (permitAcquired) activeStreamPermits.release();
            if (providerToken != null) Arrays.fill(providerToken, '\0');
            Arrays.fill(normalizedBody, (byte) 0);
        }
    }

    private void revalidateAttemptBeforeForward(AuthenticatedWorker worker, AttemptExecutionContext original) {
        final AuthenticatedWorker currentWorker;
        try {
            currentWorker = authenticator.revalidate(worker).orElseThrow(() -> new ModelProxyException(403));
        } catch (ModelProxyException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new ModelProxyException(503);
        }
        if (!worker.equals(currentWorker) || !clock.instant().isBefore(original.expiresAt())) {
            throw new ModelProxyException(403);
        }
        final AttemptExecutionContext current;
        try {
            current = contexts.resolve(currentWorker);
        } catch (AttemptExecutionDeniedException denied) {
            throw new ModelProxyException(403);
        } catch (RuntimeException unavailable) {
            throw new ModelProxyException(503);
        }
        if (!original.sameExecutionAs(current)) {
            throw new ModelProxyException(403);
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The request is already being rejected; closing is best-effort cleanup.
        }
    }

    private byte[] normalizeRequest(byte[] requestBody) {
        try {
            JsonNode parsed = json.readTree(requestBody);
            if (parsed == null || !parsed.isObject()) throw new ModelProxyException(400);
            JsonNode model = parsed.get("model");
            if (model == null || !model.isTextual() || !allowedModels.contains(model.textValue())) {
                throw new ModelProxyException(400);
            }
            ObjectNode normalized = ((ObjectNode) parsed).deepCopy();
            normalized.put("stream", true);
            normalized.put("store", false);
            return json.writeValueAsBytes(normalized);
        } catch (ModelProxyException denied) {
            throw denied;
        } catch (IOException malformed) {
            throw new ModelProxyException(400);
        }
    }

    private static URI validateEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "upstreamEndpoint");
        String scheme = endpoint.getScheme();
        boolean secure = "https".equalsIgnoreCase(scheme);
        boolean localHttp = "http".equalsIgnoreCase(scheme) && isLoopback(endpoint.getHost());
        if ((!secure && !localHttp) || endpoint.getHost() == null || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || endpoint.getPath() == null || !endpoint.getPath().endsWith("/v1/responses")) {
            throw new IllegalArgumentException("upstream endpoint is invalid");
        }
        return endpoint;
    }

    private static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception invalidHost) {
            return false;
        }
    }
}

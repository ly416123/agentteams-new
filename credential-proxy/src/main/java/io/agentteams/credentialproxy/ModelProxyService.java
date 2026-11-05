package io.agentteams.credentialproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/** Enforces Worker, active Attempt, model, and provider credential scope before forwarding. */
public final class ModelProxyService {
    private static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private final ModelProxyAuthenticator authenticator;
    private final AttemptExecutionContextResolver contexts;
    private final CredentialSource credentials;
    private final URI upstreamEndpoint;
    private final Set<String> allowedModels;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public ModelProxyService(ModelProxyAuthenticator authenticator,
            AttemptExecutionContextResolver contexts,
            CredentialSource credentials,
            URI upstreamEndpoint,
            Set<String> allowedModels,
            HttpClient http) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.upstreamEndpoint = validateEndpoint(upstreamEndpoint);
        this.allowedModels = Set.copyOf(Objects.requireNonNull(allowedModels, "allowedModels"));
        if (this.allowedModels.isEmpty() || this.allowedModels.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("allowedModels must not be empty");
        }
        this.http = Objects.requireNonNull(http, "http");
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
        final char[] providerToken;
        try {
            providerToken = credentials.resolve(context);
        } catch (RuntimeException unavailable) {
            throw new ModelProxyException(503);
        }
        if (providerToken == null || providerToken.length == 0) {
            if (providerToken != null) Arrays.fill(providerToken, '\0');
            throw new ModelProxyException(503);
        }

        try {
            revalidateAttemptBeforeForward(worker, context);
            HttpRequest request = HttpRequest.newBuilder(upstreamEndpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + new String(providerToken))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(normalizedBody))
                    .build();
            HttpResponse<java.io.InputStream> response = http.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new ModelProxyException(502);
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream")
                    && !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                response.body().close();
                throw new ModelProxyException(502);
            }
            return new ModelProxyResponse(response.statusCode(), contentType, response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ModelProxyException(503);
        } catch (IOException transportFailure) {
            throw new ModelProxyException(502);
        } catch (IllegalArgumentException malformedProviderCredential) {
            throw new ModelProxyException(503);
        } finally {
            Arrays.fill(providerToken, '\0');
            Arrays.fill(normalizedBody, (byte) 0);
        }
    }

    private void revalidateAttemptBeforeForward(AuthenticatedWorker worker, AttemptExecutionContext original) {
        final AttemptExecutionContext current;
        try {
            current = contexts.resolve(worker);
        } catch (AttemptExecutionDeniedException denied) {
            throw new ModelProxyException(403);
        } catch (RuntimeException unavailable) {
            throw new ModelProxyException(503);
        }
        if (current.version() != original.version()
                || !current.tenantId().equals(original.tenantId())
                || !current.projectId().equals(original.projectId())
                || !current.teamId().equals(original.teamId())
                || !current.agentId().equals(original.agentId())
                || !current.taskId().equals(original.taskId())
                || !current.attemptId().equals(original.attemptId())
                || !current.leaseId().equals(original.leaseId())
                || !current.modelCredentialRef().equals(original.modelCredentialRef())) {
            throw new ModelProxyException(403);
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

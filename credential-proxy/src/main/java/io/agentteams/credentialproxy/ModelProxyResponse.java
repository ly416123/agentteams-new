package io.agentteams.credentialproxy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public record ModelProxyResponse(int statusCode, String contentType, InputStream body) implements AutoCloseable {
    public ModelProxyResponse {
        if (statusCode < 200 || statusCode >= 300) throw new IllegalArgumentException("response must be successful");
        if (contentType == null || contentType.isBlank()) throw new IllegalArgumentException("contentType is required");
        Objects.requireNonNull(body, "body");
    }

    @Override
    public void close() throws IOException {
        body.close();
    }
}

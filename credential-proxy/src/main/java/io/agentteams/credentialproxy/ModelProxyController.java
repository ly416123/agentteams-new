package io.agentteams.credentialproxy;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public final class ModelProxyController {
    private static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;
    private final ModelProxyService service;

    public ModelProxyController(ModelProxyService service) {
        this.service = service;
    }

    @PostMapping(path = "/v1/responses", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> responses(HttpServletRequest request) throws IOException {
        String token = bearerToken(request.getHeader("Authorization"));
        byte[] requestBody = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
        if (requestBody.length > MAX_REQUEST_BYTES) {
            Arrays.fill(requestBody, (byte) 0);
            throw new ModelProxyException(413);
        }
        final ModelProxyResponse upstream;
        try {
            upstream = service.forward(token, requestBody);
        } finally {
            Arrays.fill(requestBody, (byte) 0);
        }
        StreamingResponseBody stream = output -> {
            try (upstream) {
                upstream.body().transferTo(output);
            }
        };
        return ResponseEntity.status(upstream.statusCode())
                .contentType(MediaType.parseMediaType(upstream.contentType()))
                .body(stream);
    }

    @ExceptionHandler(ModelProxyException.class)
    public ResponseEntity<Map<String, String>> denied(ModelProxyException error) {
        return ResponseEntity.status(error.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "model_proxy_request_denied"));
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || authorization.length() <= 7
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ModelProxyException(401);
        }
        String token = authorization.substring(7);
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new ModelProxyException(401);
        }
        return token;
    }
}

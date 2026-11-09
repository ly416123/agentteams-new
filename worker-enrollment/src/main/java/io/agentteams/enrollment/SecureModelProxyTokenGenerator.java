package io.agentteams.enrollment;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public final class SecureModelProxyTokenGenerator implements ModelProxyTokenGenerator {
    private final SecureRandom random;

    public SecureModelProxyTokenGenerator() {
        this(new SecureRandom());
    }

    SecureModelProxyTokenGenerator(SecureRandom random) {
        this.random = java.util.Objects.requireNonNull(random, "random");
    }

    @Override
    public String generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return "atp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

package io.agentteams.enrollment;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public final class SecureWorkerTokenGenerator implements WorkerTokenGenerator {
    private final SecureRandom random;

    public SecureWorkerTokenGenerator() {
        this(new SecureRandom());
    }

    SecureWorkerTokenGenerator(SecureRandom random) {
        this.random = java.util.Objects.requireNonNull(random, "random");
    }

    @Override
    public String generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return "ats_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

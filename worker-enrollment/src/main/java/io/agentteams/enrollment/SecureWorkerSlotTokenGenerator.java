package io.agentteams.enrollment;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class SecureWorkerSlotTokenGenerator implements WorkerSlotTokenGenerator {
    private final SecureRandom random;

    public SecureWorkerSlotTokenGenerator() {
        this(new SecureRandom());
    }

    SecureWorkerSlotTokenGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public String generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return "wslot_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

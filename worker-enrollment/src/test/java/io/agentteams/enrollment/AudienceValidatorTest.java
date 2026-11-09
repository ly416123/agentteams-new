package io.agentteams.enrollment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {
    @Test
    void acceptsOnlyTokensAddressedToThisPlatform() {
        AudienceValidator validator = new AudienceValidator("agentteams-api");
        Jwt accepted = token(List.of("agentteams-api", "other-api"));
        Jwt rejected = token(List.of("other-api"));

        assertThat(validator.validate(accepted).hasErrors()).isFalse();
        assertThat(validator.validate(rejected).hasErrors()).isTrue();
    }

    private static Jwt token(List<String> audience) {
        Instant now = Instant.parse("2026-11-04T00:00:00Z");
        return Jwt.withTokenValue("test").header("alg", "none").subject("operator")
                .issuedAt(now).expiresAt(now.plusSeconds(300)).audience(audience).build();
    }
}

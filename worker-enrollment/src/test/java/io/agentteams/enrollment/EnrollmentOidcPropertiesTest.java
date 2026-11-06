package io.agentteams.enrollment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EnrollmentOidcPropertiesTest {
    @Test
    void requiresIssuerJwksAndAudienceBeforeTheServiceCanStart() {
        EnrollmentOidcProperties properties = new EnrollmentOidcProperties();

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer-uri");
    }

    @Test
    void acceptsCompleteProviderAgnosticOidcConfiguration() {
        EnrollmentOidcProperties properties = new EnrollmentOidcProperties();
        properties.setIssuerUri("https://identity.example.test/issuer");
        properties.setJwkSetUri("https://identity.example.test/keys");
        properties.setAudience("agentteams-api");

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonAbsoluteJwksAddress() {
        EnrollmentOidcProperties properties = new EnrollmentOidcProperties();
        properties.setIssuerUri("https://identity.example.test/issuer");
        properties.setJwkSetUri("/keys");
        properties.setAudience("agentteams-api");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwk-set-uri");
    }

    @Test
    void rejectsRemoteHttpIssuerAndJwksUrls() {
        EnrollmentOidcProperties properties = new EnrollmentOidcProperties();
        properties.setIssuerUri("http://identity.example.test/issuer");
        properties.setJwkSetUri("https://identity.example.test/keys");
        properties.setAudience("agentteams-api");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer-uri must use HTTPS");

        properties.setIssuerUri("https://identity.example.test/issuer");
        properties.setJwkSetUri("http://identity.example.test/keys");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwk-set-uri must use HTTPS");
    }

    @Test
    void permitsLoopbackHttpJwksOnlyAfterAnExplicitDevelopmentOptIn() {
        EnrollmentOidcProperties properties = new EnrollmentOidcProperties();
        properties.setIssuerUri("https://identity.example.test/issuer");
        properties.setJwkSetUri("http://127.0.0.1:18080/keys");
        properties.setAudience("agentteams-api");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwk-set-uri must use HTTPS");

        properties.setAllowInsecureLoopback(true);
        assertThatCode(properties::validate).doesNotThrowAnyException();

        properties.setIssuerUri("http://localhost:18080/issuer");
        properties.setJwkSetUri("http://127.0.0.1:18080/keys");
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }
}

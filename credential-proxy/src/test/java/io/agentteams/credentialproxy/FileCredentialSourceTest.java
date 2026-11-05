package io.agentteams.credentialproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCredentialSourceTest {
    @TempDir
    Path secretRoot;

    @Test
    void readsASecretFromAnAllowedRelativeReference() throws Exception {
        Path secret = secretRoot.resolve("tenant-a/project-a/team-a/model-token");
        Files.createDirectories(secret.getParent());
        Files.writeString(secret, "provider-secret\n");

        char[] value = new FileCredentialSource(secretRoot)
                .resolve(context("file:tenant-a/project-a/team-a/model-token"));

        assertThat(value).containsExactly("provider-secret".toCharArray());
    }

    @Test
    void rejectsReferencesThatEscapeTheSecretRoot() {
        var source = new FileCredentialSource(secretRoot);

        assertThatThrownBy(() -> source.resolve(context("file:tenant-a/project-a/team-a/../../outside")))
                .isInstanceOf(CredentialSourceException.class);
        assertThatThrownBy(() -> source.resolve(context(
                "file:tenant-a/project-a/team-a/../../../tenant-b/project-b/team-b/model-token")))
                .isInstanceOf(CredentialSourceException.class);
        assertThatThrownBy(() -> source.resolve(context("file:/etc/passwd")))
                .isInstanceOf(CredentialSourceException.class);
    }

    @Test
    void rejectsSymlinksThatEscapeTheSecretRoot() throws Exception {
        Path outside = secretRoot.getParent().resolve("outside-provider-token");
        Files.writeString(outside, "provider-secret");
        Path escape = secretRoot.resolve("tenant-a/project-a/team-a/escape");
        Files.createDirectories(escape.getParent());
        Files.createSymbolicLink(escape, outside);

        assertThatThrownBy(() -> new FileCredentialSource(secretRoot)
                .resolve(context("file:tenant-a/project-a/team-a/escape")))
                .isInstanceOf(CredentialSourceException.class);
    }

    @Test
    void rejectsMissingAndEmptySecretFiles() throws Exception {
        Path empty = secretRoot.resolve("tenant-a/project-a/team-a/empty");
        Files.createDirectories(empty.getParent());
        Files.createFile(empty);
        var source = new FileCredentialSource(secretRoot);

        assertThatThrownBy(() -> source.resolve(context("file:tenant-a/project-a/team-a/missing")))
                .isInstanceOf(CredentialSourceException.class);
        assertThatThrownBy(() -> source.resolve(context("file:tenant-a/project-a/team-a/empty")))
                .isInstanceOf(CredentialSourceException.class);
    }

    @Test
    void rejectsOversizedSecretFiles() throws Exception {
        Path oversized = secretRoot.resolve("tenant-a/project-a/team-a/oversized");
        Files.createDirectories(oversized.getParent());
        Files.write(oversized, new byte[64 * 1024 + 1]);

        assertThatThrownBy(() -> new FileCredentialSource(secretRoot)
                .resolve(context("file:tenant-a/project-a/team-a/oversized")))
                .isInstanceOf(CredentialSourceException.class);
    }

    @Test
    void rejectsCredentialReferencesOutsideTheAuthorizedTenantAndProject() throws Exception {
        Path secret = secretRoot.resolve("tenant-b/project-b/team-b/model-token");
        Files.createDirectories(secret.getParent());
        Files.writeString(secret, "tenant-b-provider-secret");

        assertThatThrownBy(() -> new FileCredentialSource(secretRoot)
                .resolve(context("file:tenant-b/project-b/team-b/model-token")))
                .isInstanceOf(CredentialSourceException.class);
    }

    @Test
    void rejectsCredentialReferencesOutsideTheAuthorizedTeam() throws Exception {
        Path secret = secretRoot.resolve("tenant-a/project-a/team-b/model-token");
        Files.createDirectories(secret.getParent());
        Files.writeString(secret, "team-b-provider-secret");

        assertThatThrownBy(() -> new FileCredentialSource(secretRoot)
                .resolve(context("file:tenant-a/project-a/team-b/model-token")))
                .isInstanceOf(CredentialSourceException.class);
    }

    private static AttemptExecutionContext context(String credentialRef) {
        Instant issuedAt = Instant.parse("2026-11-05T10:00:00Z");
        return new AttemptExecutionContext(1, "tenant-a", "project-a", "team-a",
                UUID.fromString("88000000-0000-0000-0000-000000000001"),
                UUID.fromString("88000000-0000-0000-0000-000000000002"),
                UUID.fromString("88000000-0000-0000-0000-000000000003"),
                UUID.fromString("88000000-0000-0000-0000-000000000004"), credentialRef,
                issuedAt, issuedAt.plusSeconds(60));
    }
}

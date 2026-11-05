package io.agentteams.credentialproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCredentialSourceTest {
    @TempDir
    Path secretRoot;

    @Test
    void readsASecretFromAnAllowedRelativeReference() throws Exception {
        Path secret = secretRoot.resolve("tenant-a/project-a/model-token");
        Files.createDirectories(secret.getParent());
        Files.writeString(secret, "provider-secret\n");

        char[] value = new FileCredentialSource(secretRoot).resolve("file:tenant-a/project-a/model-token");

        assertThat(value).containsExactly("provider-secret".toCharArray());
    }

    @Test
    void rejectsReferencesThatEscapeTheSecretRoot() {
        var source = new FileCredentialSource(secretRoot);

        assertThatThrownBy(() -> source.resolve("file:../outside"))
                .isInstanceOf(CredentialSourceException.class);
        assertThatThrownBy(() -> source.resolve("file:/etc/passwd"))
                .isInstanceOf(CredentialSourceException.class);
    }

    @Test
    void rejectsSymlinksThatEscapeTheSecretRoot() throws Exception {
        Path outside = secretRoot.getParent().resolve("outside-provider-token");
        Files.writeString(outside, "provider-secret");
        Files.createSymbolicLink(secretRoot.resolve("escape"), outside);

        assertThatThrownBy(() -> new FileCredentialSource(secretRoot).resolve("file:escape"))
                .isInstanceOf(CredentialSourceException.class);
    }

    @Test
    void rejectsMissingAndEmptySecretFiles() throws Exception {
        Files.createFile(secretRoot.resolve("empty"));
        var source = new FileCredentialSource(secretRoot);

        assertThatThrownBy(() -> source.resolve("file:missing"))
                .isInstanceOf(CredentialSourceException.class);
        assertThatThrownBy(() -> source.resolve("file:empty"))
                .isInstanceOf(CredentialSourceException.class);
    }

    @Test
    void rejectsOversizedSecretFiles() throws Exception {
        Files.write(secretRoot.resolve("oversized"), new byte[64 * 1024 + 1]);

        assertThatThrownBy(() -> new FileCredentialSource(secretRoot).resolve("file:oversized"))
                .isInstanceOf(CredentialSourceException.class);
    }
}

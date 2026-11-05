package io.agentteams.credentialproxy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.io.InputStream;
import java.util.Objects;

/** Resolves {@code file:<relative-path>} references beneath one mounted secret directory. */
public final class FileCredentialSource implements CredentialSource {
    private static final String SCHEME = "file:";
    private static final int MAX_SECRET_BYTES = 64 * 1024;

    private final Path root;

    public FileCredentialSource(Path root) {
        Objects.requireNonNull(root, "root");
        try {
            this.root = root.toRealPath();
            if (!Files.isDirectory(this.root)) throw new CredentialSourceException();
        } catch (IOException error) {
            throw new CredentialSourceException();
        }
    }

    @Override
    public char[] resolve(String reference) {
        Path path = resolvePath(reference);
        byte[] bytes = null;
        try {
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(root) || !Files.isRegularFile(realPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new CredentialSourceException();
            }
            try (InputStream input = Files.newInputStream(realPath, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_SECRET_BYTES + 1);
            }
            if (bytes.length > MAX_SECRET_BYTES) throw new CredentialSourceException();
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            char[] chars = new char[decoded.remaining()];
            decoded.get(chars);
            if (decoded.hasArray()) java.util.Arrays.fill(decoded.array(), '\0');
            int start = 0;
            int end = chars.length;
            while (start < end && Character.isWhitespace(chars[start])) start++;
            while (end > start && Character.isWhitespace(chars[end - 1])) end--;
            if (start == end) {
                java.util.Arrays.fill(chars, '\0');
                throw new CredentialSourceException();
            }
            char[] secret = java.util.Arrays.copyOfRange(chars, start, end);
            java.util.Arrays.fill(chars, '\0');
            return secret;
        } catch (IOException | InvalidPathException error) {
            throw new CredentialSourceException();
        } finally {
            if (bytes != null) java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private Path resolvePath(String reference) {
        if (reference == null || !reference.startsWith(SCHEME) || reference.length() == SCHEME.length()) {
            throw new CredentialSourceException();
        }
        String relative = reference.substring(SCHEME.length());
        try {
            Path relativePath = Path.of(relative);
            if (relativePath.isAbsolute() || relativePath.normalize().startsWith("..")) {
                throw new CredentialSourceException();
            }
            Path resolved = root.resolve(relativePath).normalize();
            if (!resolved.startsWith(root) || resolved.equals(root)) throw new CredentialSourceException();
            return resolved;
        } catch (InvalidPathException error) {
            throw new CredentialSourceException();
        }
    }
}

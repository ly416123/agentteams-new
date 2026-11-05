package io.agentteams.credentialproxy;

public final class ModelProxyException extends RuntimeException {
    private final int statusCode;

    public ModelProxyException(int statusCode) {
        super("model proxy request was denied");
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}

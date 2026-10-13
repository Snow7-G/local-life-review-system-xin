package dev.lifeagent.llm;

public final class LlmHttpException extends LlmException {

    private final int statusCode;

    public LlmHttpException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}

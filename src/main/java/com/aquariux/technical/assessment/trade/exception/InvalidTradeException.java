package com.aquariux.technical.assessment.trade.exception;

/** The request is well formed but breaks a trading rule, such as an unsupported pair. Returned as 400. */
public class InvalidTradeException extends RuntimeException {

    /** Lets a client tell two causes of a 400 apart; null when one message is enough. */
    private final String code;

    public InvalidTradeException(String message) {
        this(message, null);
    }

    protected InvalidTradeException(String message, String code) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

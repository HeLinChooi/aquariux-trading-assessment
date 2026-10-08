package com.aquariux.technical.assessment.trade.exception;

/** The request is well formed but breaks a trading rule, such as an unsupported pair. Returned as 400. */
public class InvalidTradeException extends RuntimeException {
    public InvalidTradeException(String message) {
        super(message);
    }
}

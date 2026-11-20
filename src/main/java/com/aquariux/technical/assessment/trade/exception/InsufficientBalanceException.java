package com.aquariux.technical.assessment.trade.exception;

/** The wallet exists but cannot cover the trade. Returned as 400. */
public class InsufficientBalanceException extends InvalidTradeException {
    public InsufficientBalanceException(String message) {
        super(message, "INSUFFICIENT_BALANCE");
    }
}

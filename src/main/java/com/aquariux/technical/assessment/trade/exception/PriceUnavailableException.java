package com.aquariux.technical.assessment.trade.exception;

/** No price, or no recent enough price, to trade at. Returned as 503: retrying later can succeed. */
public class PriceUnavailableException extends RuntimeException {
    public PriceUnavailableException(String message) {
        super(message);
    }
}

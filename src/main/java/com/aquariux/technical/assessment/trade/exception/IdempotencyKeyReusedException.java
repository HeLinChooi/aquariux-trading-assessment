package com.aquariux.technical.assessment.trade.exception;

/**
 * An Idempotency-Key that already belongs to a different trade. Returned as 422,
 * as draft-ietf-httpapi-idempotency-key-header-08 recommends for a key reused
 * with a different request.
 */
public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException(String message) {
        super(message);
    }
}

package com.aquariux.technical.assessment.trade.exception;

/** A referenced record, such as a user, does not exist. Returned as 404. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}

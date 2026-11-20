package com.aquariux.technical.assessment.trade.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/** The body of every error response. */
@Data
@AllArgsConstructor
public class ErrorResponse {
    private LocalDateTime timestamp;
    private int status;
    private String error;
    private String message;

    /** Present only where a client must tell two causes apart, such as WALLET_NOT_FOUND. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String code;
}

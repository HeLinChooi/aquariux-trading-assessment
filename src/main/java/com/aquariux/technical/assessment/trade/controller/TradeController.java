package com.aquariux.technical.assessment.trade.controller;

import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.ErrorResponse;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;
import com.aquariux.technical.assessment.trade.service.TradeServiceInterface;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trades")
@Tag(name = "Trade", description = "Trading operations")
@RequiredArgsConstructor
public class TradeController {

    private final TradeServiceInterface tradeService;

    @PostMapping(value = "/execute", produces = "application/json")
    @Operation(summary = "Execute trade",
            description = "Buy or sell the base coin of a pair at the latest best price: the ask for a BUY, the bid for a SELL")
    @ApiResponse(responseCode = "201", description = "Trade executed")
    @ApiResponse(responseCode = "400", description = "Invalid request, missing Idempotency-Key, unsupported pair, "
            + "missing wallet or insufficient balance",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "User not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "The Idempotency-Key was already used for a different trade",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "No recent price available",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<TradeResponse> executeTrade(
            @Valid @RequestBody TradeRequest tradeRequest,
            @Parameter(description = "Required, up to 64 characters, for example a UUID. Send the same key "
                    + "when retrying: a repeat returns the first trade instead of trading again")
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tradeService.executeTrade(tradeRequest, idempotencyKey));
    }
}

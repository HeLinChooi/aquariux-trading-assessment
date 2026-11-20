package com.aquariux.technical.assessment.trade.exception;

/** The user has no wallet for the coin the trade must pay with. Returned as 400. */
public class WalletNotFoundException extends InvalidTradeException {
    public WalletNotFoundException(String message) {
        super(message, "WALLET_NOT_FOUND");
    }
}

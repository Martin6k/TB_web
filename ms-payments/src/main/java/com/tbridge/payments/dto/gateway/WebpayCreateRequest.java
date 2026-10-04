package com.tbridge.payments.dto.gateway;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WebpayCreateRequest(
        @JsonProperty("buy_order") String buyOrder,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("amount") long amount,
        @JsonProperty("return_url") String returnUrl
) {
}

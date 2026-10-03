package com.tbridge.payments.dto.gateway;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WebpayCreateResponse(
        @JsonProperty("token") String token,
        @JsonProperty("url") String url
) {
}

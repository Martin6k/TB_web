package com.tbridge.payments.dto.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WebpayCreateResponse(
        @JsonProperty("token") String token,
        @JsonProperty("url") String url
) {
}

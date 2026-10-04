package com.tbridge.payments.dto.gateway;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WebpayCommitResponse(
        @JsonProperty("vci") String vci,
        @JsonProperty("amount") Long amount,
        @JsonProperty("status") String status,
        @JsonProperty("buy_order") String buyOrder,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("card_detail") CardDetail cardDetail,
        @JsonProperty("accounting_date") String accountingDate,
        @JsonProperty("transaction_date") String transactionDate,
        @JsonProperty("authorization_code") String authorizationCode,
        @JsonProperty("payment_type_code") String paymentTypeCode,
        @JsonProperty("response_code") Integer responseCode,
        @JsonProperty("installments_amount") Long installmentsAmount,
        @JsonProperty("installments_number") Integer installmentsNumber,
        @JsonProperty("balance") Long balance
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CardDetail(
            @JsonProperty("card_number") String cardNumber
    ) {}

    /** Aprobada solo con las dos cosas: AUTHORIZED y codigo de respuesta 0. Sin codigo, no. */
    @JsonIgnore
    public boolean isAuthorized() {
        return "AUTHORIZED".equals(status) && responseCode != null && responseCode == 0;
    }
}

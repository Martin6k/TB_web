package com.tbridge.payments.dto.response;

import com.tbridge.payments.model.Payment;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.server.core.Relation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un pago, como lo ve quien tiene derecho a verlo.
 *
 * <p>No lleva los RUT ni el id de la pasarela: quien pregunta ya sabe quien es,
 * y el id de la transaccion es un dato de conciliacion, no del portal.
 */
@Relation(collectionRelation = "payments", itemRelation = "payment")
@Schema(description = "Un intento de pago y su estado")
public record PaymentResponse(
        @Schema(example = "41") Long id,
        @Schema(example = "3") Long debtId,
        @Schema(nullable = true, example = "12") Long installmentId,
        @Schema(description = "En la moneda de la deuda", example = "38.50") BigDecimal amount,
        @Schema(example = "UF") Payment.Currency currency,
        @Schema(description = "Lo que se cobra en pesos. En UF, al valor del dia en que se abrio el cobro",
                example = "1535216") Long amountClp,
        @Schema(description = "El valor de la UF usado. Solo en deudas en UF", nullable = true, example = "39876.54")
        BigDecimal ufValue,
        @Schema(example = "webpay") Payment.Gateway gateway,
        @Schema(example = "created") Payment.Status status,
        @Schema(nullable = true) Instant paidAt,
        Instant createdAt,
        @Schema(description = "Solo al abrir el cobro: a donde se va a pagar", nullable = true,
                example = "http://localhost:8080/pasarela/41?sig=...")
        String checkoutUrl,
        @Schema(description = "Si la pasarela es una simulacion. Khipu cobra de verdad cuando hay KHIPU_LLAVE",
                example = "false")
        boolean simulada
) {

    public static PaymentResponse from(Payment pago, boolean simulada) {
        return new PaymentResponse(pago.getId(), pago.getDebtId(), pago.getInstallmentId(), pago.getAmount(),
                pago.getCurrency(), pago.getAmountClp(), pago.getUfValue(), pago.getGateway(), pago.getStatus(),
                pago.getPaidAt(), pago.getCreatedAt(), null, simulada);
    }

    public PaymentResponse conEnlaceDePago(String url) {
        return new PaymentResponse(id, debtId, installmentId, amount, currency, amountClp, ufValue, gateway, status,
                paidAt, createdAt, url, simulada);
    }
}

package com.tbridge.payments.repository;

import com.tbridge.payments.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** Lo que ve un deudor: lo suyo, por RUT. */
    List<Payment> findByDebtorRutOrderByCreatedAtDesc(String debtorRut);

    /** El pago de una pasarela por su id de transaccion (en Khipu, el payment_id). */
    Optional<Payment> findByGatewayAndGatewayTxnId(Payment.Gateway gateway, String gatewayTxnId);

    /** Los pagos de una pasarela en un estado: los cobros de Khipu que siguen abiertos. */
    List<Payment> findByGatewayAndStatus(Payment.Gateway gateway, Payment.Status status);

    /**
     * Lo que ve un acreedor: SOLO lo suyo.
     *
     * El modelo anterior devolvia findAll() para cualquier acreedor, asi que
     * todos veian los pagos de todos. No era un descuido del codigo: no habia
     * columna por la que filtrar.
     */
    List<Payment> findByCreditorRutOrderByCreatedAtDesc(String creditorRut);

    java.util.Optional<Payment> findByGatewayAndGatewayTxnId(Payment.Gateway gateway, String gatewayTxnId);
}

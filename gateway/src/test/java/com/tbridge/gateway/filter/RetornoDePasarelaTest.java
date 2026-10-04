package com.tbridge.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * La vuelta anulada desde Webpay llega con el Origin de Transbank: sin quitarlo,
 * el CORS del gateway la rechazaria. En cualquier otra ruta el Origin se queda.
 */
class RetornoDePasarelaTest {

    private final RetornoDePasarela filtro = new RetornoDePasarela();

    private ServerWebExchange pasar(MockServerHttpRequest pedido) {
        AtomicReference<ServerWebExchange> recibido = new AtomicReference<>();
        filtro.filter(MockServerWebExchange.from(pedido), e -> {
            recibido.set(e);
            return Mono.empty();
        }).block();
        return recibido.get();
    }

    @Test
    void la_vuelta_de_webpay_pasa_sin_el_origin_de_transbank() {
        ServerWebExchange e = pasar(MockServerHttpRequest.post(RetornoDePasarela.RUTA)
                .header(HttpHeaders.ORIGIN, "https://webpay3gint.transbank.cl").build());

        assertNull(e.getRequest().getHeaders().getFirst(HttpHeaders.ORIGIN));
    }

    @Test
    void en_las_demas_rutas_el_origin_se_queda() {
        ServerWebExchange e = pasar(MockServerHttpRequest.post("/api/payments/checkout")
                .header(HttpHeaders.ORIGIN, "https://otra-pagina.example").build());

        assertEquals("https://otra-pagina.example", e.getRequest().getHeaders().getFirst(HttpHeaders.ORIGIN));
    }
}

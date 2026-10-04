package com.tbridge.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * La vuelta desde Webpay no es una peticion de otra pagina: es el navegador del
 * deudor que vuelve, navegando, desde el sitio de Transbank.
 *
 * <p>Cuando el deudor anula el pago, Webpay lo devuelve con un formulario POST,
 * y el navegador le pone {@code Origin: https://webpay3gint.transbank.cl}. El
 * CORS del gateway veria un origen que no conoce y responderia 403: el deudor
 * se quedaria en una pagina de error en vez de ver que el pago se anulo.
 *
 * <p>Por eso, solo en esa ruta, se quita el Origin antes de que el gateway
 * revise el CORS. No abre nada: CORS protege las lecturas que una pagina ajena
 * hace con el navegador de otro, y esta ruta no devuelve datos, solo redirige
 * al resultado. Va como WebFilter, y no como filtro del gateway, porque el CORS
 * se revisa antes que estos.
 */
@Component
public class RetornoDePasarela implements WebFilter, Ordered {

    static final String RUTA = "/api/payments/public/webpay/retorno";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest pedido = exchange.getRequest();
        if (!RUTA.equals(pedido.getURI().getPath()) || pedido.getHeaders().getFirst(HttpHeaders.ORIGIN) == null) {
            return chain.filter(exchange);
        }
        ServerHttpRequest sinOrigen = pedido.mutate().headers(h -> h.remove(HttpHeaders.ORIGIN)).build();
        return chain.filter(exchange.mutate().request(sinOrigen).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

package com.tbridge.debt.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * La documentacion de ms-debt en Swagger, en tres grupos segun quien llama:
 *
 * <ul>
 *   <li><b>portal</b>: el navegador del deudor o de la empresa, con el JWT.</li>
 *   <li><b>integracion</b>: el contrato v1, que usan los sistemas de las
 *       agencias y acreedores con su clave de API.</li>
 *   <li><b>interno</b>: los otros servicios, con la clave interna. El gateway
 *       no lo expone, asi que apunta directo al servicio.</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    public static final String JWT = "jwt";
    public static final String CLAVE_API = "clave-api";
    public static final String CLAVE_INTERNA = "clave-interna";

    @Bean
    public OpenAPI documentacion(@Value("${app.version:1.0.0}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("ms-debt · Deudas")
                        .version(version)
                        .description("""
                                Las deudas de DataBridge: la cartera que entrega la agencia o el acreedor, lo que \
                                ve el deudor, los planes de cuotas y lo que se le avisa de vuelta a quien entrego.

                                - **Cada quien ve lo suyo**: el deudor, sus deudas; la empresa, la cartera que \
                                opera. Nunca "todas".
                                - **El saldo se calcula** desde las cuotas pendientes; no se guarda.
                                - **Pesos y UF no se suman**: cada monto viaja con su moneda.
                                - Los enlaces (`_links`) dicen que se puede hacer con cada deuda segun quien mira.""")
                        .contact(new Contact().name("Technical Bridge")))
                .components(new Components()
                        .addSecuritySchemes(JWT, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("El `token` de la sesion del portal (ms-auth)."))
                        .addSecuritySchemes(CLAVE_API, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("tbk_...")
                                .description("""
                                        La clave de API del emisor. La empresa la emite en el portal, en \
                                        *Claves de API*, y se comprueba con `GET /api/v1/cuenta`."""))
                        .addSecuritySchemes(CLAVE_INTERNA, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Internal-Key")
                                .description("La clave que comparten los servicios (`INTERNAL_KEY`).")));
    }

    @Bean
    public GroupedOpenApi portal() {
        return GroupedOpenApi.builder()
                .group("portal")
                .displayName("Portal")
                .pathsToMatch("/api/**")
                .pathsToExclude("/api/v1/**")
                .build();
    }

    @Bean
    public GroupedOpenApi integracion() {
        return GroupedOpenApi.builder()
                .group("integracion")
                .displayName("Integracion (contrato v1)")
                .pathsToMatch("/api/v1/**")
                .build();
    }

    @Bean
    public GroupedOpenApi interno(@Value("${app.openapi.url-interna:http://localhost:8083}") String urlInterna) {
        return GroupedOpenApi.builder()
                .group("interno")
                .displayName("Interno")
                .pathsToMatch("/internal/**")
                .addOpenApiCustomizer(api -> api.servers(List.of(new Server()
                        .url(urlInterna)
                        .description("Directo al servicio: el gateway no expone /internal"))))
                .build();
    }
}

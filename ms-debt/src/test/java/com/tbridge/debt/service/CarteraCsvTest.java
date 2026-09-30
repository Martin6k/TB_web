package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.exception.CarteraInvalida;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarteraCsvTest {

    private static final Path EJEMPLOS = Path.of("..", "docs", "integracion", "ejemplos");
    private static final CarteraCsv.Lote LOTE =
            new CarteraCsv.Lote("PAT-2026-09-18-01", "2026-09-18", "76418902-7", null, null);

    /**
     * La plantilla CSV y el ejemplo JSON del contrato describen la misma
     * cartera. Si el conversor esta bien, dan las mismas deudas: es la regla
     * R8 (un solo contrato) aplicada a sus dos formatos.
     */
    @Test
    void laPlantillaDelContratoEsLaMismaCarteraQueElEjemploJson() throws IOException {
        byte[] csv = Files.readAllBytes(EJEMPLOS.resolve("cartera-v1.plantilla.csv"));
        ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        JsonNode esperado = json.readTree(EJEMPLOS.resolve("cartera-v1.patrimonio.json").toFile());

        JsonNode leido = json.readTree(json.writeValueAsString(CarteraCsv.leer(csv, LOTE)));

        assertEquals(esperado.get("deudas"), leido.get("deudas"));
        assertEquals("PAT-2026-09-18-01", leido.at("/lote/id_externo").asText());
        assertEquals("76418902-7", leido.at("/lote/acreedor/rut").asText());
    }

    @Test
    void aceptaElBomDeExcelYLosFinesDeLineaDeWindows() {
        String csv = "﻿" + String.join(";", CarteraCsv.COLUMNAS) + "\r\n"
                + "CTR-1;registrar;;16482337-7;persona;Felipe Rojas;;+56987654321;UF;Arriendo;;Julio;2026-07;38,5;2026-07-05\r\n";
        JsonNode cartera = CarteraCsv.leer(csv.getBytes(StandardCharsets.UTF_8), LOTE);
        assertEquals(1, cartera.get("deudas").size());
        assertEquals("38.5", cartera.at("/deudas/0/cargos/0/monto").decimalValue().toPlainString());
        assertTrue(cartera.at("/deudas/0/deudor/correo").isMissingNode(), "sin correo no se inventa uno vacio");
    }

    @Test
    void respetaLasComillasDeExcel() {
        String csv = String.join(";", CarteraCsv.COLUMNAS) + "\n"
                + "CTR-1;registrar;;16482337-7;persona;\"Rojas; Felipe\";;;CLP;Arriendo;;Julio;;520000;2026-07-05\n";
        JsonNode cartera = CarteraCsv.leer(csv.getBytes(StandardCharsets.UTF_8), LOTE);
        assertEquals("Rojas; Felipe", cartera.at("/deudas/0/deudor/nombre").asText());
    }

    @Test
    void unaDeudaConFilasQueNoCoincidenDiceCualFila() {
        String csv = String.join(";", CarteraCsv.COLUMNAS) + "\n"
                + "CTR-1;registrar;;16482337-7;persona;Felipe;;;CLP;Arriendo;;Julio;;520000;2026-07-05\n"
                + "CTR-1;registrar;;18905214-6;persona;Felipe;;;CLP;Arriendo;;Agosto;;520000;2026-08-05\n";
        CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                () -> CarteraCsv.leer(csv.getBytes(StandardCharsets.UTF_8), LOTE));
        assertEquals("csv_invalido", fallo.getCodigo());
        assertTrue(fallo.getMessage().startsWith("Fila 3: la deuda CTR-1 tiene otro deudor_rut que en la fila 2"),
                fallo.getMessage());
    }

    @Test
    void sinLasColumnasDelContratoSeRechazaEntero() {
        CarteraInvalida fallo = assertThrows(CarteraInvalida.class, () -> CarteraCsv.leer(
                "email,nombre,monto\nana@correo.cl,Ana,1000\n".getBytes(StandardCharsets.UTF_8), LOTE));
        assertTrue(fallo.getMessage().contains("Faltan columnas"), fallo.getMessage());
    }

    @Test
    void unMontoConSeparadorDeMilesSeExplica() {
        String csv = String.join(";", CarteraCsv.COLUMNAS) + "\n"
                + "CTR-1;registrar;;16482337-7;persona;Felipe;;;CLP;Arriendo;;Julio;;520.000,00;2026-07-05\n";
        CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                () -> CarteraCsv.leer(csv.getBytes(StandardCharsets.UTF_8), LOTE));
        assertTrue(fallo.getMessage().contains("Fila 2") && fallo.getMessage().contains("Sin separador de miles"),
                fallo.getMessage());
    }

    @Test
    void unaFilaSinCargoEsUnClienteAlDia() {
        String csv = String.join(";", CarteraCsv.COLUMNAS) + "\n"
                + "CTR-1;registrar;;16482337-7;persona;Felipe;felipe@correo.cl;;CLP;Arriendo;;;;;\n";
        JsonNode cartera = CarteraCsv.leer(csv.getBytes(StandardCharsets.UTF_8), LOTE);
        assertTrue(cartera.at("/deudas/0/cargos").isArray());
        assertEquals(0, cartera.at("/deudas/0/cargos").size());
    }

    @Test
    void unClienteAlDiaNoPuedeTraerCargosEnOtraFila() {
        String csv = String.join(";", CarteraCsv.COLUMNAS) + "\n"
                + "CTR-1;registrar;;16482337-7;persona;Felipe;felipe@correo.cl;;CLP;Arriendo;;;;;\n"
                + "CTR-1;registrar;;16482337-7;persona;Felipe;felipe@correo.cl;;CLP;Arriendo;;Julio;;520000;2026-07-05\n";
        CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                () -> CarteraCsv.leer(csv.getBytes(StandardCharsets.UTF_8), LOTE));
        assertTrue(fallo.getMessage().startsWith("Fila 3: la deuda CTR-1 esta al dia en la fila 2"),
                fallo.getMessage());
    }
}

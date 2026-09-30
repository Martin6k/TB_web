package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.tbridge.debt.exception.CarteraInvalida;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * La variante CSV de la Cartera v1 (contrato, seccion 6.6), convertida al JSON
 * del mismo contrato.
 *
 * <p>No hay una segunda ingesta: el CSV se traduce y entra por el mismo camino
 * que la API, con las mismas validaciones, la misma aceptacion parcial y la
 * misma idempotencia. Asi un emisor sin integracion recibe exactamente las
 * mismas reglas que uno con API.
 *
 * <p>Lo que se rechaza aca es solo lo estructural —columnas que faltan, una
 * deuda cuyas filas no coinciden—, con el numero de fila, porque quien lo
 * arregla esta mirando una planilla. Lo de negocio (RUT, montos, mora) lo
 * decide la ingesta, deuda por deuda.
 */
public final class CarteraCsv {

    static final List<String> COLUMNAS = List.of(
            "deuda_id", "accion", "motivo_retiro", "deudor_rut", "deudor_tipo", "deudor_nombre",
            "deudor_correo", "deudor_telefono", "moneda", "concepto", "referencias",
            "cargo_concepto", "cargo_periodo", "cargo_monto", "cargo_vencimiento");

    /** Las columnas de un cargo. Una fila con las cuatro vacias es un cliente al dia. */
    private static final List<String> CARGO = List.of(
            "cargo_concepto", "cargo_periodo", "cargo_monto", "cargo_vencimiento");

    /** Las columnas de la deuda: tienen que repetirse igual en cada fila de sus cargos. */
    private static final List<String> DE_LA_DEUDA = List.of(
            "accion", "deudor_rut", "deudor_tipo", "deudor_nombre", "deudor_correo",
            "deudor_telefono", "moneda", "concepto", "referencias");

    static final int MAX_FILAS = 50_000;
    private static final JsonNodeFactory NODOS = JsonNodeFactory.instance;

    /** Lo que en el CSV va aparte del archivo: los datos del lote. */
    public record Lote(String idExterno, String fechaCorte, String acreedorRut,
                       String agenciaRut, String campanaIdExterno) {
    }

    private CarteraCsv() {
    }

    public static ObjectNode leer(byte[] archivo, Lote lote) {
        List<List<String>> filas = filas(new String(archivo, StandardCharsets.UTF_8));
        if (filas.isEmpty()) {
            throw invalido("El archivo esta vacio");
        }
        Map<String, Integer> indice = encabezado(filas.get(0));
        if (filas.size() - 1 > MAX_FILAS) {
            throw invalido("El archivo tiene mas de " + MAX_FILAS + " filas: dividelo en varios lotes");
        }

        Map<String, ObjectNode> deudas = new LinkedHashMap<>();
        Map<String, Integer> primeraFila = new LinkedHashMap<>();
        Map<String, List<String>> columnasDe = new LinkedHashMap<>();
        Set<String> alDia = new HashSet<>();

        for (int n = 1; n < filas.size(); n++) {
            List<String> fila = filas.get(n);
            int numero = n + 1;                        // como la numera Excel, con el encabezado en la 1
            String id = celda(fila, indice, "deuda_id");
            if (id.isEmpty()) {
                throw invalido("Fila " + numero + ": falta deuda_id");
            }
            String accion = celda(fila, indice, "accion");

            if ("retirar".equals(accion)) {
                if (deudas.containsKey(id)) {
                    throw invalido("Fila " + numero + ": la deuda " + id + " ya aparece en la fila "
                            + primeraFila.get(id) + "; un retiro va en una sola fila");
                }
                ObjectNode retiro = NODOS.objectNode();
                retiro.put("id_externo", id);
                retiro.put("accion", "retirar");
                retiro.put("motivo_retiro", celda(fila, indice, "motivo_retiro"));
                deudas.put(id, retiro);
                primeraFila.put(id, numero);
                continue;
            }

            List<String> suyas = new ArrayList<>();
            for (String columna : DE_LA_DEUDA) {
                suyas.add(celda(fila, indice, columna));
            }
            boolean conCargo = CARGO.stream().anyMatch(columna -> !celda(fila, indice, columna).isEmpty());
            ObjectNode deuda = deudas.get(id);
            if (deuda == null) {
                deuda = nuevaDeuda(id, fila, indice);
                deudas.put(id, deuda);
                primeraFila.put(id, numero);
                columnasDe.put(id, suyas);
                if (!conCargo) {
                    //  Un cliente sin cargos esta al dia: va en una sola fila, y
                    //  la deuda sale con cargos: [].
                    alDia.add(id);
                    continue;
                }
            } else if (alDia.contains(id)) {
                throw invalido("Fila " + numero + ": la deuda " + id + " esta al dia en la fila "
                        + primeraFila.get(id) + " (sin cargos) y no puede traer cargos en otra");
            } else if (!conCargo) {
                throw invalido("Fila " + numero + ": la deuda " + id + " no trae el cargo. Un cliente al dia "
                        + "va en una sola fila, sin columnas de cargo");
            } else if (!deuda.has("cargos")) {
                throw invalido("Fila " + numero + ": la deuda " + id + " se retira en la fila "
                        + primeraFila.get(id) + " y no puede traer cargos");
            } else if (!suyas.equals(columnasDe.get(id))) {
                String cual = DE_LA_DEUDA.get(primeraDiferencia(suyas, columnasDe.get(id)));
                throw invalido("Fila " + numero + ": la deuda " + id + " tiene otro " + cual
                        + " que en la fila " + primeraFila.get(id)
                        + ". Las columnas de la deuda se repiten iguales en cada cargo");
            }
            ((ArrayNode) deuda.get("cargos")).add(cargo(fila, indice, numero));
        }

        ObjectNode cartera = NODOS.objectNode();
        cartera.put("version", "1.0");
        ObjectNode cabecera = cartera.putObject("lote");
        cabecera.put("id_externo", lote.idExterno());
        cabecera.put("fecha_corte", lote.fechaCorte());
        cabecera.putObject("acreedor").put("rut", lote.acreedorRut());
        if (lote.agenciaRut() != null && !lote.agenciaRut().isBlank()) {
            ObjectNode mandato = cabecera.putObject("mandato");
            mandato.put("agencia_rut", lote.agenciaRut());
            if (lote.campanaIdExterno() != null && !lote.campanaIdExterno().isBlank()) {
                mandato.put("campana_id_externo", lote.campanaIdExterno());
            }
        }
        ArrayNode lista = cartera.putArray("deudas");
        deudas.values().forEach(lista::add);
        return cartera;
    }

    private static ObjectNode nuevaDeuda(String id, List<String> fila, Map<String, Integer> indice) {
        ObjectNode deuda = NODOS.objectNode();
        deuda.put("id_externo", id);
        ObjectNode deudor = deuda.putObject("deudor");
        deudor.put("rut", celda(fila, indice, "deudor_rut"));
        deudor.put("tipo", celda(fila, indice, "deudor_tipo"));
        deudor.put("nombre", celda(fila, indice, "deudor_nombre"));
        ponerSiHay(deudor, "correo", celda(fila, indice, "deudor_correo"));
        ponerSiHay(deudor, "telefono", celda(fila, indice, "deudor_telefono"));
        deuda.put("moneda", celda(fila, indice, "moneda"));
        deuda.put("concepto", celda(fila, indice, "concepto"));
        String referencias = celda(fila, indice, "referencias");
        if (!referencias.isEmpty()) {
            ObjectNode refs = deuda.putObject("referencias");
            for (String par : referencias.split("\\|")) {
                int igual = par.indexOf('=');
                if (igual > 0) {
                    refs.put(par.substring(0, igual).trim(), par.substring(igual + 1).trim());
                }
            }
        }
        deuda.putArray("cargos");
        return deuda;
    }

    private static ObjectNode cargo(List<String> fila, Map<String, Integer> indice, int numero) {
        ObjectNode cargo = NODOS.objectNode();
        cargo.put("concepto", celda(fila, indice, "cargo_concepto"));
        ponerSiHay(cargo, "periodo", celda(fila, indice, "cargo_periodo"));
        String monto = celda(fila, indice, "cargo_monto").replace(',', '.');
        try {
            BigDecimal valor = new BigDecimal(monto);
            if (valor.scale() <= 0 || valor.stripTrailingZeros().scale() <= 0) {
                cargo.put("monto", valor.longValueExact());
            } else {
                cargo.put("monto", valor);
            }
        } catch (NumberFormatException | ArithmeticException e) {
            throw invalido("Fila " + numero + ": el monto '" + celda(fila, indice, "cargo_monto")
                    + "' no es un numero. Sin separador de miles: 520000, o 38,5 en UF");
        }
        cargo.put("fecha_vencimiento", celda(fila, indice, "cargo_vencimiento"));
        return cargo;
    }

    // ------------------------------------------------------------------
    //  Leer el texto
    // ------------------------------------------------------------------

    private static Map<String, Integer> encabezado(List<String> primera) {
        Map<String, Integer> indice = new LinkedHashMap<>();
        for (int i = 0; i < primera.size(); i++) {
            indice.put(primera.get(i).trim().toLowerCase(), i);
        }
        List<String> faltan = COLUMNAS.stream().filter(c -> !indice.containsKey(c)).toList();
        if (!faltan.isEmpty()) {
            throw invalido("Faltan columnas: " + String.join(", ", faltan)
                    + ". Usa la plantilla del contrato (separador ;)");
        }
        return indice;
    }

    /**
     * Separa en filas y celdas con ";" y respeta las comillas: Excel las pone
     * cuando una celda trae un ";" o un salto de linea.
     */
    static List<List<String>> filas(String texto) {
        if (texto.startsWith("﻿")) {
            texto = texto.substring(1);
        }
        List<List<String>> filas = new ArrayList<>();
        List<String> fila = new ArrayList<>();
        StringBuilder celda = new StringBuilder();
        boolean entreComillas = false;
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (entreComillas) {
                if (c == '"' && i + 1 < texto.length() && texto.charAt(i + 1) == '"') {
                    celda.append('"');
                    i++;
                } else if (c == '"') {
                    entreComillas = false;
                } else {
                    celda.append(c);
                }
            } else if (c == '"') {
                entreComillas = true;
            } else if (c == ';') {
                fila.add(celda.toString());
                celda.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < texto.length() && texto.charAt(i + 1) == '\n') {
                    i++;
                }
                fila.add(celda.toString());
                celda.setLength(0);
                if (!(fila.size() == 1 && fila.get(0).isBlank())) {
                    filas.add(fila);
                }
                fila = new ArrayList<>();
            } else {
                celda.append(c);
            }
        }
        if (celda.length() > 0 || !fila.isEmpty()) {
            fila.add(celda.toString());
            if (!(fila.size() == 1 && fila.get(0).isBlank())) {
                filas.add(fila);
            }
        }
        return filas;
    }

    private static String celda(List<String> fila, Map<String, Integer> indice, String columna) {
        int i = indice.get(columna);
        return i < fila.size() ? fila.get(i).trim() : "";
    }

    private static void ponerSiHay(ObjectNode nodo, String campo, String valor) {
        if (!valor.isEmpty()) {
            nodo.put(campo, valor);
        }
    }

    private static int primeraDiferencia(List<String> a, List<String> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) {
                return i;
            }
        }
        return 0;
    }

    private static CarteraInvalida invalido(String mensaje) {
        return new CarteraInvalida("csv_invalido", mensaje, 400);
    }
}

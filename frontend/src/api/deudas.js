import { client, embebidos, pedir } from "./client";
import { descargar } from "../utils/exportar";

/** Las deudas de quien tiene la sesion: al deudor, las suyas; a la empresa, su cartera. */
export const listarDeudas = () => pedir("/debts").then((data) => embebidos(data, "debts"));

/** Una deuda con sus cargos, cuotas e historia. */
export const obtenerDeuda = (id) => pedir(`/debts/${id}`);

/** El plan que resultaria con ese plazo. No compromete nada. */
export const simularPlan = (id, meses) =>
  pedir(`/debts/${id}/simulate`, { params: { months: meses } }).then((data) => data.plan);

/** Aceptar el plan: las cuotas pendientes se reemplazan por las del plan. */
export const repactar = (id, meses) => pedir(`/debts/${id}/repact`, { method: "POST", body: { months: meses } });

/** El deudor no reconoce la deuda: queda en revision y no se cobra mientras tanto. */
export const disputar = (id, motivo, detalle) =>
  pedir(`/debts/${id}/disputa`, { method: "POST", body: { motivo, detalle } });

/** La empresa resuelve la disputa: reanudar el cobro o retirar la deuda. */
export const resolverDisputa = (id, resultado, nota) =>
  pedir(`/debts/${id}/disputa/resolucion`, { method: "POST", body: { resultado, nota } });

/** La empresa le hace llegar al deudor su codigo de acceso, al correo. */
export const enviarCodigo = (id) => pedir(`/debts/${id}/codigo`, { method: "POST" });

/** Las cuotas por pagar del deudor, de la que vence primero a la ultima. */
export const listarVencimientos = () => pedir("/debts/vencimientos").then((data) => embebidos(data, "cuotas"));

/** Los convenios de la cartera con cuotas vencidas, del mas atrasado al menos. */
export const listarEnRiesgo = () => pedir("/debts/en-riesgo").then((data) => embebidos(data, "convenios"));

/** Lo que DataBridge sabe del deudor, y de parte de quien. */
export const misDatos = () => pedir("/debts/mis-datos");

/** Encender o apagar el correo que recuerda una cuota por vencer. */
export const cambiarRecordatorios = (recordatorios) =>
  pedir("/debts/mis-datos", { method: "PATCH", body: { recordatorios } });

/** Por cuenta de quien puede cargar cartera la empresa de la sesion. */
export const opcionesDeCarga = () => pedir("/debts/cartera/opciones");

/** Cargar la cartera en el CSV del contrato. */
export const cargarCartera = (formulario) => pedir("/debts/cartera", { method: "POST", body: formulario });

/** El certificado de deuda pagada, descargado como archivo. */
export async function descargarCertificado(id) {
  const res = await client.get(`/debts/${id}/certificate`, { responseType: "blob" });
  descargar(res.data, "certificado-deuda-pagada.pdf");
}

/**
 * Como se escriben montos, RUT, fechas y estados en Chile.
 */

const PESOS = new Intl.NumberFormat("es-CL", { style: "currency", currency: "CLP", maximumFractionDigits: 0 });
const UF = new Intl.NumberFormat("es-CL", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/**
 * Un monto en su moneda. Las deudas de arriendo pueden estar en UF, y mostrar
 * UF 38,50 como "$39" era decirle al deudor que debia otra cosa.
 */
export function dinero(valor, moneda = "CLP") {
  const n = Number(valor || 0);
  return moneda === "UF" ? `UF ${UF.format(n)}` : PESOS.format(n);
}

/** 16482337-7 -> 16.482.337-7, como se escribe en Chile. */
export function rutLegible(rut) {
  const limpio = String(rut || "").replace(/[^0-9kK]/g, "").toUpperCase();
  if (limpio.length < 2) return limpio;
  const cuerpo = limpio.slice(0, -1).replace(/\B(?=(\d{3})+(?!\d))/g, ".");
  return `${cuerpo}-${limpio.slice(-1)}`;
}

/** Una fecha (o fecha y hora) como la lee una persona: 24 sept 2026. */
export function fecha(iso) {
  if (!iso) return "";
  const d = new Date(String(iso).length === 10 ? `${iso}T12:00:00` : iso);
  return d.toLocaleDateString("es-CL", { day: "2-digit", month: "short", year: "numeric" });
}

/** Una fecha y su hora: 21 sept 2026, 18:45. */
export function fechaHora(iso) {
  if (!iso) return "";
  return new Date(iso).toLocaleString("es-CL", {
    day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit",
  });
}

/** Una fecha dicha como en una conversacion: 20 de octubre. */
export function fechaLarga(iso) {
  return new Date(`${iso}T12:00:00`).toLocaleDateString("es-CL", { day: "numeric", month: "long" });
}

/** El mes de una fecha, para agrupar: Octubre de 2026. */
export function mesYAnio(iso) {
  const texto = new Date(`${iso}T12:00:00`).toLocaleDateString("es-CL", { month: "long", year: "numeric" });
  return texto.charAt(0).toUpperCase() + texto.slice(1);
}

/** Cuanto falta para que venza, o hace cuanto vencio. */
export function cuandoVence(dias) {
  if (dias === 0) return "Vence hoy";
  if (dias === 1) return "Vence mañana";
  if (dias > 1) return `Vence en ${dias} días`;
  if (dias === -1) return "Venció ayer";
  return `Venció hace ${-dias} días`;
}

/** Que cubrio un pago: "Cuotas 2 y 3 de 6", "Cuota 1 de 3", "Pago total". */
export function queSePago(pago) {
  const cuotas = pago.cuotas || [];
  const fuera = pago.fueraDelConvenio > 0;
  if (!cuotas.length) return fuera ? "Fuera del convenio" : "Abono a la deuda";
  if (!fuera && pago.deCuotas === 1) return "Pago total";
  const de = pago.deCuotas ? ` de ${pago.deCuotas}` : "";
  const plan = cuotas.length === 1 ? `cuota ${cuotas[0]}${de}`
    : `cuotas ${cuotas.slice(0, -1).join(", ")} y ${cuotas[cuotas.length - 1]}${de}`;
  return fuera ? `Fuera del convenio y ${plan}` : plan.charAt(0).toUpperCase() + plan.slice(1);
}

/**
 * Lo que suma una deuda: lo que falta mas lo que ya se pago por DataBridge. No es
 * el monto que informo el acreedor: cuando actualiza la deuda en su cartera del
 * mes, ese monto ya viene descontado de lo que se pago aca.
 */
export const totalDeLaDeuda = (d) => Number(d.saldo) + Number(d.pagado);

/** Suma por moneda: pesos y UF no se pueden sumar entre si. */
export function porMoneda(filas, campo) {
  const totales = {};
  for (const f of filas) totales[f.moneda] = (totales[f.moneda] || 0) + Number(f[campo] || 0);
  return Object.entries(totales);
}

/** Hoy en Chile, como 2026-09-24. */
export const hoyEnChile = () => new Date().toLocaleDateString("sv-SE", { timeZone: "America/Santiago" });

/** Como se le dice a una persona el estado de su deuda. */
export const ESTADO_DEUDA = {
  open: "Pendiente",
  repacted: "En convenio",
  paid: "Pago conciliado",
  withdrawn: "Retirada",
  disputed: "En revisión",
};

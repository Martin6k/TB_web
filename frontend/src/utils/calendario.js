import { dinero } from "./formato";
import { descargar } from "./exportar";

/**
 * Las cuotas como archivo de calendario (.ics), para agregarlas al calendario
 * del telefono o de Outlook. Cada cuota es un evento de dia completo el dia
 * que vence, con un aviso la manana anterior.
 *
 * El archivo no trae enlaces: igual que los correos, no debe llevar a nadie a
 * ninguna parte.
 */
export function descargarCalendario(cuotas) {
  const sello = new Date().toISOString().replace(/[-:]/g, "").replace(/\.\d{3}/, "");
  const escapar = (texto) => String(texto).replace(/([,;\\])/g, "\\$1");
  const eventos = cuotas.map((c) => {
    const dia = c.vencimiento.replace(/-/g, "");
    const siguiente = diaSiguiente(c.vencimiento);
    const que = c.enConvenio ? `Cuota ${c.lugar} de ${c.deCuotas}`
      : c.fueraDelConvenio ? "Pago fuera del convenio" : "Pago de la deuda";
    return [
      "BEGIN:VEVENT",
      `UID:cuota-${c.id}@databridge`,
      `DTSTAMP:${sello}`,
      `DTSTART;VALUE=DATE:${dia}`,
      `DTEND;VALUE=DATE:${siguiente}`,
      `SUMMARY:${escapar(`${que}: ${dinero(c.monto, c.moneda)}`)}`,
      `DESCRIPTION:${escapar(`${c.concepto} con ${c.acreedor}, contrato ${c.externalId}. Paga en el portal escribiendo la dirección tú mismo.`)}`,
      "BEGIN:VALARM",
      "ACTION:DISPLAY",
      "TRIGGER:-PT15H",
      `DESCRIPTION:${escapar(`Mañana vence: ${que}`)}`,
      "END:VALARM",
      "END:VEVENT",
    ].join("\r\n");
  });
  const texto = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Technical Bridge//DataBridge//ES", "CALSCALE:GREGORIAN",
    ...eventos, "END:VCALENDAR"].join("\r\n") + "\r\n";
  descargar(new Blob([texto], { type: "text/calendar;charset=utf-8" }), "mis-cuotas.ics");
}

function diaSiguiente(iso) {
  const d = new Date(`${iso}T12:00:00`);
  d.setDate(d.getDate() + 1);
  return d.toISOString().slice(0, 10).replace(/-/g, "");
}

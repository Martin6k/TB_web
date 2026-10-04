import { IconoCheck } from "./Iconos";

const TITULOS = ["Pendiente", "En convenio", "Pago conciliado"];

/**
 * En que va una deuda, de principio a fin: pendiente, en convenio, pago
 * conciliado.
 *
 * "Conciliado" y no solo "pagado": la pasarela confirmo el pago, se imputo a
 * la deuda y el acreedor ya fue avisado. En convenio la barra avanza con cada
 * cuota pagada, no solo al cambiar de etapa.
 */
export function etapaDe(d) {
  const pagadas = d.cuotasPagadas ?? 0;
  const totales = d.cuotasTotales ?? 0;
  const enConvenio = 0.5 + 0.5 * (totales ? pagadas / totales : 0);

  switch (d.estado) {
    case "open":
      return { avance: 0, pasos: ["actual", "", ""], subs: ["Por regularizar", "Si lo eliges", ""],
               texto: "Pendiente" };
    case "repacted":
      return { avance: enConvenio, pasos: ["hecho", "actual", ""], subs: ["", `${pagadas} de ${totales} cuotas`, ""],
               texto: `En convenio, ${pagadas} de ${totales}` };
    case "paid":
      return { avance: 1, pasos: ["hecho", d.conConvenio ? "hecho" : "omitido", "hecho"],
               subs: ["", d.conConvenio ? `${totales} cuotas pagadas` : "Pago al contado", "Acreedor avisado"],
               texto: "Pago conciliado" };
    case "withdrawn":
      return { avance: d.conConvenio ? enConvenio : 0, pasos: ["hecho", d.conConvenio ? "hecho" : "", ""],
               subs: ["", "", "No se cobrará"], texto: "Retirada por el acreedor", detenida: true };
    default:
      //  En disputa: la empresa revisa lo que dijo el deudor, y mientras tanto no se cobra.
      return { avance: d.conConvenio ? enConvenio : 0, pasos: ["hecho", d.conConvenio ? "actual" : "", ""],
               subs: ["", "", ""], texto: "En revisión", detenida: true };
  }
}

export default function BarraEstado({ deuda, compacta = false }) {
  const etapa = etapaDe(deuda);
  const aria = {
    role: "progressbar",
    "aria-valuemin": 0,
    "aria-valuemax": 100,
    "aria-valuenow": Math.round(etapa.avance * 100),
    "aria-valuetext": etapa.texto,
  };

  if (compacta) {
    return (
      <div className={`estado-mini${etapa.detenida ? " detenida" : ""}`} style={{ "--avance": etapa.avance }} {...aria}>
        <div className="mini-riel">
          <span className="mini-relleno" />
          {[0, 0.5, 1].map((punto) => (
            <span key={punto} className={`mini-punto${etapa.avance >= punto ? " lleno" : ""}`}
                  style={{ left: `${punto * 100}%` }} />
          ))}
        </div>
        <span className="mini-texto">{etapa.texto}</span>
      </div>
    );
  }

  return (
    <div className={`pasos${etapa.detenida ? " detenida" : ""}`} style={{ "--avance": etapa.avance }} {...aria}>
      <div className="pasos-riel"><div className="pasos-relleno" /></div>
      <ol>
        {TITULOS.map((titulo, i) => (
          <li key={titulo} className={`paso ${etapa.pasos[i]}`}>
            <span className="paso-punto">
              {etapa.pasos[i] === "hecho" ? <IconoCheck size={16} /> : i + 1}
            </span>
            <span className="paso-titulo">{titulo}</span>
            {etapa.subs[i] ? <span className="paso-sub">{etapa.subs[i]}</span> : null}
          </li>
        ))}
      </ol>
      {etapa.detenida ? <p className="pasos-nota">{etapa.texto}</p> : null}
    </div>
  );
}

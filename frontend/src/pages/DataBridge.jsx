import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { resumenDeCartera } from "../api/analitica";
import { enviarCodigo as pedirCodigo, listarDeudas, listarEnRiesgo, resolverDisputa } from "../api/deudas";
import { dinero, ESTADO_DEUDA, fecha, MOTIVOS_DISPUTA, rutLegible, totalDeLaDeuda } from "../utils/formato";
import { descargarCsv, montoParaExcel } from "../utils/exportar";
import BarraEstado, { etapaDe } from "../components/BarraEstado";
import { EstadoCartera, RecuperadoPorDia } from "../components/Graficos";
import { IconoAlerta, IconoDescargar } from "../components/Iconos";

/**
 * El portal de la empresa que gestiona la cartera: la agencia (APOFYX) o el
 * acreedor que trabaja directo.
 *
 * La cartera llega por la API del contrato v1 desde el sistema de la empresa
 * o, para quien no tiene integracion, cargando el CSV del mismo contrato (en
 * su propia pagina). Aca se ve en que va cada deuda y se le hace llegar al
 * deudor su codigo de acceso.
 */
export default function DataBridge() {
  const [resumen, setResumen] = useState(null);
  const [deudas, setDeudas] = useState([]);
  const [filtro, setFiltro] = useState("todas");
  const [avisos, setAvisos] = useState({});
  const [enRiesgo, setEnRiesgo] = useState(0);
  const [error, setError] = useState("");

  async function refrescar() {
    const [r, d] = await Promise.all([resumenDeCartera(), listarDeudas()]);
    setResumen(r);
    setDeudas(d);
  }

  useEffect(() => {
    listarEnRiesgo().then((c) => setEnRiesgo(c.length)).catch(() => setEnRiesgo(0));
  }, []);

  useEffect(() => {
    refrescar().catch((err) => setError(err.message));
  }, []);

  async function enviarCodigo(deuda) {
    setAvisos((a) => ({ ...a, [deuda.id]: { enviando: true } }));
    try {
      const r = await pedirCodigo(deuda.id);
      setAvisos((a) => ({ ...a, [deuda.id]: { ok: `Enviado a ${r.destino}` } }));
    } catch (err) {
      setAvisos((a) => ({ ...a, [deuda.id]: { error: err.message } }));
    }
  }

  /** Revisada la disputa: la deuda corresponde (reanudar) o no (retirar). */
  async function resolver(deuda, resultado) {
    const pregunta = resultado === "reanudar"
      ? `¿La deuda de ${deuda.deudor} corresponde? Vuelve a cobranza. Puedes dejar una nota:`
      : `¿La deuda de ${deuda.deudor} no corresponde? Sale de la cobranza y se le avisa al acreedor. Puedes dejar una nota:`;
    const nota = window.prompt(pregunta, "");
    if (nota === null) return;
    setAvisos((a) => ({ ...a, [deuda.id]: { enviando: true } }));
    try {
      await resolverDisputa(deuda.id, resultado, nota.trim() || null);
      setAvisos((a) => ({ ...a, [deuda.id]: { ok: resultado === "reanudar" ? "Cobro reanudado" : "Deuda retirada" } }));
      await refrescar();
      //  Ya no esta en revision: con ese filtro desapareceria, y no se veria en que quedo.
      setFiltro("todas");
    } catch (err) {
      setAvisos((a) => ({ ...a, [deuda.id]: { error: err.message } }));
    }
  }

  const visibles = deudas.filter((d) => filtro === "todas" || d.estado === filtro);

  function exportar() {
    descargarCsv("cartera.csv",
      ["Deudor", "RUT", "Acreedor", "Referencia", "Concepto", "Moneda", "Total", "Pagado", "Saldo", "Estado",
        "Cuotas pagadas", "Cuotas del convenio", "Actualizada"],
      visibles.map((d) => [d.deudor, rutLegible(d.deudorRut), d.acreedor, d.externalId, d.concepto, d.moneda,
        montoParaExcel(totalDeLaDeuda(d), d.moneda), montoParaExcel(d.pagado, d.moneda),
        montoParaExcel(d.saldo, d.moneda), etapaDe(d).texto,
        d.cuotasPagadas, d.cuotasTotales, fecha(d.actualizada)]));
  }

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <span className="eyebrow">{resumen?.organizacion || "DataBridge"}</span>
          <h1>Cartera morosa</h1>
          <p>Deudores en mora, y en qué va cada uno: pendiente, en convenio o pago conciliado.</p>
        </div>
      </header>
      {error ? <div className="error">{error}</div> : null}

      {enRiesgo > 0 ? (
        <Link to="/databridge/en-riesgo" className="aviso bloque aparece" style={{ "--i": 1 }}>
          <IconoAlerta />
          <div>
            <b>{enRiesgo === 1 ? "Un convenio tiene cuotas vencidas" : `${enRiesgo} convenios tienen cuotas vencidas`}</b>
            <span className="sub">Revísalos en Convenios en riesgo, antes de que se caigan.</span>
          </div>
        </Link>
      ) : null}

      <section className="grid-3 stats bloque">
        <div className="card stat aparece" style={{ "--i": 1 }}>
          <span>Deudas en gestión</span>
          <b>{resumen?.activas ?? "–"}</b>
          <small>{resumen ? `${resumen.enConvenio} en convenio de pago` : ""}</small>
        </div>
        <div className="card stat aparece" style={{ "--i": 2 }}>
          <span>Pagos conciliados</span>
          <b>{resumen?.pagadas ?? "–"}</b>
          <small>{resumen ? `${resumen.retiradas} retiradas por el acreedor` : ""}</small>
        </div>
        <div className="card stat aparece" style={{ "--i": 3 }}>
          <span>Recuperado</span>
          <b className="totales">
            {(resumen?.porMoneda || []).map((m) => (
              <span key={m.moneda}>{dinero(m.recuperado, m.moneda)}</span>
            ))}
          </b>
          <small>{(resumen?.porMoneda || []).map((m) => `${m.tasaRecuperacion}% en ${m.moneda}`).join(", ")}</small>
        </div>
      </section>

      <div className="grid-2 bloque aparece" style={{ "--i": 4, alignItems: "stretch" }}>
        <EstadoCartera resumen={resumen} />
        <RecuperadoPorDia resumen={resumen} />
      </div>

      <div className="card aparece" style={{ "--i": 5 }}>
        <div className="card-cab">
          <h3>Deudas</h3>
          <button type="button" className="btn btn-ghost btn-sm" disabled={!visibles.length} onClick={exportar}>
            <IconoDescargar size={16} />
            Exportar a Excel
          </button>
        </div>
        <div className="card-cab">
          <div className="filters">
            {["todas", "open", "repacted", "disputed", "paid", "withdrawn"].map((f) => (
              <button key={f} type="button" className={`chip ${filtro === f ? "on" : ""}`} onClick={() => setFiltro(f)}>
                {f === "todas" ? "Todas" : ESTADO_DEUDA[f]}
              </button>
            ))}
          </div>
        </div>
        {visibles.length === 0 ? (
          <div className="empty">
            {deudas.length === 0
              ? "Todavía no llega cartera. Llega por la API del contrato o cargando el CSV en Cargar cartera."
              : "No hay deudas en ese estado."}
          </div>
        ) : (
          <div className="tabla-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Deudor</th>
                  <th>Acreedor</th>
                  <th className="num">Saldo</th>
                  <th>Estado</th>
                  <th>Acceso</th>
                </tr>
              </thead>
              <tbody>
                {visibles.map((d) => {
                  const aviso = avisos[d.id];
                  const cobrable = d.estado === "open" || d.estado === "repacted";
                  return (
                    <tr key={d.id}>
                      <td>{d.deudor}<span className="sub">{rutLegible(d.deudorRut)}</span></td>
                      <td>{d.acreedor}<span className="sub">{d.concepto}, ref. {d.externalId}</span></td>
                      <td className="num">
                        {dinero(d.saldo, d.moneda)}
                        <span className="sub">de {dinero(totalDeLaDeuda(d), d.moneda)}</span>
                      </td>
                      <td>
                        <BarraEstado deuda={d} compacta />
                        <span className="sub">{fecha(d.actualizada)}</span>
                        {d.disputa ? (
                          <span className="sub">
                            <b>{MOTIVOS_DISPUTA[d.disputa.motivo] || d.disputa.motivo}</b>
                            {d.disputa.detalle ? `: «${d.disputa.detalle}»` : ""}
                          </span>
                        ) : null}
                      </td>
                      <td>
                        {d.estado === "disputed" ? (
                          <div className="filters">
                            <button type="button" className="btn btn-soft btn-sm" disabled={aviso?.enviando}
                                    onClick={() => resolver(d, "reanudar")}>Reanudar cobro</button>
                            <button type="button" className="btn btn-ghost btn-sm" disabled={aviso?.enviando}
                                    onClick={() => resolver(d, "retirar")}>Retirar</button>
                          </div>
                        ) : null}
                        {cobrable ? (
                          <button type="button" className="btn btn-soft btn-sm" disabled={aviso?.enviando}
                                  onClick={() => enviarCodigo(d)}>
                            {aviso?.enviando ? <><span className="girando" /> Enviando…</>
                              : d.codigoEnviado ? "Reenviar código" : "Enviar código"}
                          </button>
                        ) : null}
                        {cobrable && !aviso ? (
                          <span className="sub">
                            {d.codigoEnviado ? `Invitado el ${fecha(d.codigoEnviado)}` : "Todavía sin invitar"}
                          </span>
                        ) : null}
                        {aviso?.ok ? <span className="sub">{aviso.ok}</span> : null}
                        {aviso?.error ? <span className="sub" style={{ color: "var(--danger)" }}>{aviso.error}</span> : null}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        <p className="hint">
          Al entrar su deuda, al deudor le llega solo su código de acceso. Con el botón se le reenvía. El código
          va a su correo y no se muestra aquí: quien lo viera podría entrar en su lugar.
        </p>
      </div>
    </div>
  );
}

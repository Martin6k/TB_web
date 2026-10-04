import { useEffect, useState } from "react";
import { enviarCodigo, listarEnRiesgo } from "../api/deudas";
import { dinero, fecha, rutLegible } from "../utils/formato";
import { descargarCsv, montoParaExcel } from "../utils/exportar";
import Cargando from "../components/Cargando";
import { CheckAnimado, IconoDescargar } from "../components/Iconos";

/**
 * Los convenios con cuotas vencidas, del mas atrasado al menos. Es la lista
 * de a quien llamar hoy: un convenio que se deja atrasar se cae.
 */
export default function EnRiesgo() {
  const [convenios, setConvenios] = useState(null);
  const [avisos, setAvisos] = useState({});
  const [error, setError] = useState("");

  useEffect(() => {
    listarEnRiesgo()
      .then(setConvenios)
      .catch((err) => {
        setError(err.message);
        setConvenios([]);
      });
  }, []);

  async function codigo(c) {
    setAvisos((a) => ({ ...a, [c.deudaId]: { enviando: true } }));
    try {
      const r = await enviarCodigo(c.deudaId);
      setAvisos((a) => ({ ...a, [c.deudaId]: { ok: `Enviado a ${r.destino}` } }));
    } catch (err) {
      setAvisos((a) => ({ ...a, [c.deudaId]: { error: err.message } }));
    }
  }

  function exportar() {
    descargarCsv("convenios-en-riesgo.csv",
      ["Deudor", "RUT", "Referencia", "Acreedor", "Cuotas vencidas", "Monto vencido", "Moneda", "Vencida desde",
        "Dias de atraso", "Cuotas pagadas", "Cuotas del convenio", "Saldo"],
      convenios.map((c) => [c.deudor, rutLegible(c.deudorRut), c.externalId, c.acreedor, c.cuotasVencidas,
        montoParaExcel(c.montoVencido, c.moneda), c.moneda, c.vencidaDesde, c.diasAtraso, c.cuotasPagadas,
        c.cuotasTotales, montoParaExcel(c.saldo, c.moneda)]));
  }

  if (convenios === null) return <Cargando tarjetas={1} />;

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Convenios en riesgo</h1>
          <p>Deudores en convenio con cuotas vencidas. Contáctalos antes de que el convenio se caiga.</p>
        </div>
        {convenios.length ? (
          <div className="topbar-acciones">
            <button type="button" className="btn btn-ghost btn-sm" onClick={exportar}>
              <IconoDescargar size={16} />
              Exportar a Excel
            </button>
          </div>
        ) : null}
      </header>
      {error ? <div className="error">{error}</div> : null}

      {convenios.length === 0 ? (
        <div className="card al-dia aparece" style={{ "--i": 1 }}>
          <CheckAnimado size={64} />
          <h3>Ningún convenio atrasado</h3>
          <p>Todos los deudores en convenio van al día con sus cuotas.</p>
        </div>
      ) : (
        <div className="card aparece" style={{ "--i": 1 }}>
          <div className="tabla-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Deudor</th>
                  <th>Atraso</th>
                  <th className="num">Vencido</th>
                  <th>Avance</th>
                  <th>Acceso</th>
                </tr>
              </thead>
              <tbody>
                {convenios.map((c) => {
                  const aviso = avisos[c.deudaId];
                  return (
                    <tr key={c.deudaId}>
                      <td>
                        {c.deudor}
                        <span className="sub">{rutLegible(c.deudorRut)}, ref. {c.externalId}</span>
                      </td>
                      <td>
                        <span className={`badge ${c.diasAtraso > 15 ? "badge-peligro" : "badge-warn"}`}>
                          {c.diasAtraso === 1 ? "1 día" : `${c.diasAtraso} días`}
                        </span>
                        <span className="sub">Desde el {fecha(c.vencidaDesde)}</span>
                      </td>
                      <td className="num">
                        {dinero(c.montoVencido, c.moneda)}
                        <span className="sub">{c.cuotasVencidas === 1 ? "1 cuota" : `${c.cuotasVencidas} cuotas`}</span>
                      </td>
                      <td>
                        {c.cuotasPagadas} de {c.cuotasTotales} pagadas
                        <span className="sub">{c.ultimoPago ? `Último pago: ${fecha(c.ultimoPago)}` : "Sin pagos todavía"}</span>
                      </td>
                      <td>
                        <button type="button" className="btn btn-soft btn-sm" disabled={aviso?.enviando}
                                onClick={() => codigo(c)}>
                          {aviso?.enviando ? <><span className="girando" /> Enviando…</> : "Enviar código"}
                        </button>
                        {aviso?.ok ? <span className="sub">{aviso.ok}</span> : null}
                        {aviso?.error ? <span className="sub" style={{ color: "var(--danger)" }}>{aviso.error}</span> : null}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <p className="hint">El código le llega al correo. Al entrar, ve primero la cuota atrasada.</p>
        </div>
      )}
    </div>
  );
}

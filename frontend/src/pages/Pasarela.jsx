import { useEffect, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { confirmarPagoPublico, pagoPublico } from "../api/pagos";
import { dinero } from "../utils/formato";
import TemaToggle from "../components/TemaToggle";
import LogoPasarela, { nombreDePasarela } from "../components/LogoPasarela";
import { CheckAnimado, IconoCandado } from "../components/Iconos";

/**
 * La pasarela simulada. En produccion esta pagina es la de Webpay o Khipu;
 * aca confirma el pago con la misma firma del enlace, que es lo que haria el
 * aviso firmado de la pasarela real.
 */
export default function Pasarela() {
  const { id } = useParams();
  const [params] = useSearchParams();
  const sig = params.get("sig") || "";
  const [pago, setPago] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    pagoPublico(id, sig)
      .then(setPago)
      .catch((err) => setError(err.status === 401 ? "Enlace de pago inválido" : err.message));
  }, [id, sig]);

  async function confirmar() {
    setBusy(true);
    setError("");
    try {
      setPago(await confirmarPagoPublico(id, sig));
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-panel" style={{ minHeight: "100vh" }}>
      <TemaToggle flotante />
      <div className="auth-card">
        <div className="card-cab" style={{ marginBottom: 4 }}>
          {pago ? <LogoPasarela id={pago.gateway} alto={24} /> : <span />}
          <span className="badge badge-warn">Placeholder / Simulación</span>
        </div>
        {pago ? <span className="eyebrow">Pago con {nombreDePasarela(pago.gateway)}</span> : null}
        {error ? <div className="error" style={{ marginTop: 14 }}>{error}</div> : null}
        {pago?.status === "paid" ? (
          <div className="success" style={{ padding: "20px 0 4px" }}>
            <CheckAnimado />
            <h2>Pago aprobado</h2>
            <p className="hint">Puedes cerrar esta ventana: el portal se actualiza solo.</p>
          </div>
        ) : pago ? (
          <>
            <h2 style={{ marginTop: 10 }}>Confirmar pago ({nombreDePasarela(pago.gateway)})</h2>
            <p className="hint" style={{ marginTop: -4, marginBottom: 12 }}>
              Pasarela en modo <b>placeholder</b> para pruebas locales. Al presionar <i>Pagar</i> se simula la aprobación y se concilia con tu deuda.
            </p>
            <div className="total-pagar">
              <span>Monto</span>
              <b>{dinero(pago.amount, pago.currency)}</b>
            </div>
            {pago.currency === "UF" && pago.amountClp ? (
              <p className="hint" style={{ marginTop: -8 }}>{dinero(pago.amountClp)} al valor de la UF de hoy</p>
            ) : null}
            <button className="btn btn-primary btn-block" style={{ marginTop: 14 }} disabled={busy} onClick={confirmar}>
              {busy ? <><span className="girando" /> Confirmando…</> : "Pagar"}
            </button>
            <p className="hint centro" style={{ display: "flex", gap: 6, justifyContent: "center", alignItems: "center" }}>
              <IconoCandado size={14} /> Enlace firmado: el monto no se puede cambiar.
            </p>
          </>
        ) : !error ? (
          <div style={{ display: "grid", gap: 12, marginTop: 14 }}>
            <div className="esqueleto" style={{ height: 30, width: "60%" }} />
            <div className="esqueleto" style={{ height: 70 }} />
            <div className="esqueleto" style={{ height: 46 }} />
          </div>
        ) : null}
      </div>
    </div>
  );
}

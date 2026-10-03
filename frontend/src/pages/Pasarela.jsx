import { useEffect, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { cancelarPagoPublico, confirmarPagoPublico, pagoPublico, verificarPagoPublico } from "../api/pagos";
import { dinero } from "../utils/formato";
import TemaToggle from "../components/TemaToggle";
import LogoPasarela, { nombreDePasarela } from "../components/LogoPasarela";
import { CheckAnimado, IconoCandado } from "../components/Iconos";

/**
 * El pago en su ventana aparte.
 *
 * Con una pasarela simulada, confirma el pago con la misma firma del enlace,
 * que es lo que haria el aviso firmado de la pasarela real. Con Khipu de
 * verdad el deudor paga en la pagina de Khipu, que lo devuelve aca sin decir
 * nada: esta pagina le pide a ms-payments que le pregunte a Khipu, y repite
 * mientras Khipu verifica la transferencia. Si volvio por "cancelar", el pago
 * queda fallido, salvo que haya alcanzado a pagar.
 */
const CADA_MS = 4000;
const INTENTOS = 45;

export default function Pasarela() {
  const { id } = useParams();
  const [params] = useSearchParams();
  const sig = params.get("sig") || "";
  const cancelado = params.get("cancelado") === "1";
  const [pago, setPago] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [intentos, setIntentos] = useState(0);

  useEffect(() => {
    pagoPublico(id, sig)
      .then(async (p) => {
        if (p.simulada || p.status !== "created") return setPago(p);
        setPago(await (cancelado ? cancelarPagoPublico(id, sig) : verificarPagoPublico(id, sig)));
      })
      .catch((err) => setError(err.status === 401 ? "Enlace de pago inválido" : err.message));
  }, [id, sig, cancelado]);

  // Khipu puede tardar unos segundos en conciliar la transferencia.
  const verificando = pago && !pago.simulada && pago.status === "created";
  useEffect(() => {
    if (!verificando || intentos >= INTENTOS) return;
    const t = setTimeout(async () => {
      try {
        setPago(await verificarPagoPublico(id, sig));
      } catch {
        /* Khipu no respondio: se reintenta */
      }
      setIntentos((n) => n + 1);
    }, CADA_MS);
    return () => clearTimeout(t);
  }, [verificando, intentos, id, sig]);

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
          {pago?.simulada ? <span className="badge badge-muted">Simulación</span> : null}
        </div>
        {pago ? <span className="eyebrow">Pago con {nombreDePasarela(pago.gateway)}</span> : null}
        {error ? <div className="error" style={{ marginTop: 14 }}>{error}</div> : null}
        {pago?.status === "paid" ? (
          <div className="success" style={{ padding: "20px 0 4px" }}>
            <CheckAnimado />
            <h2>Pago aprobado</h2>
            <p className="hint">Puedes cerrar esta ventana: el portal se actualiza solo.</p>
          </div>
        ) : pago?.status === "failed" || pago?.status === "expired" ? (
          <div style={{ padding: "16px 0 4px" }}>
            <h2>El pago no se completó</h2>
            <p className="hint">
              {pago.status === "expired"
                ? "Pasó el plazo para pagarlo."
                : `${nombreDePasarela(pago.gateway)} lo rechazó, o lo anulaste.`}{" "}
              No se te cobró nada: cierra esta ventana y vuelve a intentarlo desde el portal.
            </p>
          </div>
        ) : verificando ? (
          <div style={{ padding: "16px 0 4px" }}>
            <h2>{intentos < INTENTOS ? <><span className="girando" /> Verificando tu pago</> : "Todavía no vemos tu pago"}</h2>
            <p className="hint">
              {intentos < INTENTOS
                ? `${nombreDePasarela(pago.gateway)} está confirmando la transferencia. Toma unos segundos.`
                : `Si ya pagaste en ${nombreDePasarela(pago.gateway)}, aparecerá solo en el portal en unos minutos.`}{" "}
              Puedes cerrar esta ventana: el portal se actualiza solo.
            </p>
          </div>
        ) : pago ? (
          <>
            <h2 style={{ marginTop: 10 }}>Confirmar pago</h2>
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

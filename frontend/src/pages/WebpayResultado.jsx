import { useSearchParams, Link } from "react-router-dom";
import TemaToggle from "../components/TemaToggle";
import LogoPasarela from "../components/LogoPasarela";
import { CheckAnimado, IconoCandado, IconoCheck, IconoVolver } from "../components/Iconos";
import { dinero } from "../utils/formato";

/**
 * Pantalla de resultado tras retornar de Transbank Webpay Plus.
 */
export default function WebpayResultado() {
  const [params] = useSearchParams();
  const status = params.get("status") || "success";
  const id = params.get("id");
  const debtId = params.get("debtId");
  const amount = params.get("amount");
  const currency = params.get("currency") || "CLP";
  const errorMsg = params.get("error");

  const esExitoso = status === "approved" || status === "success";
  const esCancelado = status === "cancelled" || status === "rejected";

  return (
    <div className="auth-panel" style={{ minHeight: "100vh" }}>
      <TemaToggle flotante />
      <div className="auth-card aparece" style={{ maxWidth: 460 }}>
        <div className="card-cab" style={{ marginBottom: 12 }}>
          <LogoPasarela id="webpay" alto={28} />
          <span className={`badge ${esExitoso ? "badge-ok" : "badge-warn"}`}>
            Transbank Webpay (TEST)
          </span>
        </div>

        {esExitoso ? (
          <div className="success" style={{ padding: "10px 0" }}>
            <CheckAnimado />
            <h2 style={{ margin: "14px 0 6px" }}>¡Pago aprobado con éxito!</h2>
            <p className="hint">
              Tu transacción fue autorizada por el ambiente de integración de Transbank Webpay Plus.
            </p>

            {amount ? (
              <div className="total-pagar" style={{ margin: "18px 0" }}>
                <span>Monto pagado</span>
                <b>{dinero(amount, currency)}</b>
              </div>
            ) : null}

            <div style={{ display: "grid", gap: 10, marginTop: 16 }}>
              <Link className="btn btn-primary btn-block" to="/app">
                Ver mis deudas actualizadas
              </Link>
            </div>
          </div>
        ) : esCancelado ? (
          <div style={{ padding: "10px 0" }}>
            <div className="alerta alerta-warn" style={{ marginBottom: 16 }}>
              <b>Transacción no completada</b>
              <p style={{ margin: "4px 0 0", fontSize: "0.9rem" }}>
                La operación fue cancelada o rechazada en la pasarela de Webpay.
              </p>
            </div>
            <Link
              className="btn btn-primary btn-block"
              to={debtId ? `/app/pagar/${debtId}` : "/app"}
            >
              <IconoVolver size={16} /> Intentar nuevamente
            </Link>
          </div>
        ) : (
          <div style={{ padding: "10px 0" }}>
            <div className="error" style={{ marginBottom: 16 }}>
              <b>Error al procesar el pago</b>
              <p style={{ margin: "4px 0 0", fontSize: "0.9rem" }}>
                {errorMsg || "Ocurrió un error inesperado al confirmar la transacción con Webpay."}
              </p>
            </div>
            <Link className="btn btn-primary btn-block" to="/app">
              Volver al portal
            </Link>
          </div>
        )}

        <p
          className="hint centro"
          style={{
            display: "flex",
            gap: 6,
            justifyContent: "center",
            alignItems: "center",
            marginTop: 18,
          }}
        >
          <IconoCandado size={14} /> Transacción segura procesada vía Transbank REST API
        </p>
      </div>
    </div>
  );
}

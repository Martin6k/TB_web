import { Link } from "react-router-dom";
import Logo from "../components/Logo";
import TemaToggle from "../components/TemaToggle";
import BarraEstado from "../components/BarraEstado";
import { IconoFlecha } from "../components/Iconos";

/**
 * Una deuda de muestra: lo que el deudor va a ver adentro, en vez de describirlo.
 * Es la de Fernanda en la demo: un implante dental en 6 cuotas, con la primera pagada.
 */
const MUESTRA = { estado: "repacted", cuotasPagadas: 1, cuotasTotales: 6, conConvenio: true };

export default function Landing() {
  return (
    <div className="auth-shell">
      <TemaToggle flotante />
      <section className="auth-hero">
        <div className="brand-row aparece">
          <Logo size={46} />
          <div>
            <div className="brand-name">Technical Bridge</div>
            <div className="brand-sub">Pago de deudas en cobranza</div>
          </div>
        </div>

        <div className="hero-copy">
          <h1 className="aparece" style={{ "--i": 1 }}>Revisa y paga tus deudas atrasadas</h1>
          <p className="aparece" style={{ "--i": 2 }}>
            Entras con tu RUT y el código que te llegó por correo. Ves cuánto debes y a quién, sea un arriendo,
            un arancel o un tratamiento, y decides si pagas todo de una vez o en cuotas, sin intereses.
          </p>

          <div className="muestra aparece" style={{ "--i": 3 }}>
            <div className="card">
              <div className="deuda-cab" style={{ marginBottom: 18 }}>
                <div>
                  <span className="eyebrow">Clínica Dental Sonrisa Norte</span>
                  <h3 style={{ margin: "2px 0 0", fontSize: 20 }}>Implante dental</h3>
                </div>
                <div className="deuda-monto">
                  <span>Saldo</span>
                  <b style={{ fontSize: 24 }}>$1.208.334</b>
                </div>
              </div>
              <BarraEstado deuda={MUESTRA} />
            </div>
            <p className="muestra-pie">Así ves cada deuda adentro, con el avance de tus cuotas.</p>
          </div>
        </div>

        <span className="brand-sub">Las empresas, personas y RUT de esta demo son ficticios.</span>
      </section>

      <section className="auth-panel">
        <div className="auth-card">
          <h2>¿Cómo quieres entrar?</h2>
          <div className="opciones-entrada">
            <Link className="btn btn-primary btn-block" to="/login">
              Tengo un código de acceso
              <IconoFlecha size={16} />
            </Link>
            <Link className="btn btn-ghost btn-block" to="/databridge/login">Soy de una empresa</Link>
          </div>
          <p className="hint">
            Nunca te vamos a pedir que entres desde un enlace en un correo o un mensaje. Escribe esta
            dirección tú mismo.
          </p>
        </div>
      </section>
    </div>
  );
}

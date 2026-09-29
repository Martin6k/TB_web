import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { descargarCertificado, listarDeudas } from "../api/deudas";
import { dinero, porMoneda, rutLegible, totalDeLaDeuda } from "../utils/formato";
import { useAuth } from "../store/authStore";
import BarraEstado from "../components/BarraEstado";
import Cargando from "../components/Cargando";
import { CheckAnimado, IconoCalendario, IconoDocumento, IconoFlecha } from "../components/Iconos";

export default function Debts() {
  const { user } = useAuth();
  const [deudas, setDeudas] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    listarDeudas()
      .then(setDeudas)
      .catch((err) => {
        setError(err.status === 404 ? "" : err.message);
        setDeudas([]);
      });
  }, []);

  if (deudas === null) return <Cargando />;

  const vigentes = deudas.filter((d) => d.estado === "open" || d.estado === "repacted");
  const nombre = deudas[0]?.deudor?.split(" ")[0];
  const enConvenio = deudas.filter((d) => d.estado === "repacted").length;

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <span className="eyebrow">RUT {rutLegible(user?.rut)}</span>
          <h1>{nombre ? `Hola, ${nombre}` : "Hola"}</h1>
          <p>
            {vigentes.length
              ? "Esto es lo que debes, a quién, y en qué va cada deuda."
              : "No tienes deudas por pagar."}
          </p>
        </div>
      </header>
      {error ? <div className="error">{error}</div> : null}

      <section className="grid-3 stats bloque">
        <div className="card stat aparece" style={{ "--i": 1 }}>
          <span>Por pagar</span>
          <b className="totales">
            {porMoneda(vigentes, "saldo").map(([moneda, total]) => (
              <span key={moneda}>{dinero(total, moneda)}</span>
            ))}
            {vigentes.length === 0 ? dinero(0) : null}
          </b>
        </div>
        <div className="card stat aparece" style={{ "--i": 2 }}>
          <span>En convenio de pago</span>
          <b>{enConvenio}</b>
          <small>{vigentes.length - enConvenio} sin convenio</small>
        </div>
        <div className="card stat aparece" style={{ "--i": 3 }}>
          <span>Pagadas</span>
          <b>{deudas.filter((d) => d.estado === "paid").length}</b>
        </div>
      </section>

      {deudas.length === 0 ? (
        <div className="card al-dia aparece" style={{ "--i": 4 }}>
          <CheckAnimado size={64} />
          <h3>Estás al día</h3>
          <p>No hay deudas a tu nombre en Technical Bridge.</p>
        </div>
      ) : (
        <section className="deudas">
          {deudas.map((d, i) => <TarjetaDeuda key={d.id} deuda={d} i={i + 4} />)}
        </section>
      )}
    </div>
  );
}

function TarjetaDeuda({ deuda: d, i }) {
  const pagada = d.estado === "paid";
  const cobrable = d.estado === "open" || d.estado === "repacted";
  const abonado = Number(d.pagado) > 0 && !pagada;

  return (
    <article className="card deuda lift aparece" style={{ "--i": i }}>
      <div className="deuda-cab">
        <div>
          <span className="eyebrow">{d.acreedor}</span>
          <h3>{d.concepto}</h3>
          <span className="sub">Contrato {d.externalId}</span>
          {d.estado === "repacted" && d.cuotasVencidas > 0 ? (
            <span className="tag tag-vencida" style={{ marginTop: 8 }}>
              {d.cuotasVencidas === 1 ? "Una cuota vencida" : `${d.cuotasVencidas} cuotas vencidas`}
            </span>
          ) : null}
        </div>
        <div className="deuda-monto">
          <span>{pagada ? "Pagaste" : "Saldo"}</span>
          <b>{dinero(pagada ? d.pagado : d.saldo, d.moneda)}</b>
          {abonado ? <small>de {dinero(totalDeLaDeuda(d), d.moneda)}</small> : null}
        </div>
      </div>

      <BarraEstado deuda={d} />

      <div className="deuda-acciones">
        {pagada ? (
          <button className="btn btn-soft btn-sm" type="button" onClick={() => descargarCertificado(d.id)}>
            <IconoDocumento size={16} />
            Certificado de pago
          </button>
        ) : null}
        {d.estado === "open" ? (
          <Link className="btn btn-ghost btn-sm" to={`/app/repactar/${d.id}`}>
            <IconoCalendario size={16} />
            Pagar en cuotas
          </Link>
        ) : null}
        {cobrable ? (
          <Link className="btn btn-primary btn-sm" to={`/app/pagar/${d.id}`}>
            {d.estado === "repacted" ? "Pagar cuotas" : "Pagar"}
            <IconoFlecha size={16} />
          </Link>
        ) : null}
      </div>
    </article>
  );
}

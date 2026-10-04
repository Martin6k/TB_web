import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { descargarCertificado, disputar, listarDeudas } from "../api/deudas";
import { dinero, MOTIVOS_DISPUTA, porMoneda, rutLegible, totalDeLaDeuda } from "../utils/formato";
import { useAuth } from "../store/authStore";
import BarraEstado from "../components/BarraEstado";
import Cargando from "../components/Cargando";
import { CheckAnimado, IconoCalendario, IconoDocumento, IconoFlecha } from "../components/Iconos";

export default function Debts() {
  const { user } = useAuth();
  const [deudas, setDeudas] = useState(null);
  const [error, setError] = useState("");

  function cargar() {
    return listarDeudas()
      .then(setDeudas)
      .catch((err) => {
        setError(err.status === 404 ? "" : err.message);
        setDeudas([]);
      });
  }

  useEffect(() => {
    cargar();
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
          {deudas.map((d, i) => <TarjetaDeuda key={d.id} deuda={d} i={i + 4} onCambio={cargar} />)}
        </section>
      )}
    </div>
  );
}

function TarjetaDeuda({ deuda: d, i, onCambio }) {
  const pagada = d.estado === "paid";
  const cobrable = d.estado === "open" || d.estado === "repacted";
  const abonado = Number(d.pagado) > 0 && !pagada;
  const [disputando, setDisputando] = useState(false);

  return (
    <article className="card deuda lift aparece" style={{ "--i": i }}>
      <div className="deuda-cab">
        <div>
          <span className="eyebrow">{d.acreedor}</span>
          <h3>{d.concepto}</h3>
          <span className="sub">Ref. {d.externalId}</span>
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

      {d.estado === "disputed" ? (
        <div className="aviso-revision">
          <b>En revisión: {MOTIVOS_DISPUTA[d.disputa?.motivo] || "lo que nos dijiste"}</b>
          <span>
            {d.acreedor} está revisando tu caso. Mientras tanto esta deuda no se cobra ni te llegan recordatorios.
          </span>
        </div>
      ) : null}

      {disputando ? (
        <FormularioDisputa deuda={d} onCancelar={() => setDisputando(false)}
                           onListo={() => { setDisputando(false); onCambio(); }} />
      ) : null}

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
        {cobrable && !disputando ? (
          <button type="button" className="link-btn" onClick={() => setDisputando(true)}>
            No reconozco esta deuda
          </button>
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

/**
 * Decir que una deuda no corresponde. La empresa que cobra la revisa, y
 * mientras tanto no se cobra. Lo que se escribe aca lo ve solo esa empresa.
 */
function FormularioDisputa({ deuda, onCancelar, onListo }) {
  const [motivo, setMotivo] = useState("");
  const [detalle, setDetalle] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function enviar(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await disputar(deuda.id, motivo, detalle.trim() || null);
      onListo();
    } catch (err) {
      setError(err.message);
      setBusy(false);
    }
  }

  return (
    <form className="disputa" onSubmit={enviar}>
      <b>¿Por qué no corresponde?</b>
      <div className="disputa-motivos">
        {Object.entries(MOTIVOS_DISPUTA).map(([codigo, texto]) => (
          <label key={codigo} className={`chip ${motivo === codigo ? "on" : ""}`}>
            <input type="radio" name={`motivo-${deuda.id}`} value={codigo} checked={motivo === codigo}
                   onChange={() => setMotivo(codigo)} />
            {texto}
          </label>
        ))}
      </div>
      <textarea rows={3} maxLength={500} value={detalle} onChange={(e) => setDetalle(e.target.value)}
                placeholder="Si quieres, cuéntanos más: cuándo pagaste, qué monto debería ser…" />
      {error ? <div className="error">{error}</div> : null}
      <div className="deuda-acciones">
        <button type="button" className="btn btn-ghost btn-sm" onClick={onCancelar} disabled={busy}>Cancelar</button>
        <button type="submit" className="btn btn-primary btn-sm" disabled={busy || !motivo}>
          {busy ? <><span className="girando" /> Enviando…</> : "Enviar a revisión"}
        </button>
      </div>
    </form>
  );
}

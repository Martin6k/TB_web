import { Fragment, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { listarVencimientos } from "../api/deudas";
import { cuandoVence, dinero, fechaLarga, mesYAnio } from "../utils/formato";
import { descargarCalendario } from "../utils/calendario";
import Cargando from "../components/Cargando";
import { CheckAnimado, IconoAlerta, IconoCalendario } from "../components/Iconos";

/**
 * Todas las cuotas por pagar, por fecha. Las vencidas van primero y aparte:
 * es lo que el deudor tiene que resolver antes que nada.
 */
export default function Vencimientos() {
  const [cuotas, setCuotas] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    listarVencimientos()
      .then(setCuotas)
      .catch((err) => {
        setError(err.status === 404 ? "" : err.message);
        setCuotas([]);
      });
  }, []);

  if (cuotas === null) return <Cargando tarjetas={1} />;

  const vencidas = cuotas.filter((c) => c.vencida);
  const proximas = cuotas.filter((c) => !c.vencida);
  const siguiente = proximas[0];

  //  Las proximas, agrupadas por mes.
  const grupos = [];
  if (vencidas.length) grupos.push({ titulo: "Vencidas", cuotas: vencidas });
  for (const c of proximas) {
    const titulo = mesYAnio(c.vencimiento);
    const grupo = grupos.find((g) => g.titulo === titulo);
    if (grupo) grupo.cuotas.push(c);
    else grupos.push({ titulo, cuotas: [c] });
  }

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Próximos vencimientos</h1>
          <p>
            {siguiente
              ? `Tu próximo pago es el ${fechaLarga(siguiente.vencimiento)}: ${dinero(siguiente.monto, siguiente.moneda)}.`
              : vencidas.length ? "No tienes pagos por venir, pero sí pagos vencidos." : "No tienes cuotas por pagar."}
          </p>
        </div>
        {cuotas.length ? (
          <div className="topbar-acciones">
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => descargarCalendario(cuotas)}>
              <IconoCalendario size={16} />
              Agregar a mi calendario
            </button>
          </div>
        ) : null}
      </header>
      {error ? <div className="error">{error}</div> : null}

      {vencidas.length ? (
        <div className="aviso bloque aparece" style={{ "--i": 1 }}>
          <IconoAlerta />
          <div>
            <b>{vencidas.length === 1 ? "Tienes un pago vencido" : `Tienes ${vencidas.length} pagos vencidos`}</b>
            <span className="sub">
              Mientras antes lo pagues, mejor: un convenio con cuotas atrasadas se puede caer.
            </span>
          </div>
        </div>
      ) : null}

      {cuotas.length === 0 ? (
        <div className="card al-dia aparece" style={{ "--i": 1 }}>
          <CheckAnimado size={64} />
          <h3>Nada por pagar</h3>
          <p>Cuando tengas cuotas, aparecen aquí por fecha.</p>
        </div>
      ) : (
        <div className="card aparece" style={{ "--i": 2 }}>
          <div className="lista">
            {grupos.map((grupo) => (
              <Fragment key={grupo.titulo}>
                <div className="lista-grupo">{grupo.titulo}</div>
                {grupo.cuotas.map((c, i) => <FilaCuota key={c.id} cuota={c} i={i} />)}
              </Fragment>
            ))}
          </div>
          <p className="hint">
            El archivo de calendario trae cada cuota con un aviso el día anterior. No trae enlaces.
          </p>
        </div>
      )}
    </div>
  );
}

function FilaCuota({ cuota: c, i }) {
  const dia = new Date(`${c.vencimiento}T12:00:00`);
  return (
    <div className="fila" style={{ "--i": i }}>
      <div className={`fecha-caja${c.vencida ? " vencida" : ""}`}>
        <b>{dia.getDate()}</b>
        <span>{dia.toLocaleDateString("es-CL", { month: "short" }).replace(".", "")}</span>
      </div>
      <div className="fila-que">
        <b>{c.enConvenio ? `Cuota ${c.lugar} de ${c.deCuotas}`
          : c.fueraDelConvenio ? "Fuera del convenio" : "Pago de la deuda completa"}</b>
        <span>{c.concepto} con {c.acreedor}. {cuandoVence(c.dias)}.</span>
      </div>
      <div className="fila-monto">
        <b>{dinero(c.monto, c.moneda)}</b>
        <Link className="link-btn" to={`/app/pagar/${c.deudaId}`}>Pagar</Link>
      </div>
    </div>
  );
}

import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { obtenerDeuda } from "../api/deudas";
import { abrirCobro, obtenerPago } from "../api/pagos";
import { dinero, fecha, hoyEnChile } from "../utils/formato";
import BarraEstado from "../components/BarraEstado";
import Cargando from "../components/Cargando";
import LogoPasarela, { PASARELAS, nombreDePasarela } from "../components/LogoPasarela";
import { CheckAnimado, IconoCandado, IconoCheck, IconoFlecha, IconoVolver } from "../components/Iconos";


const porVencimiento = (a, b) => a.vencimiento.localeCompare(b.vencimiento) || a.numero - b.numero;

/**
 * Pagar una deuda, o las cuotas de su convenio.
 *
 * Las cuotas se eligen EN ORDEN, desde la que vence primero: marcar la
 * tercera marca tambien la primera y la segunda, y desmarcar la segunda
 * desmarca las que siguen. ms-debt exige lo mismo, porque pagar diciembre
 * dejando octubre impago dejaria al deudor en mora con plata pagada.
 *
 * El monto no lo manda la pantalla: ms-payments se lo pregunta a ms-debt. Si
 * lo mandara el navegador, bastaria editar la peticion para pagar un peso.
 */
export default function Pay() {
  const { id } = useParams();
  const [deuda, setDeuda] = useState(null);
  const [pasarela, setPasarela] = useState("webpay");
  const [cuantas, setCuantas] = useState(1);
  const [pago, setPago] = useState(null);
  const [acreditado, setAcreditado] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    obtenerDeuda(id).then(setDeuda).catch((err) => setError(err.message));
  }, [id]);

  // Mientras la pasarela no confirma, se consulta el pago. Fallido (Khipu lo
  // rechazo, o el deudor lo anulo) o vencido, no hay nada mas que esperar. El
  // enlace a la pasarela solo viene al abrir el cobro: se conserva.
  useEffect(() => {
    if (!pago || ["paid", "failed", "expired"].includes(pago.status)) return;
    const t = setInterval(async () => {
      try {
        const nuevo = await obtenerPago(pago.id);
        setPago((antes) => ({ ...nuevo, checkoutUrl: antes?.checkoutUrl }));
      } catch {
        /* se reintenta en el proximo ciclo */
      }
    }, 2000);
    return () => clearInterval(t);
  }, [pago?.id, pago?.status]);

  // Confirmado el pago, el aviso tarda unos segundos en llegar a la deuda.
  useEffect(() => {
    if (pago?.status !== "paid" || acreditado) return;
    const saldoAntes = Number(deuda?.saldo);
    const t = setInterval(async () => {
      const nueva = await obtenerDeuda(id).catch(() => null);
      if (nueva && Number(nueva.saldo) < saldoAntes) {
        setDeuda(nueva);
        setAcreditado(true);
      }
    }, 2000);
    return () => clearInterval(t);
  }, [pago?.status, acreditado]);

  if (!deuda) return error ? <div className="error">{error}</div> : <Cargando tarjetas={1} />;

  if (deuda.estado === "disputed") {
    return (
      <div className="card" style={{ maxWidth: 560 }}>
        <h2>Esta deuda está en revisión</h2>
        <p className="hint">
          Nos dijiste que no corresponde, y {deuda.acreedor} lo está revisando. Mientras tanto no se cobra: si la
          empresa confirma que corresponde, vuelve a aparecer para pagar.
        </p>
        <Link className="btn btn-ghost btn-sm" to="/app"><IconoVolver size={16} /> Volver a mis deudas</Link>
      </div>
    );
  }

  const moneda = deuda.moneda;
  const vigentes = (deuda.cuotas || []).filter((c) => c.estado !== "anulada").sort(porVencimiento);
  const pendientes = vigentes.filter((c) => c.estado === "pending");
  const pagadas = vigentes.filter((c) => c.estado === "paid");
  //  Las cuotas se numeran corrido entre planes (la 1 pudo quedar anulada al
  //  repactar): al deudor se le muestra su lugar dentro del convenio. Un mes
  //  que el acreedor informo despues va aparte y no corre la numeracion.
  const delPlan = vigentes.filter((c) => c.enConvenio);
  const lugar = (c) => (c.enConvenio ? `Cuota ${delPlan.indexOf(c) + 1} de ${delPlan.length}` : "Fuera del convenio");
  const enConvenio = deuda.conConvenio && pendientes.length > 0;
  const k = Math.min(cuantas, pendientes.length);
  const elegidas = enConvenio ? pendientes.slice(0, k) : pendientes;
  const monto = elegidas.reduce((suma, c) => suma + Number(c.monto), 0);
  const hoy = hoyEnChile();

  /** Marcar la cuota i marca todas las anteriores; desmarcarla, todas las que siguen. */
  const alternar = (i) => setCuantas(i < k ? i : i + 1);

  async function pagar() {
    setBusy(true);
    setError("");
    // Se abre la ventana de inmediato en el evento del usuario para que el navegador no la bloquee
    const popup = window.open("", "_blank", "noopener,width=480,height=720");
    try {
      const cuotas = enConvenio ? elegidas.map((c) => c.id) : null;
      const data = await abrirCobro(Number(id), cuotas, pasarela);
      setPago(data);
      if (popup) {
        popup.location.href = data.checkoutUrl;
      } else {
        window.location.href = data.checkoutUrl;
      }
    } catch (err) {
      if (popup) popup.close();
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <span className="eyebrow">{deuda.acreedor}</span>
          <h1>Pagar</h1>
          <p>{deuda.concepto}, ref. {deuda.externalId}</p>
        </div>
        <Link className="btn btn-ghost btn-sm" to="/app">
          <IconoVolver size={16} />
          Volver
        </Link>
      </header>
      {error ? <div className="error">{error}</div> : null}

      {pago?.status === "paid" ? (
        <div className="card success aparece">
          <CheckAnimado />
          <h2>{acreditado ? "Pago conciliado" : "Pago recibido"}</h2>
          <p>
            {dinero(pago.amount, pago.currency)}
            {pago.currency === "UF" && pago.amountClp ? `, ${dinero(pago.amountClp)} al valor de hoy` : ""}
          </p>
          <BarraEstado deuda={deuda} />
          {acreditado ? (
            <p className="hint">
              {Number(deuda.saldo) > 0
                ? `Tu saldo quedó en ${dinero(deuda.saldo, moneda)}. ${deuda.acreedor} ya fue avisado.`
                : `Pagaste todo. ${deuda.acreedor} ya fue avisado.`}
            </p>
          ) : (
            <p className="esperando"><span className="girando" /> Conciliando el pago con tu deuda…</p>
          )}
          <Link className="btn btn-primary" to="/app" style={{ marginTop: 16 }}>Ver mis deudas</Link>
        </div>
      ) : (
        <>
          <div className="card bloque aparece" style={{ "--i": 1 }}>
            <BarraEstado deuda={deuda} />
          </div>
          <div className="grid-2">
            <div className="card aparece" style={{ "--i": 2 }}>
              {enConvenio ? (
                <>
                  <div className="card-cab">
                    <h3>Elige las cuotas</h3>
                    <span className="badge badge-ok">{deuda.cuotasPagadas} de {deuda.cuotasTotales} pagadas</span>
                  </div>
                  <p className="hint" style={{ margin: "0 0 14px" }}>
                    Se pagan en orden, desde la que vence primero. Marca hasta donde quieras avanzar.
                  </p>
                  <div className="cuotas-lista">
                    {pagadas.map((c, i) => (
                      <div key={c.id} className="cuota-fila pagada" style={{ "--i": i }}>
                        <span className="casilla" aria-hidden="true"><IconoCheck size={14} /></span>
                        <span className="cuota-info">
                          <b>{lugar(c)}</b>
                          <span>Pagada el {fecha(c.pagadaEn)}</span>
                        </span>
                        <span className="cuota-monto">{dinero(c.monto, moneda)}</span>
                      </div>
                    ))}
                    {pendientes.map((c, i) => (
                      <label key={c.id} className={`cuota-fila${i < k ? " elegida" : ""}`}
                             style={{ "--i": i + pagadas.length }}>
                        <input type="checkbox" className="oculto" checked={i < k} disabled={!!pago}
                               onChange={() => alternar(i)} />
                        <span className="casilla" aria-hidden="true"><IconoCheck size={14} /></span>
                        <span className="cuota-info">
                          <b>{lugar(c)}</b>
                          <span>
                            Vence el {fecha(c.vencimiento)}
                            {c.vencimiento < hoy ? <span className="tag tag-vencida">Vencida</span> : null}
                          </span>
                        </span>
                        <span className="cuota-monto">{dinero(c.monto, moneda)}</span>
                      </label>
                    ))}
                  </div>
                  {pendientes.length > 1 ? (
                    <div className="atajos">
                      <button type="button" className={`chip${k === 1 ? " on" : ""}`} disabled={!!pago}
                              onClick={() => setCuantas(1)}>
                        Solo la próxima
                      </button>
                      <button type="button" className={`chip${k === pendientes.length ? " on" : ""}`}
                              disabled={!!pago} onClick={() => setCuantas(pendientes.length)}>
                        Todas ({pendientes.length})
                      </button>
                    </div>
                  ) : null}
                </>
              ) : (
                <>
                  <h3>Lo que debes</h3>
                  <table className="table">
                    <thead>
                      <tr><th>Concepto</th><th>Venció</th><th className="num">Monto</th></tr>
                    </thead>
                    <tbody>
                      {(deuda.cargos || []).map((c, i) => (
                        <tr key={i}>
                          <td>{c.concepto}</td>
                          <td>{fecha(c.vencimiento)}</td>
                          <td className="num">{dinero(c.monto, moneda)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {deuda.estado === "open" ? (
                    <p className="hint">
                      ¿Mucho de una vez?{" "}
                      <Link className="link-btn" to={`/app/repactar/${deuda.id}`}>Págalo en cuotas sin interés</Link>
                    </p>
                  ) : null}
                </>
              )}
            </div>

            <div className="card pegado aparece" style={{ "--i": 3 }}>
              <h3>Resumen</h3>
              <div className="total-pagar">
                <div>
                  <span>Total a pagar</span>
                  {enConvenio ? (
                    <small>{k === 0 ? "Ninguna cuota elegida" : k === 1 ? "1 cuota" : `${k} cuotas`}</small>
                  ) : null}
                </div>
                <b key={`${k}-${monto}`}>{dinero(monto, moneda)}</b>
              </div>
              <div className="methods">
                {Object.entries(PASARELAS).map(([id, p]) => (
                  <button key={id} type="button" className={`method${pasarela === id ? " on" : ""}`}
                          disabled={!!pago} onClick={() => setPasarela(id)} aria-label={p.nombre}>
                    <LogoPasarela id={id} alto={24} />
                    <span>{p.detalle}</span>
                  </button>
                ))}
              </div>
              <button className="btn btn-primary btn-block" disabled={busy || monto <= 0 || !!pago} onClick={pagar}>
                {busy ? <><span className="girando" /> Abriendo…</> : <>Pagar {dinero(monto, moneda)} <IconoFlecha size={16} /></>}
              </button>
              {pago?.status === "failed" || pago?.status === "expired" ? (
                <div className="error">
                  El pago no se completó en {nombreDePasarela(pago.gateway)}:{" "}
                  {pago.status === "expired" ? "pasó el plazo para pagarlo" : "se rechazó o lo anulaste"}. No se te
                  cobró nada.{" "}
                  <button type="button" className="link-btn" onClick={() => setPago(null)}>Intentar de nuevo</button>
                </div>
              ) : pago ? (
                <div className="esperando">
                  <span className="girando" />
                  Esperando la confirmación de la pasarela…{" "}
                  <button type="button" className="link-btn"
                          onClick={() => window.open(pago.checkoutUrl, "_blank", "noopener,width=480,height=720")}>
                    Abrirla de nuevo
                  </button>
                </div>
              ) : (
                <p className="hint centro" style={{ display: "flex", gap: 6, justifyContent: "center", alignItems: "center" }}>
                  <IconoCandado size={14} />
                  {moneda === "UF" ? "Se cobra en pesos, al valor de la UF de hoy." : "Pago seguro: el monto sale de tu deuda registrada."}
                </p>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

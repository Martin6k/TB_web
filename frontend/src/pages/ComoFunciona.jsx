import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { opcionesDeCarga } from "../api/deudas";
import BarraEstado from "../components/BarraEstado";

const EJEMPLO = { estado: "repacted", cuotasPagadas: 2, cuotasTotales: 6, conConvenio: true };

/** Como funciona el portal, contado para quien lo usa: el deudor o la empresa. */
export default function ComoFunciona({ para }) {
  return para === "empresa" ? <ParaEmpresas /> : <ParaDeudores />;
}

function Pasos({ pasos }) {
  return (
    <ol className="guia">
      {pasos.map(([titulo, texto], i) => (
        <li key={titulo} className="aparece" style={{ "--i": i + 1 }}>
          <span className="n">{i + 1}</span>
          <div>
            <b>{titulo}</b>
            <p>{texto}</p>
          </div>
        </li>
      ))}
    </ol>
  );
}

function Preguntas({ preguntas }) {
  return (
    <div className="preguntas">
      {preguntas.map(([pregunta, respuesta]) => (
        <details key={pregunta}>
          <summary>{pregunta}</summary>
          <p>{respuesta}</p>
        </details>
      ))}
    </div>
  );
}

function ParaDeudores() {
  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Cómo funciona</h1>
          <p>Desde que te llega el código hasta que tu deuda queda en cero.</p>
        </div>
      </header>

      <div className="grid-2">
        <div className="card aparece" style={{ "--i": 1 }}>
          <Pasos pasos={[
            ["Te llega un código", "La empresa a la que le debes, o la agencia que cobra por ella, te manda un código de seis caracteres por correo. Nunca un enlace."],
            ["Entras con tu RUT y el código", "Escribes tú la dirección del portal. El código sirve una vez y dura 24 horas; si se te vence, pides otro."],
            ["Ves lo que debes", "Cada deuda con su monto, a quién se la debes y los meses que incluye."],
            ["Pagas como te acomode", "Todo de una vez, o en un convenio de 3 a 24 cuotas sin intereses. En convenio puedes pagar una cuota o varias juntas, siempre desde la que vence primero."],
            ["Queda conciliado", "Cuando la pasarela confirma el pago, se abona a tu deuda y la empresa recibe el aviso. Cada pago tiene su comprobante y, al terminar, descargas el certificado de deuda pagada."],
          ]} />
        </div>

        <div>
          <div className="card aparece" style={{ "--i": 2, marginBottom: 16 }}>
            <h3>Tu deuda avanza por tres estados</h3>
            <BarraEstado deuda={EJEMPLO} />
            <p className="hint">
              Pendiente mientras no hagas nada; en convenio si eliges pagar en cuotas, y la barra avanza con
              cada una; pago conciliado cuando terminas.
            </p>
          </div>
          <div className="card aparece" style={{ "--i": 3 }}>
            <h3>Preguntas frecuentes</h3>
            <Preguntas preguntas={[
              ["¿Por qué no me mandan un enlace para entrar?", "Porque un enlace en un correo es justo lo que usan las estafas para llevarte a una página falsa. Como nosotros nunca mandamos uno, cualquier mensaje con enlace que diga venir de aquí es falso."],
              ["¿Cobran intereses por pagar en cuotas?", "No. El total del convenio es lo que debes hoy, dividido en las cuotas que elijas. La última cuota absorbe el redondeo."],
              ["¿Puedo pagar varias cuotas a la vez?", "Sí, siempre desde la que vence primero. Así nunca queda una cuota antigua impaga mientras pagas una nueva."],
              ["Mi deuda está en UF: ¿cuánto pago en pesos?", "Pagas en pesos, al valor de la UF del día en que pagas. El comprobante dice qué UF se usó."],
              ["¿Cómo sé que esto es legítimo?", "En Mis datos ves qué empresa te registró y el correo que tiene de ti. Si no reconoces la deuda, no pagues y comunícate directo con esa empresa."],
              ["¿Qué pasa si se me atrasa una cuota?", "La empresa ve que el convenio está atrasado y te va a contactar. Mientras antes pagues la cuota vencida, mejor."],
              ["¿Me van a recordar las cuotas?", "Sí, por correo, unos días antes de cada vencimiento. Puedes apagar esos avisos en Mis datos."],
            ]} />
          </div>
        </div>
      </div>

      <p className="hint">
        Tus cuotas por fecha están en <Link className="link-btn" to="/app/vencimientos">Próximos vencimientos</Link>, y
        lo que ya pagaste en <Link className="link-btn" to="/app/pagos">Historial de pagos</Link>.
      </p>
    </div>
  );
}

function ParaEmpresas() {
  const [minimo, setMinimo] = useState(30);

  useEffect(() => {
    opcionesDeCarga().then((o) => setMinimo(o.minDiasMora ?? 30)).catch(() => {});
  }, []);

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Cómo funciona</h1>
          <p>De la cartera morosa al pago que vuelve a tu sistema.</p>
        </div>
      </header>

      <div className="grid-2">
        <div className="card aparece" style={{ "--i": 1 }}>
          <Pasos pasos={[
            ["La cartera llega", "Por la API del contrato de integración, desde tu sistema, o cargando el archivo CSV del mismo contrato en Cargar cartera."],
            ["Entran solo morosos", `Una deuda entra cuando su cargo impago más antiguo lleva ${minimo} días vencido, sea un arriendo mensual, un arancel o un tratamiento de un solo cargo. Las que no cumplen se rechazan solas, con su motivo: DataBridge no cobra lo que todavía no es mora.`],
            ["El deudor recibe su código", "Desde la cartera le envías el código a su correo. El código no se muestra aquí: quien lo viera podría entrar en su lugar."],
            ["Paga o pide un convenio", "El deudor paga todo o en 3 a 24 cuotas sin interés. Tú ves en qué va cada deuda: pendiente, en convenio o pago conciliado."],
            ["El pago vuelve a tu sistema", "Cada pago viaja de vuelta como un evento firmado a la dirección que registraste. Aquí lo ves en Pagos recibidos."],
          ]} />
        </div>

        <div>
          <div className="card aparece" style={{ "--i": 2, marginBottom: 16 }}>
            <h3>Integrar tu sistema</h3>
            <p style={{ margin: 0 }}>
              Emite una clave en <Link className="link-btn" to="/databridge/claves">Claves de API</Link> y úsala
              para entregar la cartera:
            </p>
            <pre className="codigo">{`POST /api/v1/carteras
Authorization: Bearer tbk_...
Content-Type: application/json`}</pre>
            <p className="hint">
              Todos los endpoints, con ejemplos, están en la <a className="link-btn" href="/swagger-ui.html"
              target="_blank" rel="noreferrer">documentación de la API</a>. Si no tienes integración, usa el CSV
              en <Link className="link-btn" to="/databridge/cargar">Cargar cartera</Link>.
            </p>
          </div>
          <div className="card aparece" style={{ "--i": 3 }}>
            <h3>Preguntas frecuentes</h3>
            <Preguntas preguntas={[
              ["¿Por qué se rechazó una deuda?", `Cada rechazo viene con su motivo. Los más comunes: bajo_umbral_mora (menos de ${minimo} días de mora), cargo_no_vencido (un cargo todavía no vence a la fecha de corte), rut_invalido y sin_canal_contacto (el deudor no trae correo ni teléfono).`],
              ["¿Qué es un convenio en riesgo?", "Un convenio con cuotas vencidas sin pagar. Aparecen en Convenios en riesgo, del más atrasado al menos, para que contactes al deudor antes de que el convenio se caiga."],
              ["¿Qué pasa si el deudor paga en nuestra oficina?", "Envía la deuda de nuevo con el saldo menor, o como retiro con motivo pago_directo. DataBridge deja de cobrarla en el acto."],
              ["¿Podemos ver el código del deudor?", "No. El código va directo al correo del deudor y nunca vuelve a la empresa."],
              ["¿Cómo exporto la cartera o los pagos?", "La cartera, los pagos recibidos y los convenios en riesgo tienen un botón para descargarlos en una planilla que abre Excel."],
            ]} />
          </div>
        </div>
      </div>
    </div>
  );
}

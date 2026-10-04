import { useEffect, useState } from "react";
import { cargarCartera, opcionesDeCarga } from "../api/deudas";
import { hoyEnChile, rutLegible } from "../utils/formato";

export const COLUMNAS = [
  "deuda_id", "accion", "motivo_retiro", "deudor_rut", "deudor_tipo", "deudor_nombre", "deudor_correo",
  "deudor_telefono", "moneda", "concepto", "referencias", "cargo_concepto", "cargo_periodo", "cargo_monto",
  "cargo_vencimiento",
];
//  Una fila por cargo, de cualquier rubro: aca, los aranceles de un instituto.
//  Un cliente al dia va en una sola fila sin cargo, y un retiro sin deudor.
const EJEMPLO = [
  "AND-2025-0412;registrar;;21345678-4;persona;Benjamín Araya Toro;benjamin.araya@correo.cl;+56944120387;CLP;Arancel Técnico en Enfermería;matricula=AND-2025-0412;Arancel julio;2026-07;185000;2026-07-10",
  "AND-2025-0412;registrar;;21345678-4;persona;Benjamín Araya Toro;benjamin.araya@correo.cl;+56944120387;CLP;Arancel Técnico en Enfermería;matricula=AND-2025-0412;Arancel agosto;2026-08;185000;2026-08-10",
  "AND-2025-0412;registrar;;21345678-4;persona;Benjamín Araya Toro;benjamin.araya@correo.cl;+56944120387;CLP;Arancel Técnico en Enfermería;matricula=AND-2025-0412;Arancel septiembre;2026-09;185000;2026-09-10",
  "AND-2025-0298;registrar;;22134567-3;persona;Martín Fuentes Ríos;martin.fuentes@correo.cl;;CLP;Arancel Técnico en Enfermería;matricula=AND-2025-0298;;;;",
  "AND-2023-0177;retirar;pago_directo;;;;;;;;;;;;",
];

/** La plantilla del contrato, con BOM para que Excel respete las tildes. */
function descargarPlantilla() {
  const texto = "\uFEFF" + [COLUMNAS.join(";"), ...EJEMPLO].join("\r\n") + "\r\n";
  const url = URL.createObjectURL(new Blob([texto], { type: "text/csv;charset=utf-8" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = "cartera-v1-plantilla.csv";
  a.click();
  URL.revokeObjectURL(url);
}

/**
 * Cargar cartera por archivo: el modo archivo del contrato, para quien no
 * tiene integracion por API. El archivo entra por la misma ingesta que la
 * API, con las mismas validaciones y la misma aceptacion parcial.
 */
export default function CargaCsv({ onCargada }) {
  const [opciones, setOpciones] = useState(null);
  const [acreedor, setAcreedor] = useState("");
  const [campana, setCampana] = useState("");
  const [corte, setCorte] = useState(hoyEnChile());
  const [lote, setLote] = useState(`CSV-${hoyEnChile()}-01`);
  const [archivo, setArchivo] = useState(null);
  const [arrastrando, setArrastrando] = useState(false);
  const [busy, setBusy] = useState(false);
  const [resultado, setResultado] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    opcionesDeCarga()
      .then((data) => {
        setOpciones(data);
        setAcreedor(data.acreedores?.[0]?.rut || "");
      })
      .catch((err) => setError(err.message));
  }, []);

  const elegido = opciones?.acreedores?.find((a) => a.rut === acreedor);

  async function subir(e) {
    e.preventDefault();
    if (!archivo) return;
    setBusy(true);
    setError("");
    setResultado(null);
    const form = new FormData();
    form.append("archivo", archivo);
    form.append("lote_id_externo", lote);
    form.append("fecha_corte", corte);
    form.append("acreedor_rut", acreedor);
    if (campana) form.append("campana_id_externo", campana);
    try {
      const r = await cargarCartera(form);
      setResultado(r);
      onCargada?.();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  const rechazos = (resultado?.resultados || []).filter((r) => r.resultado === "rechazada");

  return (
    <form
      className={`card dropzone ${arrastrando ? "on" : ""}`}
      onSubmit={subir}
      onDragOver={(e) => { e.preventDefault(); setArrastrando(true); }}
      onDragLeave={() => setArrastrando(false)}
      onDrop={(e) => {
        e.preventDefault();
        setArrastrando(false);
        if (e.dataTransfer.files?.[0]) setArchivo(e.dataTransfer.files[0]);
      }}
    >
      <div className="card-cab">
        <h3>Cargar cartera (CSV)</h3>
        <button type="button" className="link-btn" onClick={descargarPlantilla}>Descargar plantilla</button>
      </div>
      <p className="hint" style={{ margin: "-4px 0 16px" }}>
        Solo deudores morosos: una deuda entra cuando su cargo impago más antiguo lleva al menos{" "}
        {opciones?.minDiasMora ?? 30} días vencido. Las que no, se rechazan solas, con su motivo.
      </p>
      {error ? <div className="error">{error}</div> : null}

      <div className="grid-2">
        <div className="field">
          <label htmlFor="acreedor">Acreedor</label>
          <select id="acreedor" value={acreedor} onChange={(e) => { setAcreedor(e.target.value); setCampana(""); }}>
            {(opciones?.acreedores || []).map((a) => (
              <option key={a.rut} value={a.rut}>{a.nombre} ({rutLegible(a.rut)})</option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="campana">Campaña</label>
          <select id="campana" value={campana} onChange={(e) => setCampana(e.target.value)}
                  disabled={!elegido?.campanas?.length}>
            <option value="">{elegido?.campanas?.length ? "Sin campaña" : "No aplica"}</option>
            {(elegido?.campanas || []).map((c) => (
              <option key={c.idExterno} value={c.idExterno}>{c.nombre}</option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="lote">Id del lote</label>
          <input id="lote" value={lote} onChange={(e) => setLote(e.target.value)} required />
        </div>
        <div className="field">
          <label htmlFor="corte">Fecha de corte</label>
          <input id="corte" type="date" value={corte} onChange={(e) => setCorte(e.target.value)} required />
        </div>
      </div>

      <p className="hint" style={{ margin: "4px 0 12px" }}>
        {archivo
          ? <>Archivo: <b>{archivo.name}</b> ({Math.ceil(archivo.size / 1024)} KB)</>
          : "Arrastra aquí el CSV, o elígelo. Separador ; y una fila por cargo, como en la plantilla."}
      </p>
      <div className="acciones-fila">
        <label className="btn btn-ghost btn-sm">
          Elegir archivo
          <input type="file" accept=".csv,text/csv" hidden onChange={(e) => setArchivo(e.target.files?.[0] || null)} />
        </label>
        <button className="btn btn-primary btn-sm" disabled={!archivo || busy || !acreedor}>
          {busy ? <><span className="girando" /> Cargando…</> : "Cargar"}
        </button>
      </div>

      {resultado ? (
        <div className="notice" style={{ marginTop: 14 }}>
          {resultado.repetido ? "Ese lote ya se había cargado con el mismo contenido: no se duplicó nada. " : ""}
          {resultado.recibidas} deuda(s) en el archivo: <b>{resultado.aceptadas} aceptadas</b>
          {rechazos.length ? `, ${rechazos.length} rechazadas` : ""}.
          {rechazos.length ? (
            <ul style={{ margin: "8px 0 0", paddingLeft: 20 }}>
              {rechazos.slice(0, 5).map((r) => (
                <li key={r.id_externo}>{r.id_externo}: {r.errores?.[0]?.mensaje}</li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}
    </form>
  );
}

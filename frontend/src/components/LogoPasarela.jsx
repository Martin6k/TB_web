/**
 * Las pasarelas de pago, con su logo oficial.
 *
 * Los logos son marcas de sus duenos y estan tal como ellos los publican para
 * los comercios: Webpay en el portal de desarrolladores de Transbank, Mercado
 * Pago en su pagina de logo oficial (version digital), y Khipu en su
 * documentacion. Van sobre una ficha blanca en los dos temas, que es el fondo
 * para el que estan hechos: en el tema oscuro, sin ella, no se leerian.
 *
 * Cada archivo trae distinto aire alrededor del dibujo: Webpay casi nada, y
 * Mercado Pago mas de la mitad de su alto. `escala` lo compensa, para que los
 * tres se vean del mismo tamano. `cabe` es el alto maximo de la imagen, en
 * porcentaje del ancho de la ficha: en una tarjeta angosta el logo se achica
 * en vez de cortarse.
 */
export const PASARELAS = {
  webpay: { nombre: "Webpay", detalle: "Débito o crédito", escala: 1, cabe: 31 },
  mercadopago: { nombre: "Mercado Pago", detalle: "Saldo o tarjeta", escala: 1.9, cabe: 49 },
  khipu: { nombre: "Khipu", detalle: "Transferencia bancaria", escala: 1, cabe: 44 },
};

export const nombreDePasarela = (id) => PASARELAS[id]?.nombre || id || "Otro medio";

export default function LogoPasarela({ id, alto = 20 }) {
  const pasarela = PASARELAS[id];
  if (!pasarela) {
    return <span className="pasarela-logo sin-logo">{nombreDePasarela(id)}</span>;
  }
  return (
    <span className="pasarela-logo" title={pasarela.nombre} style={{ "--alto": `${alto}px` }}>
      <img src={`/pasarelas/${id}.svg`} alt={pasarela.nombre}
           style={{ height: `min(${Math.round(alto * pasarela.escala)}px, ${pasarela.cabe}cqw)` }} />
    </span>
  );
}

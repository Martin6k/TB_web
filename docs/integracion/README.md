# Integración modular — contratos v1

Cómo se conectan **Patrimonio Inmuebles**, **APOFYX** y **DataBridge** sin que ninguno dependa de
que el otro esté funcionando.

Proyecto de Capstone. Las empresas, personas, RUT y montos de los ejemplos son ficticios.

---

## 1. La cadena

```
 PATRIMONIO INMUEBLES          APOFYX                          DATABRIDGE
 inmobiliaria · acreedor       cobranza · agencia              pagos · plataforma
 ───────────────────────       ──────────────────              ──────────────────
 arrendatarios, contratos,     clientes, cartera recibida,     deudas, código de acceso,
 cargos, pagos en oficina      campañas, embudo, reportes      portal, repactación, cobro

        ① Cartera v1                   ① Cartera v1 + mandato
  ──────────────────────────►   ──────────────────────────────►
        sus morosos                    la cartera validada

                                       ② Mandato y Campaña v1
                                ──────────────────────────────►

        ③ Eventos v1                   ③ Eventos v1
  ◄──────────────────────────   ◄──────────────────────────────
    pagos de sus arrendatarios    pagos, repactaciones, avance
```

1. **Patrimonio** es la inmobiliaria. Administra arriendos, y los arrendatarios que se atrasan son
   su cartera morosa. Se la entrega a APOFYX.
2. **APOFYX** es la empresa de cobranza. Recibe la cartera, la valida, la prioriza y arma la
   campaña. Le pasa la cartera a DataBridge, que es su plataforma de pagos.
3. **DataBridge** le da al deudor el código de acceso y el portal, simula la repactación, cobra y
   concilia. Avisa cada pago a APOFYX, y APOFYX le reporta a Patrimonio.

Es el reparto de §13.6 del documento de APOFYX: la cartera, la estrategia y el reporte al cliente
son de APOFYX; el portal, el pago y la conciliación son de DataBridge.

**Patrimonio es el acreedor de ejemplo, no el único.** El contrato no sabe de rubros: el lugar de
Patrimonio lo ocupa cualquier empresa con cobros. En la demo también están un instituto
(aranceles mensuales) y una clínica dental (tratamientos de un solo cargo); §6.3 muestra cómo se
ve la cartera de cada uno.

### El mismo contrato en cada tramo

**Patrimonio le habla a APOFYX con el mismo formato con que APOFYX le habla a DataBridge.** Una
cartera es una Cartera v1 en los dos tramos, y un pago es un evento v1 en los dos tramos de vuelta.
De ahí salen tres propiedades:

- **Cada pieza se puede saltar.** Un acreedor sin agencia le manda su cartera directo a DataBridge
  con el mismo adaptador que usaría con APOFYX.
- **Cada pieza se puede cambiar.** Otra agencia de cobranza se enchufa a DataBridge igual que
  APOFYX, y cualquier otro acreedor —un instituto, una clínica, un gimnasio— se enchufa a APOFYX
  igual que Patrimonio.
- **Se escribe una sola vez.** Hay un esquema, un validador y un catálogo de eventos para toda la
  cadena.

---

## 2. Cada sistema funciona solo

| Sistema | Qué hace sin los otros |
| --- | --- |
| **Patrimonio** | Administra arriendos, emite cargos, registra pagos en oficina, lista morosos y exporta su cartera a archivo |
| **APOFYX** | Recibe carteras de sus clientes, las valida y prioriza, arma campañas y reporta. Sin DataBridge el pago ocurre fuera, en el canal del acreedor, como hoy |
| **DataBridge** | Recibe carteras de cualquier emisor autorizado, da acceso al deudor, simula repactaciones y cobra |

Y **sumar un cliente nuevo no requiere cambiar código** en APOFYX ni en DataBridge (§10).

---

## 3. Quién es quién en el contrato

| Rol | Quién | Qué pone |
| --- | --- | --- |
| **Acreedor** | Patrimonio, o cualquier empresa con deudores | La cartera: a quién se le cobra, cuánto y por qué |
| **Agencia** | APOFYX, o cualquier empresa de cobranza | La cartera validada, el mandato y la estrategia: canales, intentos, cadencia |
| **Plataforma** | DataBridge | Código de acceso, portal, repactación, pago, conciliación |
| **Deudor** | El arrendatario atrasado | Nada: entra al portal con su código y paga |

**Emisor** es quien envía una cartera en un tramo dado: Patrimonio frente a APOFYX, APOFYX frente a
DataBridge.

---

## 4. Reglas de modularidad

| # | Regla | Por qué |
| --- | --- | --- |
| **R1** | **Ningún sistema lee la base de otro.** Todo pasa por los contratos | Cada uno puede cambiar su esquema sin romper a los demás |
| **R2** | **La identidad entre sistemas es RUT + id externo**, nunca el `id` autoincremental de otro | Un `id = 42` no significa nada fuera de la base que lo generó |
| **R3** | **Todo envío es idempotente.** Mandar dos veces el mismo lote o evento no duplica nada | Las redes fallan y los reintentos son inevitables |
| **R4** | **Cada sistema guarda su propia copia** de lo que necesita, con la fecha en que lo supo | Si el otro está caído, se sigue trabajando con la última versión conocida |
| **R5** | **La integración se enciende por configuración** (URL + credenciales). Sin ella, el sistema oculta esas funciones y sigue operando | Es lo que hace que cada uno funcione solo |
| **R6** | **Lo que sale se encola antes de enviarse** (bandeja de salida). Si el receptor no responde, se reintenta | Una caída del otro lado no pierde carteras, pagos ni avisos |
| **R7** | **El receptor ignora los campos que no conoce** | Se pueden agregar campos sin sacar una versión nueva |
| **R8** | **El mismo contrato en cada tramo** | Cualquier pieza de la cadena se puede saltar o reemplazar (§1) |

---

## 5. Convenciones comunes

| Dato | Formato | Ejemplo |
| --- | --- | --- |
| RUT | Sin puntos, con guion, `K` mayúscula. Se valida el dígito verificador (módulo 11) | `76418902-7` |
| Fecha | ISO 8601 | `2026-09-18` |
| Fecha y hora | ISO 8601 con zona | `2026-09-18T09:00:00-03:00` |
| Teléfono | E.164, sin espacios | `+56987654321` |
| Moneda | `CLP` o `UF` | `UF` |
| Monto en CLP | Entero, sin decimales | `520000` |
| Monto en UF | Hasta 2 decimales | `38.5` |
| Id externo | 1 a 64 caracteres: letras, números, `.`, `_`, `-` | `CTR-2025-014` |

Es el mismo criterio de RUT que ya usa `crm_creditor.tax_id` en APOFYX, así que el cruce entre
sistemas es una comparación directa.

---

## 6. Contrato ① — Cartera v1

Esquema validable: [`cartera-v1.schema.json`](cartera-v1.schema.json) (JSON Schema draft-07).
Ejemplo completo, tal como Patrimonio se lo entrega a APOFYX:
[`ejemplos/cartera-v1.patrimonio.json`](ejemplos/cartera-v1.patrimonio.json).

### 6.1 Transporte

Lo atienden **APOFYX** (tramo acreedor → agencia) y **DataBridge** (tramo agencia → plataforma, o
acreedor → plataforma si no hay agencia). Es el mismo endpoint en los dos:

```
POST /api/v1/carteras
Authorization: Bearer <clave de API del emisor>
Content-Type: application/json
```

- **El emisor lo define la clave de API, no el cuerpo.** Un campo del cuerpo se puede falsificar;
  la clave no.
- **La idempotencia la da `lote.id_externo`**, único por emisor. Reenviar el mismo lote con el
  mismo contenido devuelve el resultado guardado con `"repetido": true`. Reenviarlo con **otro**
  contenido responde `409`: un id no se reutiliza.
- Máximo **5.000 deudas por lote**. Una cartera más grande se parte en varios lotes.

**Conectarse.** Antes de mandar nada, el emisor comprueba su clave. Los dos receptores atienden la
misma consulta, con la misma respuesta: quién es el dueño de la clave y con quién quedó conectado.

```
GET /api/v1/cuenta
Authorization: Bearer <clave de API del emisor>

{ "rut": "76418902-7", "razon_social": "Patrimonio Inmuebles SpA", "nombre": "Patrimonio Inmuebles",
  "tipo": "acreedor", "receptor": { "rut": "77305118-6", "nombre": "APOFYX" } }
```

Con eso el sistema del acreedor muestra «Conectado con APOFYX» sin tener el nombre de nadie escrito
en su código, y se suscribe a los avisos (§8.1). Así un mismo sistema se conecta a cualquier
agencia, o directo a DataBridge.

### 6.2 Cómo cambia la cartera de un tramo al otro

**Casi nada, y a propósito.** Cuando APOFYX le pasa la cartera a DataBridge:

| | Patrimonio → APOFYX | APOFYX → DataBridge |
| --- | --- | --- |
| `lote.id_externo` | El de Patrimonio: `PAT-2026-09-18-01` | Uno propio de APOFYX: `APX-2026-09-19-004`. Cada emisor numera sus lotes |
| `lote.acreedor` | Patrimonio | **El mismo**: la deuda sigue siendo de Patrimonio |
| `lote.mandato` | No viene | **APOFYX lo agrega**: su RUT y la campaña a la que asignó la cartera |
| `deudas[].id_externo` | Los de Patrimonio | **Los mismos.** Es lo que permite que un pago vuelva hasta el contrato correcto |
| `deudas[]` | Todos los clientes con contrato: los que deben, con sus cargos, y los que están al día, con `cargos: []` | Las que APOFYX aceptó, incluidas las al día. Las que rechazó o devolvió (más de 120 días) no siguen |

**APOFYX no cambia montos ni cargos.** Valida, deduplica, prioriza y decide qué entra a cada
campaña, pero lo que se debe lo fija el acreedor.

### 6.3 Estructura

```json
{
  "version": "1.0",
  "lote": {
    "id_externo": "PAT-2026-09-18-01",
    "fecha_corte": "2026-09-18",
    "acreedor": { "rut": "76418902-7", "razon_social": "Patrimonio Inmuebles SpA" }
  },
  "deudas": [
    {
      "id_externo": "CTR-2025-014",
      "deudor": {
        "rut": "16482337-7", "tipo": "persona", "nombre": "Felipe Rojas Muñoz",
        "correo": "felipe.rojas@correo.cl", "telefono": "+56987654321"
      },
      "moneda": "CLP",
      "concepto": "Arriendo mensual",
      "referencias": { "contrato": "CTR-2025-014", "propiedad": "Depto 1204, Av. Irarrázaval 2450, Ñuñoa" },
      "cargos": [
        { "concepto": "Arriendo agosto", "periodo": "2026-08", "monto": 520000, "fecha_vencimiento": "2026-08-05" },
        { "concepto": "Arriendo septiembre", "periodo": "2026-09", "monto": 520000, "fecha_vencimiento": "2026-09-05" }
      ]
    }
  ]
}
```

En el tramo APOFYX → DataBridge, el lote lleva además:

```json
"mandato": { "agencia_rut": "77305118-6", "campana_id_externo": "APX-PAT-2026-09" }
```

**`lote`**

| Campo | Obligatorio | Regla |
| --- | --- | --- |
| `id_externo` | Sí | Único por emisor. Clave de idempotencia |
| `fecha_corte` | Sí | Fecha a la que están calculados los montos. La mora se mide contra ella |
| `emitido_en` | No | Cuándo se generó el archivo |
| `acreedor.rut` | Sí | Tiene que coincidir con el emisor, o el emisor tiene que ser una agencia con mandato sobre él |
| `acreedor.razon_social` | No | Informativo: el receptor ya lo conoce por el RUT |
| `mandato` | No | Lo pone la agencia al reenviar. Sin mandato, es gestión directa del acreedor |

**Cada deuda**

| Campo | Obligatorio | Regla |
| --- | --- | --- |
| `id_externo` | Sí | El id de la obligación en el sistema del acreedor. Único por acreedor, no solo por lote. **Viaja intacto por toda la cadena** |
| `accion` | No | `registrar` (por defecto) o `retirar` |
| `deudor.rut` | Sí | Identifica a la persona entre deudas y acreedores |
| `deudor.tipo` | Sí | `persona` o `empresa` |
| `deudor.nombre` | Sí | Como figura en el contrato |
| `deudor.correo`, `deudor.telefono` | Al menos uno | **Con los dos, el código de acceso va por dos canales** (APOFYX §13.3). Con uno solo, va por ese |
| `moneda` | Sí | Una deuda tiene una sola moneda; todos sus cargos van en ella |
| `concepto` | Sí | Lo que el deudor lee primero en el portal |
| `referencias` | No | Pares clave–valor que **se le muestran al deudor** para que reconozca la deuda. Máximo 10 |
| `cargos` | Sí, al registrar | Lo que se debe, desglosado. El `monto` de cada cargo es **lo que se debe hoy** de ese cargo, no el original |

**`referencias` está pensado para la confianza del deudor.** Ver su contrato y la dirección de su
departamento, su número de matrícula o el presupuesto de su tratamiento, dentro de un portal al
que llegó escribiendo la dirección, es justo la evidencia que APOFYX no podía dar solo (§13.5 de
su documento).

**Cualquier rubro.** Los mismos campos sirven para cualquier cobro. Lo que cambia es qué pone cada
acreedor en ellos:

| Rubro | `id_externo` | `concepto` | `cargos` | `referencias` |
| --- | --- | --- | --- | --- |
| Arriendos (Patrimonio) | El contrato | Arriendo mensual | Un cargo por mes, con `periodo` | Contrato y propiedad |
| Aranceles (Instituto Andes) | La matrícula | Arancel de la carrera | Un cargo por mes, con `periodo` | Matrícula y carrera |
| Tratamientos (Sonrisa Norte) | El presupuesto | El tratamiento | **Un solo cargo**, sin `periodo` | Presupuesto y tratamiento |
| Planes mensuales (un gimnasio, un ISP) | El socio o el servicio | El plan | Un cargo por mes, con `periodo` | Plan y sucursal |

Una deuda de un solo cargo se ve así. Lleva 75 días de mora al corte, y entra igual que un
arriendo de dos meses: DataBridge mide la mora en días, no en meses impagos (`bajo_umbral_mora`,
§6.4).

```json
{
  "id_externo": "SN-2026-118",
  "deudor": { "rut": "13579246-2", "tipo": "persona", "nombre": "Patricio Muñoz Salas",
              "correo": "patricio.munoz@correo.cl" },
  "moneda": "CLP",
  "concepto": "Tratamiento de ortodoncia",
  "referencias": { "presupuesto": "SN-2026-118", "tratamiento": "Ortodoncia" },
  "cargos": [
    { "concepto": "Ortodoncia, saldo del presupuesto", "monto": 890000, "fecha_vencimiento": "2026-07-05" }
  ]
}
```

### 6.4 Reglas de validación

Las que no caben en JSON Schema las aplica cada receptor al recibir:

| Código de error | Cuándo |
| --- | --- |
| `rut_invalido` | El RUT no cumple el formato o el dígito verificador no calza |
| `sin_canal_contacto` | El deudor no trae ni correo ni teléfono |
| `monto_invalido` | Monto ≤ 0, CLP con decimales, o UF con más de 2 decimales |
| `cargo_no_vencido` | Un cargo vence en o después de `fecha_corte`: todavía no es mora |
| `mora_fuera_de_mandato` | La mora supera el máximo de la agencia. **Para APOFYX son 120 días**: después devuelve el caso al acreedor (su §2.2). APOFYX lo aplica al recibir; DataBridge lo vuelve a revisar contra el mandato. Si la deuda ya estaba en gestión, la devolución es de verdad: APOFYX la saca de su cartera y le pasa el retiro a DataBridge, con el motivo `fuera_de_mandato`, para que deje de cobrarla |
| `id_duplicado_en_lote` | Dos deudas con el mismo `id_externo` en el mismo lote |
| `deuda_no_encontrada` | Se pide `retirar` una deuda que el receptor no tiene |
| `deuda_saldada` | Se pide retirar una deuda que ya se pagó, o actualizarla con algún cargo que ya se pagó. Con cargos posteriores a los pagados no es un error: el deudor se volvió a atrasar, y la deuda vuelve a cobranza |
| `campana_desconocida` | El `mandato` apunta a una campaña que esa agencia no registró para ese acreedor |
| `bajo_umbral_mora` | **Solo DataBridge.** Una deuda nueva —o una pagada que vuelve— cuyo cargo impago más antiguo lleva menos días vencido que su umbral (30, por omisión, `MIN_DIAS_MORA`): DataBridge cobra a deudores morosos. Se mide en días y no en meses impagos para que sirva en cualquier rubro, también para una deuda de un solo cargo. Una deuda que ya está en gestión puede volver con menos: el deudor pagó una parte directo |

**Todos los clientes, y el receptor detecta al moroso.** El acreedor no decide quién es moroso ni
qué retirar: cada mes entrega a todos sus clientes con contrato, deban o no. Lo demás lo detecta
cada receptor con sus reglas:

| El cliente | Qué hace el receptor |
| --- | --- |
| Está al día (`cargos: []`) y el receptor no lo tenía | Responde `al_dia` y **no guarda ningún dato suyo**: no hay nada que cobrarle |
| Está al día y su deuda estaba en gestión | Le pagó al acreedor por fuera: la cierra como un retiro `pago_directo` |
| Está al día y su deuda ya estaba pagada o retirada | `al_dia`, sin cambios |
| Debe | Las reglas de siempre: la mora de la agencia y el mínimo de meses de DataBridge |

Los interesados sin contrato no viajan: no tienen cargos ni pagos que revisar.

**La mora la calcula el receptor, no el emisor.** Son los días entre el vencimiento del cargo
impago más antiguo y `fecha_corte`. Mandarla calculada abriría la puerta a que no calce con los
cargos.

**Tramos**: `1-30`, `31-90` y `91-120`. APOFYX los usa para priorizar.

**Aceptación parcial.** Las deudas válidas entran y las inválidas se rechazan una por una, con su
motivo. Un RUT mal escrito no bloquea las otras 4.999.

**Actualizar y retirar.** Mandar de nuevo una deuda con el mismo `id_externo` la actualiza: por
ejemplo, si el arrendatario pagó una parte en la oficina y Patrimonio manda el saldo menor.
`"accion": "retirar"` la saca de la gestión; el motivo es obligatorio (`pago_directo`,
`acuerdo_directo`, `error`, `disputa_resuelta`, `fuera_de_mandato`, `otro`). El de
`fuera_de_mandato` lo pone la agencia cuando devuelve un caso. APOFYX reenvía actualizaciones y
retiros a DataBridge igual que las altas. **El acreedor manda sobre la deuda original; DataBridge
manda sobre lo que se pagó a través de él.**

**El mes siguiente.** El acreedor vuelve a mandar cada mes a todos sus clientes con contrato, y eso
no deshace lo que el deudor ya acordó:

- **Una deuda en convenio conserva su convenio.** Si el acreedor informa un mes nuevo, DataBridge lo
  agrega como una cuota aparte, fuera del convenio. Si informa menos de lo que queda del convenio
  —le pagaron una parte en la oficina—, se descuenta de las últimas cuotas, y el plan termina antes.
  Un pago que todavía no le llega al acreedor cuando emite su cartera aparece como deuda, y se
  corrige solo con la cartera siguiente.
- **Una deuda pagada vuelve con cargos nuevos.** Si el deudor se atrasa otra vez en el mismo
  contrato, la deuda se reabre, siempre que todos sus cargos sean posteriores a los que se pagaron.
  En DataBridge cuenta como una entrada nueva: exige de nuevo los 30 días de mora.

**La invitación.** Cuando una deuda entra —o vuelve— a cobranza en DataBridge, al deudor le llega
solo su código de acceso por correo, sin monto ni enlace. La agencia lo puede reenviar desde su
portal.

### 6.5 Respuesta

Para el lote de ejemplo, **suponiendo que el RUT de `CTR-2024-007` viniera mal escrito**
(en el archivo de ejemplo es válido):

```json
{
  "lote": "PAT-2026-09-18-01",
  "repetido": false,
  "recibidas": 4, "aceptadas": 3, "rechazadas": 1,
  "resultados": [
    { "id_externo": "CTR-2025-014", "resultado": "registrada", "mora_dias": 44, "tramo": "31-90" },
    { "id_externo": "CTR-2026-031", "resultado": "registrada", "mora_dias": 13, "tramo": "1-30" },
    { "id_externo": "CTR-2024-007", "resultado": "rechazada",
      "errores": [ { "campo": "deudor.rut", "codigo": "rut_invalido", "mensaje": "El dígito verificador no corresponde" } ] },
    { "id_externo": "CTR-2025-022", "resultado": "al_dia" }
  ],
  "campos_ignorados": []
}
```

`resultado` es uno de: `registrada`, `actualizada`, `sin_cambios`, `retirada`, `al_dia`,
`rechazada`. Solo `rechazada` cuenta en `rechazadas`.

### 6.6 Variante CSV

Para el emisor sin integración (modo archivo, §9): el mismo contrato en una planilla.
Plantilla: [`ejemplos/cartera-v1.plantilla.csv`](ejemplos/cartera-v1.plantilla.csv).

```
POST /api/v1/carteras   (multipart/form-data)
  archivo            el CSV
  lote_id_externo    PAT-2026-09-18-01
  fecha_corte        2026-09-18
  acreedor_rut       76418902-7
  agencia_rut        77305118-6          (solo en el tramo agencia → plataforma)
  campana_id_externo APX-PAT-2026-09     (ídem)
```

- **Una fila por cargo.** Las filas con el mismo `deuda_id` forman una deuda, y sus columnas de
  deuda tienen que ser idénticas.
- Separador **`;`**: es el que usa Excel en Chile, donde la coma es el separador decimal.
- UTF-8, con o sin BOM. Montos sin separador de miles; en UF se acepta `38,5` o `38.5`.
- `referencias` va en una sola columna como `clave=valor|clave=valor`.
- Una fila de retiro lleva solo `deuda_id`, `accion` y `motivo_retiro`.
- Un cliente al día va en una sola fila, con las columnas de cargo vacías: es `cargos: []`.

Desde el portal de empresas de DataBridge la misma planilla se carga arrastrándola
(`POST /api/debts/cartera`, con la sesión del personal en vez de la clave de API). No es otra
ingesta: el archivo se convierte a Cartera v1 y entra por el mismo camino.

Reemplaza al CSV antiguo de ms-debt (`email,nombre,acreedor,monto,...`), que no traía RUT, ni id
externo, ni forma de reenviar sin duplicar.

---

## 7. Contrato ② — Mandato y Campaña v1 (agencia → DataBridge)

Lo que APOFYX le dice a DataBridge **antes** de pasarle una cartera.

**Mandato**: la agencia cobra por cuenta de este acreedor.

```
POST /api/v1/mandatos
{ "acreedor_rut": "76418902-7", "razon_social": "Patrimonio Inmuebles SpA",
  "nombre_fantasia": "Patrimonio Inmuebles", "vigente_desde": "2026-09-01", "mora_maxima_dias": 120 }
```

**Un acreedor nuevo se registra con su mandato.** Si DataBridge no conoce ese RUT, lo registra con
`razon_social` y `nombre_fantasia`, los mismos nombres de `lote.acreedor`. `nombre_fantasia` es el
que ve el deudor. Así una empresa nueva de la agencia llega a DataBridge sin que nadie la cargue a
mano. Un acreedor que ya existe no se toca.

**La agencia responde por el mandato**: es su contrato con el acreedor (§5.2 del documento de
APOFYX). DataBridge registra quién lo declaró y cuándo, y solo acepta carteras de ese acreedor
enviadas por esa agencia mientras el mandato esté vigente.

`mora_maxima_dias` lo fija la agencia, no DataBridge. Los 120 días son la regla de APOFYX; otra
agencia podría usar 180 sin que DataBridge cambie.

**Campaña**: cómo se contacta a esa cartera.

```
POST /api/v1/campanas
{
  "id_externo": "APX-PAT-2026-09",
  "acreedor_rut": "76418902-7",
  "nombre": "Arriendos septiembre 2026",
  "inicio": "2026-09-19", "fin": "2026-11-03",
  "canales": ["whatsapp", "correo"],
  "intentos": 5,
  "cadencia_dias": [1, 4, 11, 25, 45]
}
```

`canales`, `intentos`, `inicio` y `fin` son los mismos campos de `crm_campaign`. La cadencia es la
de §8 de su documento. APOFYX conserva la estrategia y DataBridge la ejecuta, que es el reparto
de §13.6.

---

## 8. Contrato ③ — Eventos v1 (de vuelta por la cadena)

**DataBridge avisa a quien le mandó la cartera** (APOFYX, o el acreedor si trabaja directo).
**APOFYX le reporta a su cliente** (Patrimonio) con el mismo formato. Patrimonio nunca habla con
DataBridge.

### 8.1 Entrega

Cada receptor registra una URL y recibe un secreto de quien le envía. El envío es un `POST` con:

```
X-Evento:    pago.confirmado
X-Evento-Id: evt_7f3c2a90-4d1e-4b8a-9c55-2e61f0a1b7d4
X-Timestamp: 1789923791
X-Firma:     v1=<HMAC-SHA256(secreto, timestamp + "." + cuerpo), en hex>
```

```json
{
  "id": "evt_7f3c2a90-4d1e-4b8a-9c55-2e61f0a1b7d4",
  "tipo": "pago.confirmado",
  "version": "1",
  "ocurrido_en": "2026-09-20T14:03:11-03:00",
  "acreedor_rut": "76418902-7",
  "lote_id_externo": "PAT-2026-09-18-01",
  "datos": { }
}
```

**Cómo se registra un receptor.** Ante DataBridge, con la misma clave de API de la cartera:

```
POST /api/v1/suscripciones
{ "url": "https://apofyx.cl/api/v1/eventos", "eventos": ["pago.confirmado", "deuda.saldada"] }
```

`eventos` es opcional (sin él, todos). La respuesta trae el `secreto`. Registrar la misma URL otra
vez la reactiva y devuelve el mismo secreto, así que la llamada se puede repetir sin romper nada.
APOFYX atiende el mismo `POST /api/v1/suscripciones` para sus clientes, con la clave que les emite.

**El secreto se guarda cifrado en las dos puntas.** No puede ser una huella: quien envía firma con
él y quien recibe verifica con él. Los tres sistemas lo guardan con AES-256-GCM, con una llave que
no está en la base (`CIFRADO_LLAVE`), en el formato `enc:v1:` + base64(iv | etiqueta | cifrado).
Lo mismo vale para la clave de API que el receptor presenta. Cambiar la llave obliga a volver a
suscribirse.

Al reenviar, APOFYX genera un evento nuevo (con su propio `id` y firmado con el secreto de
Patrimonio) y cambia `lote_id_externo` por el lote original de Patrimonio. `deuda_id_externo` no
cambia, porque siempre fue el de Patrimonio.

| Regla | Detalle |
| --- | --- |
| **Firma** | El receptor recalcula el HMAC y descarta lo que no calce |
| **Antirrepetición** | El receptor descarta si el timestamp difiere más de 5 minutos de su reloj |
| **Al menos una vez** | Un evento puede llegar dos veces; el receptor deduplica por `id` |
| **Sin orden garantizado** | Se ordena por `ocurrido_en`, no por llegada |
| **Respuesta** | `2xx` en menos de 10 s. El receptor guarda y procesa después |
| **Reintentos** | 1 min, 5 min, 30 min, 2 h, 6 h y 24 h. Después queda como *entrega fallida*, reenviable a mano |

Es la misma técnica HMAC que ms-payments ya usa para verificar las pasarelas (`WebhookVerifier`),
ahora en sentido contrario.

### 8.2 Catálogo

| Evento | Cuándo | `datos` |
| --- | --- | --- |
| `lote.procesado` | Termina la ingesta de un lote | `periodo`, `fecha_corte`, `recibidas`, `aceptadas`, `rechazadas`, `tramos: [{tramo, deudas, promedio_clp}]` |
| `campana.avance` | Una vez al día por campaña | `campana_id_externo`, `fecha_corte`, `deudas`, `enviados`, `ingresos_portal`, `repactaciones`, `pagos`, `saldadas`, `disputas`, `retiradas`, `recuperado_clp`, `recuperado_uf` |
| `repactacion.aceptada` | El deudor acepta un plan | `deuda_id_externo`, `cuotas`, `monto_cuota`, `moneda`, `primera_cuota` |
| `pago.confirmado` | La pasarela confirma un pago | `deuda_id_externo`, `pago_id`, `monto`, `moneda`, `monto_clp`, `valor_uf`, `medio`, `pagado_en` |
| `deuda.saldada` | El saldo llega a cero | `deuda_id_externo`, `saldada_en` |
| `deuda.disputada` | El deudor dice que la deuda no es suya o no corresponde | `deuda_id_externo`, `motivo` |
| `deuda.reanudada` | Revisada la disputa, la deuda sí corresponde y se vuelve a cobrar | `deuda_id_externo`, `motivo`, `con_convenio` |
| `deuda.retirada` | Se procesó un retiro | `deuda_id_externo`, `motivo` |

**`campana.avance` no nace de un lote**, así que su sobre no lleva `lote_id_externo`: la campaña
se identifica en `datos.campana_id_externo`. Es también el único evento que no se reenvía al
acreedor: la campaña es de la agencia.

**Un campo ausente no es un cero.** `campana.avance` trae lo que DataBridge mide de verdad. Lo que
depende del proveedor de mensajería —`entregados`, `abiertos`, `respuestas`, `bajas`,
`reportes_fraude`— no viaja en cero: viaja ausente, porque un cero diría "ninguno" cuando lo
correcto es "no lo sé". `recuperado_clp` y `recuperado_uf` van separados: sumarlos no significaría
nada.

**Qué se emite hoy (03-10-2026).** Todo el catálogo.

**La disputa.** El deudor, desde el portal, dice que la deuda no corresponde y elige un motivo:

| `motivo` | Qué dice el deudor |
| --- | --- |
| `no_reconoce` | No reconoce la deuda |
| `ya_pagada` | Ya la pagó |
| `monto_incorrecto` | El monto no corresponde |
| `otro` | Otro motivo |

Desde ese momento la deuda queda **disputada**: no se puede pagar ni repactar, y no le llegan
recordatorios. El texto libre que escribe el deudor se queda en DataBridge: no viaja (I5). La
empresa que cobra la revisa y la resuelve de una de dos formas:

| Resolución | Evento | Qué pasa |
| --- | --- | --- |
| La deuda corresponde | `deuda.reanudada`, `motivo: "disputa_rechazada"` | Vuelve a cobranza, o a su convenio si tenía uno (`con_convenio: true`) |
| La deuda no corresponde | `deuda.retirada`, `motivo: "disputa_resuelta"` | Sale de la cobranza y se anulan las cuotas pendientes |

`deuda.reanudada` solo cambia una deuda que esté disputada: si llega tarde, después de un pago o un
retiro, no reabre nada.

**`pago.confirmado` dice con qué se pagó** en `medio`: `webpay`, `mercadopago` o `khipu`. Webpay
cobra de verdad contra Transbank (ambiente de integración por omisión) y Khipu cuando DataBridge
tiene la llave de una cuenta de cobro; Mercado Pago es simulada. Un pago de Webpay se confirma solo
cuando Transbank lo autoriza y el monto y la orden calzan; uno de Khipu, cuando Khipu dice que la
transferencia está conciliada, con el monto y la transacción del cobro.

**En UF, `pago.confirmado` trae el valor de la UF usado.** La UF cambia todos los días; el acreedor
tiene que poder reconstruir por qué un pago de `UF 38,5` fueron esos pesos.

### 8.3 Qué hace cada uno con los eventos

**Ningún evento lleva datos personales del deudor**: ni nombre, ni RUT, ni correo, ni teléfono.
Cada receptor ya tiene la deuda y cruza por `deuda_id_externo`.

**APOFYX** los usa para tres cosas:

| Uso | Con qué eventos |
| --- | --- |
| Poner al día el estado de cada deuda de su cartera | `repactacion.aceptada`, `pago.confirmado`, `deuda.saldada`, `deuda.disputada`, `deuda.reanudada`, `deuda.retirada` |
| Llenar `crm_campaignfunnelsnapshot`: envíos, ingresos al portal, pagos y recuperado | `campana.avance` |
| Dejar el rastro de cada lote confirmado por DataBridge | `lote.procesado` |
| Reportarle a Patrimonio | Todos, reenviados con el lote de Patrimonio |

`campana.avance` trae `pagos` y `recuperado_clp`. Es la columna que hoy **falta a propósito** en
APOFYX porque no puede medirla (§11.3 de su documento): con DataBridge sí puede, y deja de
enterarse de lo recuperado a fin de mes por una planilla.

**Patrimonio** marca como pagados los cargos del contrato que corresponde, y muestra en el contrato
en qué va una disputa: abierta (`deuda.disputada`), rechazada (`deuda.reanudada`) o aceptada
(`deuda.retirada` con `disputa_resuelta`). No cambia ningún cargo por una disputa: revisar el
contrato le toca a la corredora. No sabe ni necesita saber que detrás de APOFYX está DataBridge.

---

## 9. Modos de operación

| Sistema | Solo | Con archivo | Con API |
| --- | --- | --- | --- |
| **Patrimonio** | Cobra arriendos, registra pagos en oficina, lista morosos | Descarga su cartera y la sube al portal de su agencia | Se conecta a su agencia desde su panel, le envía la cartera por API y recibe sus eventos |
| **APOFYX** | Recibe, valida y prioriza carteras; campañas y reportes como hoy, con el pago fuera | Sube la cartera validada en CSV al portal de DataBridge | Reenvía carteras, registra mandatos y campañas, recibe y reenvía eventos |
| **DataBridge** | Opera con acreedores directos, sin agencia | Recibe CSV desde su portal | Ingesta por API, eventos por webhook |

---

## 10. Cómo se suma un cliente nuevo

Por ejemplo, un gimnasio que le encarga a APOFYX sus cuotas atrasadas:

1. **El gimnasio se registra** en el portal de empresas de APOFYX (`/empresas/registro/`) con su
   RUT y los datos de quien lo va a usar. El personal de APOFYX **aprueba su acceso** y le crea la
   campaña.
2. **En su portal emite su clave de API.** Su sistema la usa para comprobarla
   (`GET /api/v1/cuenta`), suscribirse a los avisos (`POST /api/v1/suscripciones`) y entregar su
   cartera (`POST /api/v1/carteras`). Sin sistema propio, sube la planilla de §6.6 en el mismo
   portal.
3. **APOFYX le pasa la cartera a DataBridge** con el mandato del gimnasio, que trae su RUT y sus
   nombres: DataBridge lo registra ahí la primera vez.
4. **DataBridge detecta a los morosos**, les envía su código de acceso y los pagos vuelven por la
   cadena hasta el sistema del gimnasio.

**No hay ningún paso que toque código, configuración ni la consola** de DataBridge ni de APOFYX.
Lo único que se escribe es el adaptador del gimnasio, en su propio sistema, y es opcional: la
planilla sirve igual. Si el gimnasio prefiriera no pasar por una agencia, usa el mismo adaptador
contra DataBridge directo.

---

## 11. Versionado

- La versión va en la ruta (`/api/v1/`) y en el cuerpo (`"version": "1.0"`).
- **Agregar un campo opcional no cambia la versión**: los receptores ignoran lo que no conocen
  (R7) y lo informan en `campos_ignorados`.
- **Quitar, renombrar o cambiar el significado de un campo es v2.** Los receptores atienden v1 y
  v2 en paralelo durante un semestre.
- El esquema publicado es **estricto** (`additionalProperties: false`) para que el emisor detecte
  errores de tipeo antes de enviar. Recibir es tolerante; validar antes de enviar, no.

---

## 12. Qué cambia en cada repositorio

**DataBridge (TB_web)**: una capa de integración nueva con organizaciones, claves de API,
mandatos, campañas, lotes con idempotencia, bandeja de salida de eventos y suscripciones. Se
diseña en `TBridgeDB.sql` y reemplaza la ingesta CSV actual.

**APOFYX**:
- Una app Django nueva, `cartera`, que funciona sola: lotes recibidos, deudores, deudas asignadas
  a campañas y su estado. **Sigue sin tablas de pagos ni transacciones**: eso es de DataBridge, y
  el estado de cada deuda se actualiza con los eventos.
- Una app `integracion`, aislada de `crm` y `cartera`: recibe carteras de clientes, se las pasa a
  DataBridge, registra mandatos y campañas, y recibe y reenvía eventos. Con la configuración vacía
  queda apagada.
- Columnas de pago en `crm_campaignfunnelsnapshot`, alimentadas por `campana.avance`.
- `link_clicks` pasa a significar *ingresos al portal*: con código de acceso ya no hay link.
- **Actualizar su documento**: §2.2 y la cabecera de `AphofyxDB.sql` dicen que no hay tabla de
  deudores, y D1 y §13.7 dicen que la integración no se implementa ahí.

**Patrimonio Inmuebles**:
- Un módulo `cobranza` que funciona solo: arrendatarios con RUT, contratos, cargos mensuales,
  pagos y morosos.
- Un adaptador `integracion` que arma la cartera v1 para APOFYX y marca los pagos que llegan por
  eventos.

---

## 13. Decisiones

### Resueltas

| # | Decisión | Resolución |
| --- | --- | --- |
| **I1** | ¿Base compartida o contratos? | **Contratos versionados.** Ninguna base se lee desde afuera (R1) |
| **I2** | ¿Por dónde pasa la cartera? | **Por la cadena: acreedor → APOFYX → DataBridge.** APOFYX guarda la cartera, como dice §13.6 de su documento; los pagos y transacciones quedan solo en DataBridge |
| **I3** | Identidad entre sistemas | **RUT + id externo** (R2). El id de la deuda es el del acreedor en toda la cadena |
| **I4** | ¿Todo o nada por lote? | **Aceptación parcial**, con el motivo de cada rechazo |
| **I5** | ¿Datos personales en eventos? | **No.** Los ids alcanzan: cada receptor ya tiene sus registros |
| **I6** | ¿Quién fija la mora máxima? | **La agencia**, en su mandato. DataBridge no conoce las reglas de APOFYX |
| **I7** | ¿Quién calcula la mora? | **El receptor**, desde los cargos y la fecha de corte |
| **I8** | Formato del esquema | **JSON Schema draft-07**, el que más validadores soportan en Java, Python y JavaScript |
| **I9** | Acceso del deudor | **Código de acceso por dos canales, con magic link de respaldo** (decidido el 18-09-2026). Es interno de DataBridge; el contrato solo exige al menos un canal (§6.3) |
| **I14** | ¿Un contrato por tramo o uno para toda la cadena? | **Uno para toda la cadena** (R8). Cada pieza se puede saltar o reemplazar |
| **I10** | Fuente del valor de la UF | **Banco Central de Chile**, serie `F073.UFF.PRE.Z.D` de su Base de Datos Estadísticos (decidido el 22-09-2026). Se publica con un mes de adelanto, así que no hay hora del día que fijar: ms-payments carga cada mañana desde una semana atrás hasta 40 días adelante. Un cobro usa la UF de ese día exacto o no se hace. Sin credenciales, operaciones la carga a mano |
| **I11** | ¿La integración de DataBridge es un microservicio propio o un módulo de ms-debt? | **Un paquete de ms-debt** (`integracion`). Todo lo que toca el contrato —lotes, deudas, mandatos, eventos— es de ms-debt, y un servicio aparte solo agregaría llamadas entre ambos |
| **I15** | ¿Quién resuelve una disputa? | **La empresa que cobra**, desde su panel en DataBridge (decidido el 03-10-2026). La disputa recorre la cadena hasta el acreedor, que ve en qué va pero no la resuelve: la deuda la cobra la agencia (§8.2) |
| **I16** | ¿Cómo se guardan los secretos que hay que leer de vuelta? | **Cifrados con AES-256-GCM** y una llave fuera de la base, en los tres sistemas (decidido el 03-10-2026). Las claves que solo se comparan siguen guardadas como huella (§8.1) |

### Abiertas

| # | Decisión | Estado |
| --- | --- | --- |
| **I12** | Retención del detalle | Cuánto guardan APOFYX y DataBridge una deuda saldada o retirada antes de anonimizarla |
| **I13** | ¿Integradores de terceros? | En v1, el emisor solo puede ser el acreedor o una agencia con mandato |

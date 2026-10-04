"""
El motor local del asistente: responde sin LLM, con reglas.

Lee las deudas tal como las entrega ms-debt (`saldo`, `moneda`, `estado`...).
Pesos y UF no se suman entre si: una deuda en UF sumada a una en
pesos daria un total que no significa nada.

El analisis de sentimiento es por lexico, no un modelo. Alcanza para lo que
importa en cobranza: notar cuando alguien esta frustrado, o cuando desconfia
de que el mensaje sea real, que es la razon principal por la que la gente no
paga lo que si quiere pagar (docs de APOFYX, seccion 10).
"""

from __future__ import annotations

import re
import unicodedata
from typing import Any

VIGENTES = {"open", "repacted"}

ESTADOS = {
    "open": "pendiente",
    "repacted": "en convenio de pago",
    "paid": "pagada",
    "withdrawn": "retirada por el acreedor",
    "disputed": "en revision",
}

# Raices, sin tildes: el texto se normaliza antes de buscar.
FRUSTRACION = [
    "harto", "chato", "cansad", "enojad", "molest", "rabia", "abuso", "injust", "reclam",
    "no puedo pagar", "no tengo plata", "no me alcanza", "cesante", "sin trabajo", "sin pega",
    "desesperad", "angusti", "me cobran de mas", "no es justo", "pesimo", "horrible",
]
DESCONFIANZA = [
    "estafa", "fraude", "phishing", "es real", "es verdad", "es confiable", "confiar", "sospech",
    "quien eres", "quienes son", "de donde sacaron", "como tienen mis datos", "como consiguieron",
    "me estan cobrando algo que no", "no reconozco", "no es mia", "no es mio",
]
POSITIVO = ["gracias", "genial", "perfecto", "excelente", "buenisimo", "bacan", "listo", "super"]


def _normalizar(texto: str) -> str:
    sin_tildes = unicodedata.normalize("NFKD", texto or "").encode("ascii", "ignore").decode("ascii")
    return re.sub(r"\s+", " ", sin_tildes.lower()).strip()


def sentimiento(mensaje: str) -> dict[str, Any]:
    """
    Frustracion y desconfianza pesan mas que lo positivo: "gracias, pero esto
    es un abuso" es un reclamo, no un agradecimiento.
    """
    texto = _normalizar(mensaje)
    conteo = {
        "desconfianza": sum(1 for p in DESCONFIANZA if p in texto),
        "frustracion": sum(1 for p in FRUSTRACION if p in texto),
        "positivo": sum(1 for p in POSITIVO if p in texto),
    }
    for etiqueta in ("desconfianza", "frustracion", "positivo"):
        if conteo[etiqueta]:
            return {"etiqueta": etiqueta, "intensidad": conteo[etiqueta]}
    return {"etiqueta": "neutral", "intensidad": 0}


def dinero(valor: Any, moneda: str = "CLP") -> str:
    try:
        n = float(valor)
    except (TypeError, ValueError):
        n = 0.0
    if moneda == "UF":
        entero, decimales = f"{n:,.2f}".split(".")
        return f"UF {entero.replace(',', '.')},{decimales}"
    return f"${n:,.0f}".replace(",", ".")


def totales(deudas: list[dict[str, Any]]) -> str:
    """El saldo vigente por moneda: "$1.040.000 y UF 115,50"."""
    por_moneda: dict[str, float] = {}
    for d in deudas:
        if d.get("estado") in VIGENTES:
            por_moneda[d.get("moneda") or "CLP"] = por_moneda.get(d.get("moneda") or "CLP", 0.0) + float(d.get("saldo") or 0)
    if not por_moneda:
        return dinero(0)
    return " y ".join(dinero(total, moneda) for moneda, total in por_moneda.items())


def _linea(d: dict[str, Any]) -> str:
    return (f"- {d.get('acreedor')} ({d.get('concepto')}): {dinero(d.get('saldo'), d.get('moneda'))}, "
            f"{ESTADOS.get(d.get('estado'), d.get('estado'))}")


def local_reply(mensaje: str, deudas: list[dict[str, Any]]) -> str:
    texto = _normalizar(mensaje)
    animo = sentimiento(mensaje)["etiqueta"]
    vigentes = [d for d in deudas if d.get("estado") in VIGENTES]
    pagadas = [d for d in deudas if d.get("estado") == "paid"]
    acreedores = sorted({d.get("acreedor") for d in deudas if d.get("acreedor")})

    if animo == "desconfianza":
        quien = f" Tu deuda es con {', '.join(acreedores)}." if acreedores else ""
        return (
            "Es bueno que lo preguntes. Technical Bridge nunca te pide contraseñas ni datos "
            "bancarios, y nunca te manda un enlace para entrar: entras escribiendo la dirección "
            f"tú mismo, con tu RUT y tu código.{quien} Si algo no te cuadra, puedes confirmarlo "
            "directamente con esa empresa antes de pagar. Si la deuda no te corresponde, díselo "
            "a ellos: no tienes que pagar algo que no reconoces."
        )

    prefacio = ""
    if animo == "frustracion":
        prefacio = "Entiendo que es una situación difícil. "
        if re.search(r"no puedo pagar|no tengo plata|no me alcanza|cesante|sin trabajo|sin pega", texto):
            return (
                prefacio + "Puedes dividir lo que debes en 3 a 24 cuotas, sin interés: el total es "
                "lo mismo que debes hoy. En cada deuda está el botón Ver planes para simularlo sin "
                "comprometerte. Tu saldo vigente es " + totales(deudas) + "."
            )

    if re.search(r"\b(hola|buenas|hey|saludos|alo)\b", texto):
        return ("Hola, soy el asistente de Technical Bridge. Te puedo decir cuánto debes y a quién, "
                "cómo pagar en cuotas y cómo pagar. ¿Qué necesitas?")
    if "certificad" in texto:
        if pagadas:
            return ("Tienes " + str(len(pagadas)) + " deuda(s) pagada(s). En Mis pagos, junto a cada "
                    "una, está el botón Certificado para descargar el PDF.")
        return "El certificado se emite cuando una deuda queda pagada por completo."
    if re.search(r"repact|cuot|plazo|convenio|plan", texto):
        return (prefacio + "Eliges entre 3 y 24 meses y ves la cuota antes de aceptar. No hay "
                "interés: el total es lo que debes hoy, y la última cuota absorbe el redondeo. En "
                "UF, cada cuota se paga al valor de la UF del día en que pagas.")
    if re.search(r"pagar|pago|pasarela|khipu|webpay|mercado", texto):
        return (prefacio + "En cada deuda está el botón Pagar: eliges la próxima cuota o todo el "
                "saldo, y pagas con Webpay, Mercado Pago o Khipu. Tu saldo se actualiza solo "
                "unos segundos después, y la empresa queda avisada.")
    if re.search(r"saldo|deuda|cuanto|debo|a quien", texto):
        if not deudas:
            return "No encuentro pagos pendientes a tu nombre."
        lineas = [prefacio + f"Tienes {len(vigentes)} deuda(s) vigente(s), por {totales(deudas)} en total."]
        lineas += [_linea(d) for d in vigentes[:5]]
        if pagadas:
            lineas.append(f"Además, {len(pagadas)} ya está(n) pagada(s).")
        return "\n".join(lineas)
    if animo == "positivo":
        return "Con gusto. Si necesitas algo más sobre tus pagos, pregúntame."
    if vigentes:
        return (prefacio + f"Tu saldo vigente es {totales(deudas)} en {len(vigentes)} deuda(s). "
                "Te puedo contar el detalle, cómo pagar en cuotas o cómo pagar.")
    if deudas:
        return "No tienes saldo pendiente. Si pagaste todo, puedes descargar el certificado de cada deuda."
    return "Pregúntame cuánto debes, cómo pagar en cuotas o cómo pagar."

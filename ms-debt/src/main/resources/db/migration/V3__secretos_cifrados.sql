-- =============================================================================
--  V3 — El secreto de las suscripciones, cifrado
-- =============================================================================
--  subscriptions.secret es con lo que DataBridge firma los avisos: no puede ser
--  una huella, porque hay que firmar con el. Desde aqui se guarda cifrado con
--  AES-256-GCM y una llave fuera de la base (CIFRADO_LLAVE):
--
--      'enc:v1:' + base64(iv | etiqueta | cifrado)
--
--  Cifrado es mas largo que en claro. Los que ya estaban en claro los cifra el
--  servicio al arrancar (CifrarSecretosGuardados): aqui no hay llave.
-- =============================================================================

ALTER TABLE subscriptions MODIFY secret VARCHAR(255) NOT NULL;

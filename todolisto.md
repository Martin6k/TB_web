# Inicio Rápido de Servicios y Prueba de Webpay

Comandos directos para levantar la infraestructura, todos los microservicios en modo desarrollo (`dev`), el frontend y emitir el código de acceso para pagar una deuda con Webpay.

---

## 1. Preparación Inicial (Solo la primera vez)

```powershell
# 1.1 Iniciar infraestructura base (MySQL 3308, RabbitMQ, Mailpit)
docker compose up -d

# 1.2 Compilar e instalar módulos
.\mvnw.cmd -q clean install -DskipTests
```

---

## 2. Levantar los Servicios (Una terminal por servicio)

### Terminal 1 — ms-auth (Puerto 8081)
```powershell
.\mvnw.cmd -pl ms-auth spring-boot:run "-Dspring-boot.run.profiles=dev"
```

### Terminal 2 — ms-debt (Puerto 8083)
```powershell
.\mvnw.cmd -pl ms-debt spring-boot:run "-Dspring-boot.run.profiles=dev"
```

### Terminal 3 — ms-payments (Puerto 8084 - Webpay TEST Activo)
```powershell
.\mvnw.cmd -pl ms-payments spring-boot:run "-Dspring-boot.run.profiles=dev"
```

### Terminal 4 — ms-ai (Puerto 8085 - Opcional)
```powershell
cd ms-ai
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app.main:app --port 8085 --reload
```

### Terminal 5 — Gateway (Puerto 8082)
```powershell
.\mvnw.cmd -pl gateway spring-boot:run "-Dspring-boot.run.profiles=dev"
```

### Terminal 6 — Frontend (Puerto 5173)
```powershell
cd frontend
npm install
npm run dev
```

---

## 3. Emitir Código de Acceso para Probar el Pago

Ejecuta este comando en PowerShell para generar el código de acceso de **Rodrigo Perez** (posee deuda de mas de 1 Millón de CLP):

```powershell
$cuerpo = @{ rut = "14583206-3"; canales = @("correo"); correo = "rodrigo.perez@correo.cl"; acreedor = "Patrimonio Inmuebles"; paraQue = "CTR-2025-019" } | ConvertTo-Json -Compress
>> $cuerpo | docker compose exec -T ms-auth curl -s -X POST http://127.0.0.1:8081/internal/codigos -H "X-Internal-Key: tbridge-internal-dev" -H "Content-Type: application/json" --data-binary "@-"
```

> *(La respuesta mostrará el JSON con `"codigo": "XXXXXX"`. También queda visible en Mailpit en http://localhost:8025).*

---

## 4. Pasos para Probar Webpay en el Navegador

1. Abre el portal en **http://localhost:5173**
2. Haz clic en **Tengo un código de acceso**.
3. Ingresa:
   - **RUT:** `16.482.337-7`
   - **Código:** Pega el código de 6 caracteres obtenido en el paso 3.
4. Entra a tu deuda y pulsa **Pagar**.
5. Selecciona la pasarela **Webpay Plus (Transbank TEST)** y presiona el botón **Pagar**.
6. Se abrirá la pasarela oficial de **Transbank Webpay Plus (Ambiente de Integración)**:
   - Puedes usar cualquier tarjeta de prueba o seleccionar las opciones de prueba que ofrece Transbank.
7. Al autorizar el pago, Transbank te redirigirá de regreso y verás el comprobante con la deuda y cuotas conciliadas en tiempo real.

---

## 5. Apagar Servicios

```powershell
# Detener infraestructura de Docker
docker compose down
```

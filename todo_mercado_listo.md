# Guía de Pruebas: Mercado Pago (DataBridge)

Este archivo contiene las credenciales de prueba, tarjetas de prueba y comandos necesarios para probar el flujo de pago con **Mercado Pago** en el entorno local.

---

## 1. Credenciales de la Aplicación (TEST)

* **País de operación:** Chile
* **N.° de la aplicación:** `8079701943220387`
* **User ID:** `3737969390`
* **Public Key:** `APP_USR-0e4b2202-9b14-4b4b-9696-28802be3048a`
* **Access Token:** `APP_USR-8079701943220387-100401-c35823cb3e6ca0dd24d21af811de65b4-3737969390`

---

## 2. Cuenta y Tarjeta de Prueba para Pagar

### Usuario Comprador de Prueba
* **Usuario:** `TESTUSER6607781982686461757`
* **Contraseña:** `3dzLoS43zQ`
* **Código de verificación (2FA):** `969390`

### Tarjeta de Prueba (Aprobada)
* **Tarjeta:** `Mastercard`
* **Número de tarjeta:** `5416 7526 0258 2580`
* **Fecha de vencimiento:** `11/30`
* **Código de seguridad (CVV):** `123`
* **Nombre del titular:** `APRO` (o cualquier nombre)
* **RUT / DNI:** `16482337-7` (o cualquier RUT válido)

---

## 3. Comandos para Iniciar los Servicios

Abre una terminal PowerShell para cada servicio:

### Terminal 1: Infraestructura (Docker)
```powershell
docker compose up -d
```
*(Inicia MySQL en puerto 3308, RabbitMQ en 5672 y Mailpit en 8025)*

### Terminal 2: ms-auth (Puerto 8081)
```powershell
.\mvnw.cmd -pl ms-auth spring-boot:run "-Dspring-boot.run.profiles=dev"
```

### Terminal 3: Gateway (Puerto 8082)
```powershell
.\mvnw.cmd -pl gateway spring-boot:run "-Dspring-boot.run.profiles=dev"
```

### Terminal 4: ms-debt (Puerto 8083)
```powershell
.\mvnw.cmd -pl ms-debt spring-boot:run "-Dspring-boot.run.profiles=dev"
```

### Terminal 5: ms-payments (Puerto 8084)
```powershell
.\mvnw.cmd -pl ms-payments spring-boot:run "-Dspring-boot.run.profiles=dev"
```

### Terminal 6: Frontend (Puerto 5173 o Docker 8080)
```powershell
cd frontend
npm run dev
```

---

## 4. Pasos para Probar el Pago con Mercado Pago

### Paso 1: Emitir Código de Acceso para el Deudor de Prueba
Ejecuta este comando en PowerShell para obtener un código de acceso para Felipe Rojas (deuda pendiente disponible):

```powershell
$cuerpo = @{ rut = "14583206-3"; canales = @("correo"); correo = "rodrigo.perez@correo.cl"; acreedor = "Patrimonio Inmuebles"; paraQue = "CTR-2025-019" } | ConvertTo-Json -Compress
 $cuerpo | docker compose exec -T ms-auth curl -s -X POST http://127.0.0.1:8081/internal/codigos -H "X-Internal-Key: tbridge-internal-dev" -H "Content-Type: application/json" --data-binary "@-"
```

O revisa el código de 6 dígitos en el buzón local de Mailpit:
👉 **[http://localhost:8025](http://localhost:8025)**

### Paso 2: Iniciar Sesión en el Portal
1. Ingresa a **[http://localhost:5173](http://localhost:5173)** (o `http://localhost:8080` si usas Docker).
2. Haz clic en **"Tengo un código de acceso"**.
3. Ingresa el RUT `16482337-7` y el código de 6 dígitos.

### Paso 3: Realizar el Pago
1. En la lista de deudas, haz clic en **"Pagar"** en la deuda activa.
2. Selecciona la cuota o saldo a pagar.
3. Elige la pasarela **Mercado Pago**.
4. Haz clic en **"Pagar con Mercado Pago"**.
5. Se abrirá la pasarela de Mercado Pago Checkout Pro.
6. Selecciona pagar con **Nueva tarjeta de crédito / débito**.
7. Ingresa los datos de la tarjeta de prueba:
   - **Número:** `5416 7526 0258 2580`
   - **Vencimiento:** `11/30`
   - **CVV:** `123`
8. Al completar el pago, Mercado Pago te devolverá automáticamente al portal de DataBridge mostrando el comprobante con visto bueno verde y las cuotas conciliadas en tiempo real.

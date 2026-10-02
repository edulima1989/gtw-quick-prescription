# Quick Prescription - Docker Compose

Este repositorio contiene el API Gateway y el `docker-compose.yml` de Quick Prescription. La configuracion actual levanta PostgreSQL, autenticacion, prescripciones y el gateway. El microfrontend `mf-shell` esta comentado y no se inicia con Compose.

## Servicios

| Servicio Compose | Contenedor | Puerto en el host | Funcion |
| --- | --- | --- | --- |
| `postgres` | `prescription-postgres` | `5432` | PostgreSQL 16; bases `usuarios_db` y `prescriptions_db` |
| `msa-authentication` | `msa-authentication` | `8082` | Autenticacion y usuarios; usa `usuarios_db` |
| `msa-quick-prescription` | `msa-quick-prescription` | `8081` | Catalogos y recetas; usa `prescriptions_db` |
| `gateway` | `gtw-quick-prescription` | `8080` | Enruta `/api/v1/**` y valida el JWT |

Los servicios comparten la red `prescription-net`. Dentro de Compose, los microservicios acceden a PostgreSQL mediante `postgres:5432`, y el gateway accede a ellos por sus nombres de servicio. Desde el host se utilizan los puertos publicados en la tabla.

## Requisitos

- Docker Engine o Docker Desktop con Docker Compose v2.
- Puertos `5432`, `8080`, `8081` y `8082` disponibles en el host.
- Los tres repositorios deben estar en la misma carpeta padre:

```text
<carpeta-padre>/
├── gtw-quick-prescription/                 # docker-compose.yml
├── msa-quick-prescription/
└── msa-quick-prescription-authentication/
```

Las imagenes Java se construyen con Gradle y JDK 21 dentro de Docker; no hace falta instalar Java o Gradle en el host.

## Variables de entorno

Desde `gtw-quick-prescription`, se puede copiar `.env.example` a `.env` y cambiar los valores antes de ejecutar Compose. Sin archivo `.env` se usan los valores predeterminados de `docker-compose.yml`:

| Variable | Valor predeterminado | Uso |
| --- | --- | --- |
| `POSTGRES_USER` | `postgres` | Usuario de ambas bases de datos |
| `POSTGRES_PASSWORD` | `mysecretpassword` | Contrasena de PostgreSQL y de los microservicios |
| `JWT_SECRET` | Valor de ejemplo en Compose | Enviado al contenedor; actualmente no leido por autenticacion |
| `JWT_EXPIRATION` | `86400000` | Enviado al contenedor; actualmente no leido por autenticacion |

Compose pasa `JWT_SECRET` y `JWT_EXPIRATION` al contenedor, pero la configuracion actual de autenticacion tiene valores fijos para ambos: modificar estas variables todavia no cambia los tokens. Los `*_ASSET_PREFIX` incluidos en `.env.example` tampoco se usan en el Compose actual porque los microfrontends no estan activos.

El gateway usa `SHELL_ORIGIN=http://localhost:3000` para CORS. Como Compose no inicia el shell, cambie ese valor en `docker-compose.yml` si el frontend se sirve desde otro origen. No publique el archivo `.env` con credenciales reales.

## Arranque

Ejecute los siguientes comandos desde `gtw-quick-prescription`:

```bash
docker compose up --build -d
docker compose ps
docker compose logs -f
```

La primera inicializacion de PostgreSQL ejecuta `docker/postgres/init-databases.sql` y crea `usuarios_db` y `prescriptions_db`. Los microservicios esperan el healthcheck de PostgreSQL; el gateway depende del arranque de ambos microservicios. El esquema de autenticacion se gestiona con Flyway y el de prescripciones con Hibernate (`ddl-auto: update`).

## Verificacion

```bash
# Comprobar las bases creadas
docker compose exec postgres psql -U postgres -d postgres -c '\l'

# Inspeccionar los registros de cada componente
docker compose logs -f msa-authentication msa-quick-prescription gateway
```

| Recurso | URL en el host |
| --- | --- |
| Gateway | `http://localhost:8080` |
| API de autenticacion por el gateway | `http://localhost:8080/api/v1/auth/login` |
| API de prescripciones por el gateway | `http://localhost:8080/api/v1/cie10` |
| Microservicio de autenticacion (directo) | `http://localhost:8082/users` |
| Microservicio de prescripciones (directo) | `http://localhost:8081/prescriptions` |

Todas las rutas del gateway pasan por su filtro de autenticacion, excepto `POST /api/v1/auth/register`, `POST /api/v1/auth/login` y las rutas de documentacion Swagger. Para probar catalogos sin token, consulte el microservicio directamente en `http://localhost:8081/prescriptions/api/v1/cie10`.

## Endpoints disponibles

Todos los endpoints se consumen a traves del gateway con la ruta base `http://localhost:8080/api/v1`. El contrato completo esta en `docs/openapi.yaml`.

| Metodo | Ruta en el gateway | Autenticacion | Destino | Descripcion |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/auth/register` | Publica | Autenticacion (`/users/api/auth/register`) | Registra un consumidor de la API |
| `POST` | `/api/v1/auth/login` | Publica | Autenticacion (`/users/api/auth/login`) | Valida credenciales y emite un JWT |
| `GET` | `/api/v1/cie10` | Bearer JWT | Prescripciones | Lista paginada de diagnosticos CIE-10 |
| `GET` | `/api/v1/cie10/{codigo}` | Bearer JWT | Prescripciones | Obtiene un diagnostico por codigo |
| `GET` | `/api/v1/cie10/{codigo}/vademecum` | Bearer JWT | Prescripciones | Vademecum relacionado con un diagnostico |
| `GET` | `/api/v1/vademecum` | Bearer JWT | Prescripciones | Lista paginada del vademecum |
| `GET` | `/api/v1/vademecum/{id}` | Bearer JWT | Prescripciones | Obtiene un registro del vademecum por `id` |
| `GET` | `/api/v1/vademecum/{id}/cie10` | Bearer JWT | Prescripciones | Diagnosticos CIE-10 relacionados con un registro del vademecum |
| `POST` | `/api/v1/recetas` | Bearer JWT | Prescripciones | Genera una receta medica en PDF |

Las rutas de autenticacion se reescriben a `/users/api/auth/{segmento}` y las de prescripciones reciben el prefijo `/prescriptions` antes de enviarse al microservicio. Los catalogos (`cie10`, `vademecum` y su relacion) son de solo lectura.

### Autenticacion

Las rutas protegidas requieren el encabezado `Authorization: Bearer <accessToken>`. El gateway valida el token llamando a `POST /users/api/auth/validate-session` del servicio de autenticacion antes de enrutar la solicitud. Si el encabezado falta, tiene otro formato o el token es invalido o esta expirado, responde `401`; si el servicio de autenticacion no esta disponible, responde `503`.

### Auth

**`POST /api/v1/auth/register`**: crea una cuenta de consumidor.

| Campo | Tipo | Reglas |
| --- | --- | --- |
| `userName` | string | Obligatorio, de 2 a 150 caracteres |
| `userMail` | string (email) | Obligatorio, maximo 254 caracteres |
| `userPassword` | string | Obligatorio, de 8 a 72 caracteres |

Respuestas: `201` con `id`, `userName` y `userMail`; `400` datos invalidos; `409` correo ya registrado; `500` error interno.

**`POST /api/v1/auth/login`**: devuelve un token JWT.

| Campo | Tipo | Reglas |
| --- | --- | --- |
| `userMail` | string (email) | Obligatorio, maximo 254 caracteres |
| `userPassword` | string | Obligatorio |

Respuestas: `200` con `accessToken`, `tokenType` (`Bearer`) y `expiresIn` (segundos); `400` datos invalidos; `401` credenciales incorrectas; `500` error interno.

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"userMail":"juan@example.com","userPassword":"password123"}'
```

### Parametros de los listados

| Parametro | Aplica a | Reglas |
| --- | --- | --- |
| `page` | Todos los listados | Entero >= 1; predeterminado `1` |
| `size` | Todos los listados | Entero de 1 a 100; predeterminado `20` |
| `q` | `GET /cie10`, `GET /vademecum` | Texto libre de 2 a 100 caracteres; busca en codigo y descripcion (CIE-10) o en nombre y composicion (vademecum) |
| `categoria` | `GET /cie10` | Coincidencia exacta sin distinguir mayusculas, de 2 a 100 caracteres |
| `casaComercial` | `GET /vademecum` | Coincidencia exacta sin distinguir mayusculas, de 2 a 100 caracteres |
| `sort` | Listados de CIE-10 | `codigo,asc` (predeterminado), `codigo,desc`, `descripcion,asc`, `descripcion,desc` |
| `sort` | Listados del vademecum | `nombre,asc` (predeterminado), `nombre,desc`, `casaComercial,asc`, `casaComercial,desc` |

Los listados responden con `content`, `page`, `size`, `totalElements` y `totalPages`.

### CIE-10

- **`GET /api/v1/cie10`**: lista diagnosticos; admite `q`, `categoria`, `page`, `size` y `sort`.
- **`GET /api/v1/cie10/{codigo}`**: devuelve `id`, `codigo`, `descripcion` y `categoria`. El codigo sigue el patron `^[A-Z][0-9]{2}(\.[0-9A-Z]{1,4})?$` (por ejemplo `J00` o `J02.9`).
- **`GET /api/v1/cie10/{codigo}/vademecum`**: lista el vademecum relacionado; admite `page`, `size` y `sort`.

Respuestas: `200`; `400` parametros invalidos; `401` sin token valido; `403` sin permiso; `404` codigo inexistente (solo rutas con `{codigo}`); `500` error interno.

```bash
curl 'http://localhost:8080/api/v1/cie10?q=resfriado&page=1&size=20' \
  -H 'Authorization: Bearer <accessToken>'
```

### Vademecum

- **`GET /api/v1/vademecum`**: lista registros; admite `q`, `casaComercial`, `page`, `size` y `sort`.
- **`GET /api/v1/vademecum/{id}`**: devuelve `id`, `nombre`, `composicion`, `funcion`, `presentacion`, `dosificacion`, `casaComercial` y `contraindicaciones`. El `id` es un entero >= 1.
- **`GET /api/v1/vademecum/{id}/cie10`**: lista los diagnosticos relacionados; admite `page`, `size` y `sort`.

Respuestas: `200`; `400` parametros invalidos; `401` sin token valido; `403` sin permiso; `404` registro inexistente (solo rutas con `{id}`); `500` error interno.

### Recetas

**`POST /api/v1/recetas`**: genera el PDF de una receta. No crea ni almacena ningun recurso; los datos del paciente solo se usan para generar el documento.

| Campo | Tipo | Reglas |
| --- | --- | --- |
| `paciente.nombre` | string | Obligatorio, de 2 a 150 caracteres |
| `paciente.edad` | integer | Obligatorio, de 0 a 130 |
| `profesional.nombre` | string | Obligatorio, de 2 a 150 caracteres |
| `fecha` | string (`YYYY-MM-DD`) | Obligatorio |
| `codigosCie10` | array de string | Obligatorio, de 1 a 10 codigos CIE-10 |
| `medicamentos` | array | Obligatorio, de 1 a 10 elementos |
| `medicamentos[].vademecumId` | integer | Obligatorio, >= 1; `id` obtenido en `GET /vademecum` |
| `medicamentos[].dosis` | string | Obligatorio, de 1 a 100 caracteres |
| `medicamentos[].frecuencia` | string | Obligatorio, de 1 a 100 caracteres |
| `medicamentos[].duracion` | string | Obligatorio, de 1 a 100 caracteres |
| `medicamentos[].indicaciones` | string | Opcional, maximo 500 caracteres |

Respuestas: `200` con `application/pdf`, `Content-Disposition: attachment; filename="receta.pdf"` y `Cache-Control: no-store`; `400` datos invalidos; `401` sin token valido; `403` sin permiso; `422` codigo CIE-10 o medicamento inexistente; `500` error interno.

```bash
curl -X POST http://localhost:8080/api/v1/recetas \
  -H 'Authorization: Bearer <accessToken>' \
  -H 'Content-Type: application/json' \
  -o receta.pdf \
  -d '{
    "paciente": {"nombre": "Maria Fernanda Torres", "edad": 34},
    "profesional": {"nombre": "Dr. Carlos Andrade"},
    "fecha": "2026-09-28",
    "codigosCie10": ["J00", "J02.9"],
    "medicamentos": [{
      "vademecumId": 101,
      "dosis": "1 tableta de 500 mg",
      "frecuencia": "Cada 8 horas",
      "duracion": "5 dias",
      "indicaciones": "Tomar despues de las comidas."
    }]
  }'
```

### Errores

Los microservicios responden los errores con `application/problem+json` (RFC 9457): `type`, `title`, `status`, `detail`, `instance` y, en errores de validacion, `errores` (lista de `campo` y `mensaje`).

### Documentacion Swagger

Estas rutas son publicas y el gateway las envia sin cambios a cada microservicio:

| Servicio | Swagger UI | OpenAPI |
| --- | --- | --- |
| Autenticacion | `http://localhost:8080/users/swagger-ui.html` | `http://localhost:8080/users/v3/api-docs` |
| Prescripciones | `http://localhost:8080/prescriptions/swagger-ui.html` | `http://localhost:8080/prescriptions/api-docs` |

## Operacion y datos

```bash
docker compose stop                                 # Detener, conservar contenedores y datos
docker compose start                                # Reanudar
docker compose up -d --build msa-quick-prescription # Reconstruir solo prescripciones
docker compose down                                 # Eliminar contenedores, conservar el volumen
docker compose down -v                              # Eliminar tambien los datos de PostgreSQL
```

Si el volumen `postgres-data` ya existia antes de agregar `prescriptions_db`, el script de inicializacion no volvera a ejecutarse. Cree la base sin borrar los datos existentes:

```bash
docker compose exec postgres psql -U postgres -d postgres -c 'CREATE DATABASE prescriptions_db;'
```

Si cambia `POSTGRES_USER` en `.env`, sustituya `postgres` en los comandos `psql` por ese usuario. Un error de puerto ocupado se resuelve liberando el puerto del host o cambiando su mapeo en `docker-compose.yml`. Si el frontend muestra errores CORS, revise `SHELL_ORIGIN` en el servicio `gateway`.

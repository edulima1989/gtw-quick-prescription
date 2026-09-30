# Quick Prescription - Docker Compose

Este repositorio contiene el API Gateway y el `docker-compose.yml` de Quick Prescription. La configuracion actual levanta PostgreSQL, autenticacion, prescripciones y el gateway. El microfrontend `mf-shell` esta comentado y no se inicia con Compose.

## Servicios

| Servicio Compose | Contenedor | Puerto en el host | Funcion |
| --- | --- | --- | --- |
| `postgres` | `prescription-postgres` | `5432` | PostgreSQL 16; bases `usuarios_db` y `prescriptions_db` |
| `msa-authentication` | `msa-authentication` | `8082` | Autenticacion y usuarios; usa `usuarios_db` |
| `msa-quick-prescription` | `msa-quick-prescription` | `8081` | Catalogos y recetas; usa `prescriptions_db` |
| `gateway` | `gtw-quick-prescription` | `8080` | Enruta `/users/**` y `/prescriptions/**` |

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
| API de autenticacion por el gateway | `http://localhost:8080/users/api/auth/login` |
| API de prescripciones por el gateway | `http://localhost:8080/prescriptions/api/v1/cie10` |
| Microservicio de autenticacion (directo) | `http://localhost:8082/users` |
| Microservicio de prescripciones (directo) | `http://localhost:8081/prescriptions` |

Las rutas `/prescriptions/**` pasan por el filtro de autenticacion del gateway (excepto las rutas de documentacion). Para probar catalogos sin token, consulte el microservicio directamente en `http://localhost:8081/prescriptions/api/v1/cie10`.

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

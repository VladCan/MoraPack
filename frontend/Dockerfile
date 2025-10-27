# --- Etapa 1: Build (Compilación) ---
# Usamos la imagen 'latest' para asegurar compatibilidad con el lockfile
FROM oven/bun:latest AS builder

# Establecemos el directorio de trabajo dentro del contenedor
WORKDIR /app

# Copiamos los archivos de manifiesto y bloqueo
COPY package.json bun.lock* ./

# Instalamos las dependencias
# Usamos --frozen-lockfile para asegurar que se usa el bun.lock
RUN bun install --frozen-lockfile

# Copiamos el resto del código fuente del proyecto
COPY . .

# el archivo .env.production por defecto.
RUN mv .env.prod .env.production

# Corremos el script de build (usualmente "build" en package.json para Vite)
# Esto generará la carpeta /app/dist
RUN bun run build

# --- Etapa 2: Serve (Servidor con Caddy) ---
# Usamos una imagen de Caddy súper ligera con Alpine Linux
FROM caddy:2-alpine AS final

# Copiamos los archivos estáticos compilados desde la etapa 'builder'
# al directorio raíz por defecto de Caddy (/srv)
COPY --from=builder /app/dist /srv

# ¡Importante! Para una SPA (Single Page Application),
# necesitamos que Caddy redirija todas las rutas a index.html.
# Copiamos nuestro archivo Caddyfile personalizado.
COPY Caddyfile /etc/caddy/Caddyfile

# Exponemos el puerto 80 (el que Caddy usa por defecto para HTTP)
EXPOSE 80

# El comando para iniciar Caddy ya está en la imagen base.
# Por defecto, ejecuta: caddy run --config /etc/caddy/Caddyfile

#!/bin/bash

# --- CONFIGURACIÓN ---
# ¡Asegúrate de que estos valores sean correctos!

# 1. Configuración Remota (Tu servidor)
REMOTE_USER_HOST="1inf54-981-3c@1inf54-981-3c.inf.pucp.edu.pe"
REMOTE_PROJECT_DIR="~/MoraPack"          # Carpeta del proyecto en el servidor

# 2. Configuración de Imágenes (Tu registro de Docker)
GHCR_USERNAME="wolgank" # Tu usuario de GitHub (dueño del registro)
BACKEND_IMAGE_NAME="morapack-backend"
FRONTEND_IMAGE_NAME="morapack-frontend"

# 3. Configuración del Proyecto (Paths en tu máquina local)
# ¡RUTAS CORREGIDAS!

# --- Backend ---
# Ruta al *archivo* Dockerfile del backend
BACKEND_DOCKERFILE_FILE="./backend/src/main/docker/Dockerfile.native-micro"
# Ruta a la *carpeta de contexto* del backend (la que contiene 'mvnw' y 'target/')
BACKEND_CONTEXT_PATH="./backend"

# --- Frontend ---
# !! Revisa que estas rutas sean correctas para tu frontend !!
# Ruta al *archivo* Dockerfile del frontend
FRONTEND_DOCKERFILE_FILE="./frontend/Dockerfile"
# Ruta a la *carpeta de contexto* del frontend
FRONTEND_CONTEXT_PATH="./frontend"


# 4. Configuración de Docker Compose
COMPOSE_FILE="docker-compose.prod.yml"

# --- FIN DE LA CONFIGURACIÓN ---

# Salir inmediatamente si un comando falla
set -e

# Función para logs bonitos
log() {
  echo ""
  echo "-------------------------------------------------"
  echo " $1"
  echo "-------------------------------------------------"
}

# --- FASE 1: CONSTRUIR Y SUBIR IMÁGENES (Se ejecuta en tu máquina) ---

log "FASE 1: Construyendo y subiendo imágenes a GHCR"

log "Iniciando sesión en GitHub Container Registry..."
echo $CR_PAT | docker login ghcr.io -u $GHCR_USERNAME --password-stdin
# docker login ghcr.io -u $GHCR_USERNAME # Descomenta si prefieres login interactivo

# --- ¡CAMBIO AQUÍ! ---
# Preguntamos al usuario si desea reconstruir el backend
echo ""
read -p "🔧 ¿Deseas recompilar el backend nativo (target)? (Es lento) [y/N]: " REBUILD_BACKEND
echo "" # Añade un salto de línea después de la entrada del usuario

case "$REBUILD_BACKEND" in
    [yY] | [yY][eE][sS]) # Acepta 'y', 'Y', 'yes', 'YES', etc.
        log "Compilando ejecutable nativo del Backend..."
        # Usamos (cd ... && ...) para ejecutar el comando DENTRO de la carpeta del backend
        (cd $BACKEND_CONTEXT_PATH && ./mvnw clean package -Pnative -Dquarkus.native.container-build=true -DskipTests)
        log "Backend recompilado."
        ;;
    *)
        log "Omitiendo recompilación del backend. Se usará el 'target' existente."
        ;;
esac
# --- FIN DEL CAMBIO ---

log "Construyendo imagen del Backend..."
docker build -t "ghcr.io/$GHCR_USERNAME/$BACKEND_IMAGE_NAME:latest" -f $BACKEND_DOCKERFILE_FILE $BACKEND_CONTEXT_PATH

log "Construyendo imagen del Frontend..."
# (Asegúrate de que tu frontend no necesite un paso de 'build' similar, como 'npm run build')
docker build -t "ghcr.io/$GHCR_USERNAME/$FRONTEND_IMAGE_NAME:latest" -f $FRONTEND_DOCKERFILE_FILE $FRONTEND_CONTEXT_PATH

log "Subiendo imagen del Backend a GHCR..."
docker push "ghcr.io/$GHCR_USERNAME/$BACKEND_IMAGE_NAME:latest"

log "Subiendo imagen del Frontend a GHCR..."
docker push "ghcr.io/$GHCR_USERNAME/$FRONTEND_IMAGE_NAME:latest"

log "Imágenes subidas con éxito."


# --- FASE 2: DESPLEGAR EN EL SERVIDOR (Se ejecuta remotamente) ---

log "FASE 2: Desplegando en el servidor $REMOTE_USER_HOST"


ssh $REMOTE_USER_HOST << EOF
    # Salir si cualquier comando falla EN EL SERVIDOR
    set -e

    echo "--- (REMOTE) Conectado al servidor. Navegando al directorio $REMOTE_PROJECT_DIR ---"
    # IMPORTANTE: Necesitamos expandir la variable local $REMOTE_PROJECT_DIR aquí,
    # por eso 'EOF' NO está entre comillas.
    cd $REMOTE_PROJECT_DIR

    echo "--- (REMOTE) Actualizando el repositorio desde 'main' ---"
    # Validar fast-forward
    git pull --ff-only origin main

    echo "--- (REMOTE) Repositorio actualizado con éxito. ---"

    echo "--- (REMOTE) Deteniendo los servicios actuales... ---"
    # De nuevo, $COMPOSE_FILE se expande desde la variable local
    docker compose -f $COMPOSE_FILE down

    echo "--- (REMOTE) Iniciando nuevos servicios... ---"
    # docker-compose.prod.yml ya tiene 'pull_policy: always', así que
    # 'up' descargará las nuevas imágenes 'latest' automáticamente.
    docker compose -f $COMPOSE_FILE up -d

    echo "--- (REMOTE) Limpiando imágenes de Docker antiguas... ---"
    docker image prune -f

    echo "--- (REMOTE) ¡Despliegue completado con éxito! ---"
EOF

log "¡PROCESO DE DEPLOY TERMINADO!"

#!/bin/bash

# --- CONFIGURACIÓN ---
REMOTE_USER_HOST="1inf54-981-3c@1inf54-981-3c.inf.pucp.edu.pe"
REMOTE_PROJECT_DIR="~/MoraPack"

# Nombres locales para las imágenes (Deben coincidir con docker-compose.server.yml)
BACKEND_IMAGE="morapack-backend:local-build"
FRONTEND_IMAGE="morapack-frontend:local-build"
COMPOSE_FILE="docker-compose.server.yml"

# --- INICIO ---
echo "🚀 Iniciando proceso de construcción y despliegue TOTALMENTE en el servidor..."

ssh $REMOTE_USER_HOST "bash -s" << EOF
    set -e # Detener si algo falla

    echo "📂 Navegando a $REMOTE_PROJECT_DIR ..."
    cd $REMOTE_PROJECT_DIR

    echo "⬇️  Actualizando código (Git pull)..."
    git pull --ff-only origin main

    # --- 1. COMPILACIÓN NATIVA (BACKEND) ---
    echo "☕ Compilando Quarkus Nativo..."
    cd backend
    chmod +x mvnw
    # Nota: Asegúrate de haber configurado el SWAP si tu server tiene poca RAM
    ./mvnw clean package -Pnative -Dquarkus.native.container-build=true -DskipTests
    cd ..

    # --- 2. CONSTRUCCIÓN DOCKER (BACKEND) ---
    echo "🐳 Empaquetando imagen Docker del Backend ($BACKEND_IMAGE)..."
    docker build -t "$BACKEND_IMAGE" -f ./backend/src/main/docker/Dockerfile.native-micro ./backend

    # --- 3. CONSTRUCCIÓN DOCKER (FRONTEND) ---
    echo "⚛️  Empaquetando imagen Docker del Frontend ($FRONTEND_IMAGE)..."
    docker build -t "$FRONTEND_IMAGE" -f ./frontend/Dockerfile ./frontend

    # --- 4. DESPLIEGUE ---
    echo "🔄 Reiniciando servicios con $COMPOSE_FILE..."
    
    # Bajamos lo viejo
    docker compose -f $COMPOSE_FILE down

    # Subimos lo nuevo (Usará las imágenes :local-build que acabamos de crear)
    docker compose -f $COMPOSE_FILE up -d

    echo "🧹 Limpiando imágenes basura..."
    docker image prune -f

    echo "✅ ¡Despliegue local en servidor completado!"
EOF

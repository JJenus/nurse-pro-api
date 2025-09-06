#!/bin/bash

# Script to restart the nursepro-api container
# Usage: ./restart-nursepro-api.sh [--rebuild]

# Variables
CONTAINER_NAME="nursepro-api"
IMAGE_NAME="nursepro-api:1.0"
PORT="8080"
WORKDIR="/home/jjenus/workbench/apps/xhar_nurse/nurse-pro-api"

# Function to check if a command succeeded
check_error() {
    if [ $? -ne 0 ]; then
        echo "Error: $1"
        exit 1
    fi
}

# Check for --rebuild flag
REBUILD=false
if [ "$1" == "--rebuild" ]; then
    REBUILD=true
fi

# Check if running as non-root and has Docker permissions
if [ "$(id -u)" -ne 0 ]; then
    docker ps >/dev/null 2>&1
    check_error "Docker permission denied. Run 'sudo usermod -aG docker $USER' and 'newgrp docker'."
fi

# Change to working directory
cd "$WORKDIR" || check_error "Failed to change to $WORKDIR"

# Rebuild image if --rebuild flag is provided
if [ "$REBUILD" = true ]; then
    echo "Rebuilding $IMAGE_NAME..."
    docker build -t "$IMAGE_NAME" .
    check_error "Failed to rebuild image"
    echo "Image size:"
    docker images "$IMAGE_NAME"
fi

# Check if port 8080 is in use
if sudo netstat -tuln | grep -q ":$PORT "; then
    echo "Warning: Port $PORT is in use. Stopping containers using it..."
    for container_id in $(docker ps -q --filter "publish=$PORT"); do
        docker stop "$container_id"
        check_error "Failed to stop container $container_id"
        docker rm "$container_id"
        check_error "Failed to remove container $container_id"
    done
fi

# Stop and remove existing container if it exists
if docker ps -a --filter "name=^/$CONTAINER_NAME$" --format '{{.ID}}' | grep -q .; then
    echo "Stopping and removing existing $CONTAINER_NAME container..."
    docker stop "$CONTAINER_NAME"
    check_error "Failed to stop $CONTAINER_NAME"
    docker rm "$CONTAINER_NAME"
    check_error "Failed to remove $CONTAINER_NAME"
fi

# Start the container
echo "Starting $CONTAINER_NAME..."
docker run -d \
    --name "$CONTAINER_NAME" \
    -p "$PORT:$PORT" \
    "$IMAGE_NAME"
check_error "Failed to start $CONTAINER_NAME"

# Wait briefly and check container status
sleep 5
if docker ps --filter "name=^/$CONTAINER_NAME$" --format '{{.Status}}' | grep -q "Up"; then
    echo "$CONTAINER_NAME is running."
    echo "Access at http://localhost:$PORT or http://localhost:$PORT/swagger-ui.html"
    echo "Check logs with: docker logs $CONTAINER_NAME"
else
    echo "Error: $CONTAINER_NAME failed to start."
    echo "Checking logs..."
    docker logs "$CONTAINER_NAME"
    exit 1
fi
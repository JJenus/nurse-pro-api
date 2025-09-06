#!/bin/bash

# Script to export nursepro-api:1.0 image and copy it to a specified project path
# Usage: ./copy-nursepro-image.sh [--rebuild] /path/to/project
#        ./copy-nursepro-image.sh [--rebuild] user@host:/path/to/project

# Variables
IMAGE_NAME="nursepro-api:1.0"
EXPORT_FILE="/home/jjenus/nursepro-api-1.0.tar"
WORKDIR="/home/jjenus/workbench/apps/xhar_nurse/nurse-pro-api"

# Function to check if a command succeeded
check_error() {
    if [ $? -ne 0 ]; then
        echo "Error: $1"
        exit 1
    fi
}

# Check for arguments
if [ $# -lt 1 ]; then
    echo "Usage: $0 [--rebuild] /path/to/project | user@host:/path/to/project"
    exit 1
fi

# Parse arguments
REBUILD=false
TARGET_PATH="./"
for arg in "$@"; do
    if [ "$arg" == "--rebuild" ]; then
        REBUILD=true
    else
        TARGET_PATH="$arg"
    fi
done

# Validate target path
if [ -z "$TARGET_PATH" ]; then
    echo "Error: Target project path not specified"
    exit 1
fi

# Check for Docker permissions
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

# Check if image exists
if ! docker images -q "$IMAGE_NAME" | grep -q .; then
    echo "Error: Image $IMAGE_NAME not found"
    exit 1
fi

# Export the image
echo "Exporting $IMAGE_NAME to $EXPORT_FILE..."
docker save -o "$EXPORT_FILE" "$IMAGE_NAME"
check_error "Failed to export image"
ls -lh "$EXPORT_FILE"

# Copy to target path
echo "Copying $EXPORT_FILE to $TARGET_PATH..."
if [[ "$TARGET_PATH" == *:* ]]; then
    # Remote path (user@host:/path)
    scp "$EXPORT_FILE" "$TARGET_PATH"
    check_error "Failed to copy $EXPORT_FILE to $TARGET_PATH"
else
    # Local path
    cp "$EXPORT_FILE" "$TARGET_PATH"
    check_error "Failed to copy $EXPORT_FILE to $TARGET_PATH"
fi

echo "Successfully copied $EXPORT_FILE to $TARGET_PATH"
echo "To import on the target machine, run:"
echo "  docker load -i $TARGET_PATH/nursepro-api-1.0.tar"
echo "To run the container, run:"
echo "  docker run -d --name nursepro-api -p 8080:8080 $IMAGE_NAME"
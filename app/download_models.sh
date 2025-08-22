#!/usr/bin/env bash
set -euo pipefail

# Directory to download into (models folder)
MODEL_DIR="src/main/assets/models"
# Default list of model files to download; override by passing filenames as arguments
MODEL_FILES=(
 "gemma3-1b-it-int4.task"
  # Add other model filenames here, e.g.:
  # "gemma3-1b-it-int8.task"
  # "gemma3-1b-it-int16.task"
)
# Base URL on Hugging Face
BASE_URL="https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main"

# Require HF token for private repo access
if [ -z "${HF_TOKEN:-}" ]; then
  echo "Error: HF_TOKEN environment variable is not set. Please export your Hugging Face token as HF_TOKEN." >&2
  exit 1
fi

# If filenames provided as arguments, use those instead
if [ "$#" -gt 0 ]; then
  MODEL_FILES=("$@")
fi

# Create model directory if it doesn't exist
mkdir -p "$MODEL_DIR"
cd "$MODEL_DIR"

# Loop through each model file and download if missing
for MODEL_FILE in "${MODEL_FILES[@]}"; do
  if [ -f "$MODEL_FILE" ]; then
    echo "✅ $MODEL_FILE already exists in $MODEL_DIR. Skipping."
  else
    echo "⬇️ Downloading $MODEL_FILE to $MODEL_DIR..."
    if command -v curl >/dev/null 2>&1; then
      curl -L -H "Authorization: Bearer $HF_TOKEN" \
           "$BASE_URL/$MODEL_FILE" \
           -o "$MODEL_FILE"
    elif command -v wget >/dev/null 2>&1; then
      wget --header="Authorization: Bearer $HF_TOKEN" \
           -O "$MODEL_FILE" \
           "$BASE_URL/$MODEL_FILE"
    else
      echo "Error: Neither curl nor wget is installed." >&2
      exit 1
    fi

    # Verify download
    if [ $? -eq 0 ] && [ -f "$MODEL_FILE" ]; then
      echo "✅ Download complete: $MODEL_DIR/$MODEL_FILE"
    else
      echo "❌ Failed to download: $MODEL_FILE" >&2
      exit 1
    fi
  fi
done

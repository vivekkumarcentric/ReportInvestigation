#!/bin/bash

# ============================================================================
# Ollama Setup Script for Bug Investigation Agent
# ============================================================================
# This script automates the setup of Ollama and required models
# ============================================================================

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Ollama Setup for Bug Investigation   ${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Check if Ollama is installed
echo -e "${YELLOW}Checking if Ollama is installed...${NC}"
if ! command -v ollama &> /dev/null; then
    echo -e "${RED}❌ Ollama is not installed${NC}"
    echo ""
    echo -e "${BLUE}Installation Instructions:${NC}"
    echo "1. Visit https://ollama.ai"
    echo "2. Download and install Ollama for your OS"
    echo "3. Run this script again"
    echo ""
    exit 1
fi

echo -e "${GREEN}✓ Ollama is installed${NC}"
echo ""

# Check if Ollama service is running
echo -e "${YELLOW}Checking if Ollama service is running...${NC}"
if curl -s http://localhost:11434/api/tags > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Ollama service is running${NC}"
else
    echo -e "${YELLOW}⚠ Ollama service is not running. Starting Ollama...${NC}"

    # Try to start Ollama in background
    if [[ "$OSTYPE" == "darwin"* ]]; then
        # macOS
        open -a Ollama
        echo "Opening Ollama app..."
    elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
        # Linux
        if command -v systemctl &> /dev/null; then
            sudo systemctl start ollama
            echo "Started Ollama service..."
        else
            ollama serve &
            echo "Started Ollama in background..."
        fi
    fi

    # Wait for Ollama to start
    echo -e "${YELLOW}Waiting for Ollama to start (up to 30 seconds)...${NC}"
    for i in {1..30}; do
        if curl -s http://localhost:11434/api/tags > /dev/null 2>&1; then
            echo -e "${GREEN}✓ Ollama service is now running${NC}"
            break
        fi
        echo -n "."
        sleep 1
    done

    if ! curl -s http://localhost:11434/api/tags > /dev/null 2>&1; then
        echo -e "${RED}❌ Ollama service failed to start${NC}"
        echo ""
        echo -e "${BLUE}Manual Start Instructions:${NC}"
        if [[ "$OSTYPE" == "darwin"* ]]; then
            echo "1. Open Ollama.app from Applications"
        else
            echo "1. Run: ollama serve"
        fi
        echo "2. Wait for the service to be ready"
        echo "3. Run this script again"
        echo ""
        exit 1
    fi
fi

echo ""

# Function to check and pull a model
pull_model() {
    local model=$1
    local display_name=$2

    echo -e "${YELLOW}Checking if model '$model' is loaded...${NC}"

    if curl -s http://localhost:11434/api/tags | grep -q "\"$model\""; then
        echo -e "${GREEN}✓ Model '$model' is already loaded${NC}"
    else
        echo -e "${YELLOW}Pulling model '$model' (this may take a few minutes)...${NC}"
        ollama pull "$model"
        if [ $? -eq 0 ]; then
            echo -e "${GREEN}✓ Successfully loaded model '$model'${NC}"
        else
            echo -e "${RED}❌ Failed to load model '$model'${NC}"
            return 1
        fi
    fi
    echo ""
}

# Pull required models
echo -e "${BLUE}Loading required models...${NC}"
echo ""

pull_model "qwen2.5:7b" "Qwen 2.5 (Analysis Model)"
pull_model "llava:7b" "Llava (Vision Model)"

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  ✓ Ollama Setup Complete!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${BLUE}Next Steps:${NC}"
echo "1. Make sure Ollama is running: ollama serve"
echo "2. Start the Bug Investigation Agent:"
echo "   cd /Users/VivekKumar/Downloads/final-bia"
echo "   mvn spring-boot:run"
echo "3. Open your browser and navigate to: http://localhost:8080"
echo "4. Upload a test report and click 'Analyse' on failed cases"
echo ""
echo -e "${BLUE}Troubleshooting:${NC}"
echo "• If models fail to load, ensure you have sufficient disk space (≥10GB)"
echo "• Check your internet connection"
echo "• Run 'ollama list' to see all available/loaded models"
echo ""


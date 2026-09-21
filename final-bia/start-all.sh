#!/bin/bash

# ============================================================================
# Complete Setup and Run Script for Bug Investigation Agent
# ============================================================================
# This script sets up Ollama and runs the Bug Investigation Agent
# ============================================================================

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Bug Investigation Agent - Setup${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Check if Java is installed
echo -e "${YELLOW}Checking Java installation...${NC}"
if ! command -v java &> /dev/null; then
    echo -e "${RED}❌ Java is not installed${NC}"
    echo "Please install Java 17 or higher"
    exit 1
fi
JAVA_VERSION=$(java -version 2>&1 | grep -oP 'version "\K[0-9]+')
echo -e "${GREEN}✓ Java $JAVA_VERSION is installed${NC}"
echo ""

# Setup Ollama
echo -e "${YELLOW}Setting up Ollama...${NC}"
if [ -f "$SCRIPT_DIR/setup-ollama.sh" ]; then
    bash "$SCRIPT_DIR/setup-ollama.sh"
else
    echo -e "${RED}setup-ollama.sh not found${NC}"
    exit 1
fi

echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Starting Bug Investigation Agent${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Build and run the application
cd "$SCRIPT_DIR"
echo -e "${YELLOW}Building the application...${NC}"
mvn clean install -DskipTests -q

echo -e "${GREEN}✓ Build successful${NC}"
echo ""

echo -e "${YELLOW}Starting Spring Boot application...${NC}"
echo -e "${BLUE}Application will be available at: http://localhost:8080${NC}"
echo ""

mvn spring-boot:run


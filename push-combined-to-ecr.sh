#!/bin/bash

# Script to build and push bmps-combined Docker image to AWS ECR
# Usage: ./push-combined-to-ecr.sh
#
# Automatically tags with:
# - Timestamp for easy rollback (format: YYYYMMDD-HHMMSS, e.g., 20251012-143045)
# - 'latest' for convenience

set -e  # Exit on any error

# Configuration
AWS_REGION="us-east-2"
AWS_ACCOUNT_ID="891162616451"
ECR_REPOSITORY="bpms/core"
ECR_REGISTRY="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
IMAGE_NAME="bmps-combined"

# Generate timestamp tag (format: YYYYMMDD-HHMMSS)
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
TIMESTAMP_TAG="${TIMESTAMP}"

echo "=========================================="
echo "Building and pushing ${IMAGE_NAME}"
echo "Timestamp: ${TIMESTAMP_TAG}"
echo "=========================================="

# Step 1: Authenticate Docker to ECR
echo ""
echo "Step 1: Authenticating with ECR..."
aws ecr get-login-password --region ${AWS_REGION} | \
  docker login --username AWS --password-stdin ${ECR_REGISTRY}

# Step 2: Build the Docker image for AMD64 (ECS Fargate)
echo ""
echo "Step 2: Building Docker image for linux/amd64..."
docker buildx build --platform linux/amd64 -f Dockerfile.combined -t ${IMAGE_NAME}:${TIMESTAMP_TAG} --load .

# Step 3: Tag the image for ECR
echo ""
echo "Step 3: Tagging image for ECR..."
docker tag ${IMAGE_NAME}:${TIMESTAMP_TAG} ${ECR_REGISTRY}/${ECR_REPOSITORY}:${TIMESTAMP_TAG}
docker tag ${IMAGE_NAME}:${TIMESTAMP_TAG} ${ECR_REGISTRY}/${ECR_REPOSITORY}:latest

# Step 4: Push to ECR
echo ""
echo "Step 4: Pushing to ECR..."
echo "Pushing timestamp tag: ${TIMESTAMP_TAG}..."
docker push ${ECR_REGISTRY}/${ECR_REPOSITORY}:${TIMESTAMP_TAG}

echo "Pushing 'latest' tag..."
docker push ${ECR_REGISTRY}/${ECR_REPOSITORY}:latest

echo ""
echo "=========================================="
echo "✅ Successfully pushed to ECR!"
echo "=========================================="
echo ""
echo "Tags pushed:"
echo "  - ${ECR_REGISTRY}/${ECR_REPOSITORY}:${TIMESTAMP_TAG}"
echo "  - ${ECR_REGISTRY}/${ECR_REPOSITORY}:latest"
echo ""
echo "For ECS task definition (recommended):"
echo "  ${ECR_REGISTRY}/${ECR_REPOSITORY}:${TIMESTAMP_TAG}"
echo ""
echo "Note: This combined image runs both:"
echo "  - Python API on port 8001 (internal)"
echo "  - Scala Core on port 8081 (external)"
echo "Only expose port 8081 in your ECS task definition."

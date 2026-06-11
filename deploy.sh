#!/usr/bin/env bash
set -euo pipefail

# Deployment script for RDAS on Kubernetes

IMAGE_NAME="loopdfs/rdas"
TAG="1.2.0"
NAMESPACE="default"

echo "================================================================="
echo "🚀 Deploying Reference Data Aggregation Service (RDAS) to Kubernetes"
echo "================================================================="

# Detect cluster environment
if command -v minikube >/dev/null 2>&1 && minikube status >/dev/null 2>&1; then
    echo "📦 [Minikube detected] Pointing docker-env to Minikube..."
    eval $(minikube docker-env)
fi

echo "🔨 Building Docker image: ${IMAGE_NAME}:${TAG}..."
docker build -t "${IMAGE_NAME}:${TAG}" .

echo "⚙️ Creating ConfigMap..."
kubectl apply -f k8s/configmap.yaml -n "$NAMESPACE"

echo "🛡️ Deploying StatefulSet, Service, HPA, and PDB..."
kubectl apply -f k8s/statefulset.yaml -n "$NAMESPACE"

echo "⏳ Waiting for rollout to finish..."
kubectl rollout status statefulset/rdas -n "$NAMESPACE" --timeout=120s

echo "🚀 RDAS successfully deployed to namespace: ${NAMESPACE}"
echo "-----------------------------------------------------------------"
echo "Status of resources:"
kubectl get all -l app=rdas -n "$NAMESPACE"
echo "================================================================="

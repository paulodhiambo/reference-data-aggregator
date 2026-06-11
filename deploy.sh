#!/usr/bin/env bash
set -euo pipefail

# Deployment script for Reference Data Aggregator on Kubernetes

IMAGE_NAME="loopdfs/reference-data-aggregator"
TAG="1.2.0"
NAMESPACE="default"

echo "================================================================="
echo "🚀 Deploying Reference Data Aggregator to Kubernetes"
echo "================================================================="

# Detect cluster environment
if command -v minikube >/dev/null 2>&1 && minikube status >/dev/null 2>&1; then
    echo "📦 [Minikube detected] Pointing docker-env to Minikube..."
    eval $(minikube docker-env)
fi

echo "🔨 Building Docker image: ${IMAGE_NAME}:${TAG}..."
docker build -t "${IMAGE_NAME}:${TAG}" .

echo "⚙️ Creating Secrets and ConfigMap..."
kubectl apply -f k8s/secret.yaml -n "$NAMESPACE"
kubectl apply -f k8s/configmap.yaml -n "$NAMESPACE"

echo "🛡️ Deploying Service, HPA, PDB, and StatefulSet..."
kubectl apply -f k8s/service.yaml -n "$NAMESPACE"
kubectl apply -f k8s/hpa.yaml -n "$NAMESPACE"
kubectl apply -f k8s/pdb.yaml -n "$NAMESPACE"
kubectl apply -f k8s/statefulset.yaml -n "$NAMESPACE"

echo "⏳ Waiting for rollout to finish..."
kubectl rollout status statefulset/reference-data-aggregator -n "$NAMESPACE" --timeout=120s

echo "🚀 Reference Data Aggregator successfully deployed to namespace: ${NAMESPACE}"
echo "-----------------------------------------------------------------"
echo "Status of resources:"
kubectl get all -l app=reference-data-aggregator -n "$NAMESPACE"
echo "================================================================="

#!/usr/bin/env bash
# Brings up a local cluster running this system, ready for the scaling experiment.
#
# Run from the repository root:
#     ./mvnw -DskipTests package && docker compose build
#     bash k8s/bootstrap.sh
#
# The images are built locally and loaded into the cluster rather than pulled,
# because there is no registry to pull them from. That is also why every
# deployment sets imagePullPolicy: Never.
set -euo pipefail

CLUSTER="${CLUSTER:-provisioning}"
NAMESPACE=provisioning
METRICS_SERVER_VERSION="${METRICS_SERVER_VERSION:-v0.7.2}"

# Only the locally built images. postgres:16 is deliberately absent: it is a
# public multi-platform image, and `kind load` imports with --all-platforms,
# which fails when Docker's containerd snapshotter holds only the layers for
# this machine's architecture ("content digest ... not found"). The cluster
# pulls it from the registry instead, which is what a registry is for.
IMAGES=(
  provisioning-saga-inventory-service:latest
  provisioning-saga-provisioning-service:latest
  provisioning-saga-order-service:latest
  provisioning-saga-gateway:latest
)

echo "==> cluster"
if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  kind create cluster --name "$CLUSTER" --wait 300s
else
  echo "cluster $CLUSTER already exists"
fi
kind export kubeconfig --name "$CLUSTER"

echo "==> metrics-server"
# The HPA scales on CPU utilisation, which it computes from the metrics API.
# Without metrics-server the HPA reports <unknown> for current utilisation and
# never scales, which reads as "autoscaling does not help" rather than
# "autoscaling cannot see". --kubelet-insecure-tls is needed because kind's
# kubelet serving certificates are self-signed.
kubectl apply -f "https://github.com/kubernetes-sigs/metrics-server/releases/download/${METRICS_SERVER_VERSION}/components.yaml"
kubectl -n kube-system patch deployment metrics-server --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
kubectl -n kube-system rollout status deployment/metrics-server --timeout=300s

echo "==> loading images"
for image in "${IMAGES[@]}"; do
  echo "    $image"
  kind load docker-image --name "$CLUSTER" "$image"
done

echo "==> applying manifests"
kubectl apply -k k8s/
for deployment in postgres inventory-service provisioning-service order-service gateway; do
  kubectl -n "$NAMESPACE" rollout status "deployment/$deployment" --timeout=300s
done

echo "==> waiting for the HPA to report a real utilisation figure"
# <unknown> here means metrics are not flowing yet, and an experiment started
# before they are would measure an HPA that cannot see rather than one that
# cannot keep up.
for _ in $(seq 1 60); do
  targets=$(kubectl -n "$NAMESPACE" get hpa provisioning-service -o jsonpath='{.status.currentMetrics[0].resource.current.averageUtilization}' 2>/dev/null || true)
  if [ -n "$targets" ]; then
    echo "    HPA sees ${targets}% CPU"
    break
  fi
  sleep 5
done

echo
kubectl -n "$NAMESPACE" get deploy,hpa
echo
echo "ready. Now run:"
echo "    python -m bench.run_scaling_experiment --mode fixed --replicas 1 --runs 3"
echo "    python -m bench.run_scaling_experiment --mode hpa --runs 3"

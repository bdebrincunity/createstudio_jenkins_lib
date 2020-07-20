/*
    Helm install
 */
def call (def namespace, def release, def env) {
    echo "Installing ${release} in ${namespace}"

    KUBE_CNF = "k8s/configs/${env}/kubeconfig-labs-createstudio-${env}_environment"

    script {
        release = "${release}-${namespace}"
        sh "helm repo add helm ${HELM_REPO}; helm repo update"
        sh """
            helm upgrade --install --namespace ${namespace} ${release} \
                --kubeconfig ${KUBE_CNF}
                --set imagePullSecrets=${IMG_PULL_SECRET} \
                --set image.repository=${DOCKER_REG}/${IMAGE_NAME},image.tag=${DOCKER_TAG} helm/acme
        """
        sh "sleep 5"
    }
}

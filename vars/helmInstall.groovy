/*
    Helm install
 */
def call (def namespace, def release, def env) {
    echo "Installing ${release} in ${namespace}"

    KUBE_CNF = "k8s/configs/${env}/kubeconfig-labs-createstudio-${env}_environment"

    script {
        sh "helm repo add helm ${HELM_REPO}; helm repo update"
        sh """
            helm upgrade --install ${release} --set service_name=${release} ./helm --namespace ${namespace} \
                --kubeconfig ${KUBE_CNF}
        """
        sh "sleep 5"
    }
}

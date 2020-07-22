/*
    Helm delete (if exists)
 */
def call (def namespace, def release, def env) {
    echo "Deleting ${release} in ${namespace} if deployed"

    KUBE_CNF = "k8s/configs/${env}/kubeconfig-labs-createstudio-${env}_environment"

    script {
        sh "[ -z \"\$(helm ls --short ${release} 2>/dev/null)\" ] || helm delete --purge ${release} --kubeconfig ${KUBE_CNF}"
    }
}

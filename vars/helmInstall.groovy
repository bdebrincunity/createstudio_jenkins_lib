/*
    Helm install
 */
def helmInstall (namespace, release) {
    echo "Installing ${release} in ${namespace}"

    script {
        release = "${release}-${namespace}"
        sh "helm repo add helm ${HELM_REPO}; helm repo update"
        sh """
            helm upgrade --install --namespace ${namespace} ${release} \
                --set imagePullSecrets=${IMG_PULL_SECRET} \
                --set image.repository=${DOCKER_REG}/${IMAGE_NAME},image.tag=${DOCKER_TAG} helm/acme
        """
        sh "sleep 5"
    }
}

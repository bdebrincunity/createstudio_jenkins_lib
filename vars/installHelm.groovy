/*
    Helm install
 */
def call () {
    echo "Installing ${release} in ${namespace}"

    script {
        sh """
            apk add curl
            curl -fsSL -o get_helm.sh https://raw.githubusercontent.com/helm/helm/master/scripts/get-helm-3
            chmod 700 get_helm.sh
            ./get_helm.sh
        """
        sh "sleep 5"
    }
}

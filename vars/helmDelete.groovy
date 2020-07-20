/*
    Helm delete (if exists)
 */
def helmDelete (namespace, release) {
    echo "Deleting ${release} in ${namespace} if deployed"

    script {
        release = "${release}-${namespace}"
        sh "[ -z \"\$(helm ls --short ${release} 2>/dev/null)\" ] || helm delete --purge ${release}"
    }
}

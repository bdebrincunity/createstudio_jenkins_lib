import com.unity.DockerUtils

def call(Map args = [:]) {
    Map argsDefault = [
            chartDir   : "./helm",
            dockerImage: "kiwigrid/gcloud-kubectl-helm",
            command    : "helm upgrade --install",
            extraParams: "",
            name       : 'Apply: Helm Chart',
    ]

    Map mergedArgs = argsDefault + args
    String script = ""
    context = this

    KUBE_CNF = "k8s/configs/${mergedArgs.environment}/kubeconfig-labs-createstudio-${mergedArgs.environment}_environment"

    withEnv(["home=${WORKSPACE}"]) {
        script = """
            CHART_VERSION=`helm show chart ${mergedArgs.chartDir} | grep version | awk '{print \$2}'`
            ${mergedArgs.command} ${mergedArgs.extraParams} ${mergedArgs.release} --namespace ${mergedArgs.namespace} -f ${mergedArgs.chartValuesFile} ${mergedArgs.releaseName} ${mergedArgs.chartName}-\$CHART_VERSION.tgz \
                --kubeconfig ${KUBE_CNF}
        """
        DockerUtils.runInDocker(context, mergedArgs.dockerImage, script)
    }
}

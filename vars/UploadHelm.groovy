import com.unity.DockerUtils

def call(Map args = [:]) {
    Map argsDefault = [
            chartDir   : "./helm",
            dockerImage: 'quay.io/helmpack/chart-testing:v3.0.0-beta.2',
            command    : 'helm package --dependency-update',
            chartMuseum: 'https://chartmuseum.internal.unity3d.com',
            name       : 'Upload: Helm Chart',
    ]

    Map mergedArgs = argsDefault + args
    context = this

    //Define directory checking commands
    String script = """
        if [ ! -d ${mergedArgs.chartDir} ]; then 
            echo \"Helm directory ${mergedArgs.chartDir} not found. Either add the required directory or skip the default helm stages by adding the variable: \$HELM_DISABLED. \" 1>&2
        fi
        # Add repo
        helm repo add chartmuseum ${mergedArgs.chartMuseum}
        helm repo update
        # Set chart info
        CHART_NAME=`helm show chart ${mergedArgs.chartDir} | grep name | awk '{print \$2}'`
        CHART_VERSION=`helm show chart ${mergedArgs.chartDir} | grep version | awk '{print \$2}'`
        CHART_PACKAGE=\$CHART_NAME-\$CHART_VERSION.tgz
        # Remove package, then upload new package
        curl --silent --request DELETE ${mergedArgs.chartMuseum}/api/charts/\$CHART_NAME/\$CHART_VERSION || true
        curl --silent -H 'Content-Encoding: gzip' -H 'Content-Type: application/gzip' --request POST --data-binary \"@\$CHART_PACKAGE\" ${mergedArgs.chartMuseum}/api/charts
    """

    DockerUtils.runInDocker(context, mergedArgs.dockerImage, script)
}

import com.unity.DockerUtils

def call(Map args = [:]) {
    Map argsDefault = [
            dockerImage: "kiwigrid/gcloud-kubectl-helm",
            name       : "Authentication: GCloud",
    ]

    Map mergedArgs = argsDefault + args
    context = this
    withCredentials([string(credentialsId: mergedArgs.gkeStrCredsID, variable: "GC_KEY")]) {
        sh 'echo "$GC_KEY" | tee key.json'
    }

    String params = "-w /workspace -v \${PWD}:/workspace -v /var/run/docker.sock:/var/run/docker.sock -it"
    String script = """
        gcloud auth activate-service-account --key-file=key.json
        yes | gcloud auth configure-docker
        rm key.json
        docker pull gcr.io/unity-labs-createstudio-test/basetools:1.0.0
        docker pull gcr.io/unity-labs-createstudio-test/sonarqube-msbuild
        """

    DockerUtils.runInDocker(context, mergedArgs.dockerImage, script)
}

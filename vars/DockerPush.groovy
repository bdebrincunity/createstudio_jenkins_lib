import com.createstudio.DockerUtilities

def call(Map args = [:]) {
    Map argsDefault = [
            name       : "Deploy: Docker Push",
    ]

    Map mergedArgs = argsDefault + args
    context = this

    withCredentials([string(credentialsId: mergedArgs.gkeStrCredsID, variable: "GC_KEY")]) {
        sh 'echo "$GC_KEY" | tee key.json'
    }

    withEnv([
            "version=${sh([returnStdout: true, script: 'echo ${CURRENT_VERSION:-}']).trim()}",
            "branch=${sh([returnStdout: true, script: 'echo $BRANCH']).trim()}",
            "service_name=${sh([returnStdout: true, script: 'echo $SERVICE_NAME']).trim()}",
            "registry=${sh([returnStdout: true, script: 'echo $DOCKER_REG']).trim()}",
            "revision=${sh([returnStdout: true, script: 'git log --format=\"%H\" -n 1']).trim()}",
            "committer=${sh([returnStdout: true, script: 'git log --format=\"%cn\" -n 1']).trim()}",
            "author=${sh([returnStdout: true, script: 'git log --format=\"%an\" -n 1']).trim()}",
            "identifier=${sh([returnStdout: true, script: 'uuidgen']).trim()}",
    ]) {
        if (!version?.trim()) {
            println("Our version string is set to ${version}")
            image_name = "${registry}/${service_name}:${branch}-${version}"
        } else {
            println("Our version string is empty or null")
            image_name = "${registry}/${service_name}"
        }   
        withEnv([
                "image=${image_name}",
                "DOCKER_PARAMS= --log-driver json-file --net=container:network-${identifier} -e JENKINS_URL -e GIT_BRANCH=${env.BRANCH_NAME} -e GIT_COMMIT=${revision} -e CHANGE_ID -e BRANCH_NAME -e BUILD_NUMBER -e BUILD_URL ",
        ]) {
           DockerUtilities.pushImage(context)
        }
    }
}

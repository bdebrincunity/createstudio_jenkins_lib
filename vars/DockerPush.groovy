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
            "version=${sh([returnStdout: true, script: 'echo $CURRENT_VERSION']).trim()}",
            "name_id=${sh([returnStdout: true, script: 'echo $NAME_ID']).toLowerCase}",
            "registry=${sh([returnStdout: true, script: 'echo $DOCKER_REG']).trim()}",
            "revision=${sh([returnStdout: true, script: 'git log --format=\"%H\" -n 1']).trim()}",
            "committer=${sh([returnStdout: true, script: 'git log --format=\"%cn\" -n 1']).trim()}",
            "author=${sh([returnStdout: true, script: 'git log --format=\"%an\" -n 1']).trim()}",
            "identifier=${sh([returnStdout: true, script: 'uuidgen']).trim()}",
    ]) {
        withEnv([
                "image=${registry}/${name_id}:${version}",
                "DOCKER_PARAMS= --log-driver json-file --net=container:network-${identifier} -e JENKINS_URL -e GIT_BRANCH=${env.BRANCH_NAME} -e GIT_COMMIT=${revision} -e CHANGE_ID -e BRANCH_NAME -e BUILD_NUMBER -e BUILD_URL ",
        ]) {
           DockerUtilities.pushImage(context)
        }
    }
}

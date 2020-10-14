import java.text.SimpleDateFormat
// Helper functions

// Upload to gcp
def uploadFile(String filename, String bucket, String strip_dir){
    googleStorageUpload bucket: "gs://${bucket}", credentialsId: 'sa-createstudio-buckets', pattern: "${filename}", pathPrefix: "${strip_dir}"
}
// Download from gcp
def downloadFile(String filename, String bucket){
    googleStorageDownload bucketUri: "gs://${bucket}/${filename}", credentialsId: 'sa-createstudio-buckets', localDirectory: "."
}

def call(body) {
    // evaluate the body block, and collect configuration into the object
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()
    // Get Jenkinsfile path from the project
    def currentScriptPath = currentBuild.rawBuild.parent.definition.scriptPath
    // Obtain only the Project DIR so this will be our working directory
    def JENKINSFILE_DIR = new File(currentScriptPath).parent
    def build_script = libraryResource 'com/createstudio/scripts/unity_build.sh'

    /*
        This is the main pipeline section with the stages of the CI/CD
     */
    pipeline {

        options {
            // Build auto timeout
            timeout(time: 600, unit: 'MINUTES')
            ansiColor('xterm')
        }

        // Some global default variables
        environment {
            SERVICE_NAME = "${pipelineParams.SERVICE_NAME}"
            PROJECT_TYPE = "${pipelineParams.PROJECT_TYPE}"
            SERVER_PORT = "${pipelineParams.SERVER_PORT}"
            TEST_LOCAL_PORT = "${pipelineParams.TEST_LOCAL_PORT}"
            PROJECT_DIR = "${JENKINSFILE_DIR}"
            DEPLOY_PROD = false
            DOCKER_REG = 'gcr.io/unity-labs-createstudio-test'
            HELM_REPO = 'https://chartmuseum.internal.unity3d.com/'
            NAME_ID = "${SERVICE_NAME}-${BRANCH_NAME}"
            BRANCH = BRANCH_NAME.toLowerCase()
            ID = NAME_ID.toLowerCase().replaceAll("_", "-").replaceAll('/', '-')
            BUILD_UUID = UUID.randomUUID().toString()
            buildManifest = 'artifacts/build_manifest.json'
            gcpBucketCICD = 'createstudio_ci_cd'
            gcpBucketCredential = 'sa-createstudio-buckets'
            registryCredential = 'sa-createstudio-jenkins'
            registry = 'gcr.io/unity-labs-createstudio-test'
            namespace = 'labs-createstudio'
            GIT_SSH_COMMAND = "ssh -o StrictHostKeyChecking=no"
        }

        parameters {
            booleanParam (name: 'DEPLOY_TO_PROD', defaultValue: false, description: 'If build and tests are good, proceed and deploy to production without manual approval')
            booleanParam (name: 'BUMP_MAJOR', defaultValue: false, description: 'Bump Major Semver')
            booleanParam (name: 'BUMP_MINOR', defaultValue: false, description: 'Bump Minor Semver')
            booleanParam (name: 'BUMP_PATCH', defaultValue: false, description: 'Bump Patch Semver')
        }

        agent any

        // Pipeline stages
        stages {
            stage('Update SCM Variables') {
                environment {
                    GOOGLE_APPLICATION_CREDENTIALS = credentials('sa-createstudio-jenkins')
                }
                steps {
                    script {
                        echo "Pull custom docker images"
                        PullCustomImages(gkeStrCredsID: 'sa-gcp-jenkins')
                        echo "Global ID set to ${ID}"
                        def listName = PROJECT_TYPE.split(",")
                        listName.each { item ->
                            echo "${item}"
                        }
                    }
                }
            }
            ////////// Step 2 //////////
            stage('Update LFS') {
                steps {
                    container('cloudbees-jenkins-worker') {
                        script {
                            sshagent (credentials: ['ssh_createstudio']) {
                                // Update url to use ssh instead of https
                                sh("git config --global --add url.\"git@github.com:\".insteadOf \"https://github.com/\"")
                                // Install LFS hooks in repo
                                sh("git lfs install")
                                // Pull LFS files
                                sh("git lfs pull origin")
                            }
                        }
                   }
                }
            }
            // Parallel builds work, but share workspace which Unity complains about, need to figure out how to separate into different workspaces
           /*
            stage('Parallel Unity Build') {
                steps {
                    //dir("${PROJECT_DIR}") {
                        //container('docker') {
                            script {
                                def builds = [:]
                                def projectList = PROJECT_TYPE.split(",")
                                projectList.each { item ->
                                    builds["${item}"] = {
                                        stage("Build ${item}") {
                                            container('docker') {
                                                echo "Build ${item} - ${ID}"
                                                rnd = Math.abs(new Random().nextInt() % 3000) + 1
                                                //sleep(rnd)
                                                w${SERVICE_NAME}ithCredentials([
                                                    [$class: 'UsernamePasswordMultiBinding', credentialsId:'unity_pro_login', usernameVariable: 'UNITY_USERNAME', passwordVariable: 'UNITY_PASSWORD'],
                                                    [$class: 'StringBinding', credentialsId: 'unity_pro_license_content', variable: 'UNITY_LICENSE_CONTENT'],
                                                    [$class: 'StringBinding', credentialsId: 'unity_pro_serial', variable: 'UNITY_SERIAL']
                                                ]){
                                                    //docker.image("gableroux/unity3d:2019.4.3f1-${item}").inside("-w /${item} -v \${PWD}:/${item} -it") {
                                                    docker.image("gableroux/unity3d:2019.4.3f1-${item}").inside() {
                                                        sh("pwd")
                                                        sh("ls -la")
                                                        sh("ls -la ../")
                                                        sshagent (credentials: ['ssh_createstudio']) {
                                                            sh("files/build.sh ${item}")
                                                        }
                                                        project = sh(returnStdout: true, script: "find . -maxdepth 1 -type d | grep ${item} | sed -e 's/\\.\\///g'").trim()
                                                        sh("ls -la")
                                                        echo ("Built ${project} !")
                                                        if ("${item}" == 'mac') {
                                                            // Update permissions to OSX project, needed to launch
                                                            sh("chmod +x ${project}/Contents/MacOS/*")
                                                        }
                                                        archiveArtifacts allowEmptyArchive: false, artifacts: "${project}/", fingerprint: true, followSymlinks: false
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                // Execute our parallel builds based on PROJECT_TYPES
                                parallel builds
                            }
                        //}
                    //}
                }
            }*/
            ////////// Step 3 //////////
            stage("Get Version") {
                environment {
                    GOOGLE_APPLICATION_CREDENTIALS = credentials('sa-createstudio-jenkins')
                }
                when {
                    anyOf {
                        expression { BRANCH_NAME ==~ /(main|staging|develop)/ }
                    }
                }
                steps {
                    dir("${PROJECT_DIR}") {
                        container('docker') {
                            script {
                                //sh("[ -z \"\$(docker images -a | grep \"${DOCKER_REG}/${SERVICE_NAME} 2>/dev/null)\" ] || PullCustomImages(gkeStrCredsID: 'sa-gcp-jenkins')")
                                docker.image("gcr.io/unity-labs-createstudio-test/basetools:1.0.0").inside("-w /workspace -v \${PWD}:/workspace -it") {
                                    manifestDateCheckPre = sh(returnStdout: true, script: "python3 /usr/local/bin/gcp_bucket_check.py | grep Updated")
                                    println(manifestDateCheckPre)
                                    VERSION = IncrementVersion()
                                    echo "Version is ${VERSION}"
                                }
                            }
                        }
                    }
                }
            }
            stage('Create Mac Build') {
                environment {
                    type = "mac"
                }
                when {
                    expression { "${PROJECT_TYPE}".contains('mac') }
                }
                steps {
                    dir("${PROJECT_DIR}") {
                        container('docker') {
                            script {
                                withCredentials([
                                    [$class: 'UsernamePasswordMultiBinding', credentialsId:'unity_pro_login', usernameVariable: 'UNITY_USERNAME', passwordVariable: 'UNITY_PASSWORD'],
                                    [$class: 'StringBinding', credentialsId: 'unity_pro_license_content', variable: 'UNITY_LICENSE_CONTENT'],
                                    [$class: 'StringBinding', credentialsId: 'unity_pro_serial', variable: 'UNITY_SERIAL']
                                ]){
                                    docker.image("gableroux/unity3d:2019.4.3f1-${type}").inside("-w /workspace -v \${PWD}:/workspace -it") {
                                        sshagent (credentials: ['ssh_createstudio']) {
                                            if (binding.hasVariable('VERSION')) {
                                                withEnv(["CURRENT_VERSION=${VERSION}"]) {
                                                    echo "Did we receive ${CURRENT_VERSION} ?"
                                                    sh("files/build.sh ${type}")
                                                }
                                            } else {
                                                sh("files/build.sh ${type}")
                                            }
                                        }
                                        project = sh(returnStdout: true, script: "find . -maxdepth 1 -type d | grep ${SERVICE_NAME}-${type} | sed -e 's/\\.\\///g'").trim()
                                        sh("ls -la ${project}")
                                        echo ("Built ${project} !")
                                        if ("${type}" == 'mac') {
                                            // Update permissions to OSX project, needed to launch
                                            sh("find ./${project} -name \"*.app\" -exec chmod +x {} \\;")
                                        }
                                        archiveArtifacts allowEmptyArchive: false, artifacts: "${project}/", fingerprint: true, followSymlinks: false
                                    }
                                }
                            }
                        }
                    }
                }
            }
            stage('Create Windows Build') {
                environment { type = "windows" }
                when {
                    expression { "${PROJECT_TYPE}".contains('windows') }
                }
                steps {
                    dir("${PROJECT_DIR}") {
                        container('docker') {
                            script {
                                withCredentials([
                                    [$class: 'UsernamePasswordMultiBinding', credentialsId:'unity_pro_login', usernameVariable: 'UNITY_USERNAME', passwordVariable: 'UNITY_PASSWORD'],
                                    [$class: 'StringBinding', credentialsId: 'unity_pro_license_content', variable: 'UNITY_LICENSE_CONTENT'],
                                    [$class: 'StringBinding', credentialsId: 'unity_pro_serial', variable: 'UNITY_SERIAL']
                                ]){
                                    docker.image("gableroux/unity3d:2019.4.3f1-${type}").inside("-w /workspace -v \${PWD}:/workspace -it") {
                                        sshagent (credentials: ['ssh_createstudio']) {
                                            if (binding.hasVariable('VERSION')) {
                                                withEnv(["CURRENT_VERSION=${VERSION}"]) {
                                                    sh("files/build.sh ${type}")
                                                }
                                            } else {
                                                sh("files/build.sh ${type}")
                                            }
                                        }
                                        project = sh(returnStdout: true, script: "find . -maxdepth 1 -type d | grep ${SERVICE_NAME}-${type} | sed -e 's/\\.\\///g'").trim()
                                        sh("ls -la ${project}")
                                        echo ("Built ${project} !")
                                        archiveArtifacts allowEmptyArchive: false, artifacts: "${project}/", fingerprint: true, followSymlinks: false
                                    }
                                }
                            }
                        }
                    }
                }
            }
            ////////// Step 2 //////////
            stage('Build WebGL Docker') {
                environment {
                    type = "webgl"
                }
                when {
                    expression { "${PROJECT_TYPE}".contains('webgl') }
                }
                steps {
                    dir("${PROJECT_DIR}") {
                        container('docker') {
                            script {
                                withCredentials([
                                    [$class: 'UsernamePasswordMultiBinding', credentialsId:'unity_pro_login', usernameVariable: 'UNITY_USERNAME', passwordVariable: 'UNITY_PASSWORD'],
                                    [$class: 'StringBinding', credentialsId: 'unity_pro_license_content', variable: 'UNITY_LICENSE_CONTENT'],
                                    [$class: 'StringBinding', credentialsId: 'unity_pro_serial', variable: 'UNITY_SERIAL']
                                ]){
                                    docker.image("gableroux/unity3d:2019.4.3f1-${type}").inside("-w /workspace -v \${PWD}:/workspace -it") {
                                        sshagent (credentials: ['ssh_createstudio']) {
                                            if (binding.hasVariable('VERSION')) {
                                                withEnv(["CURRENT_VERSION=${VERSION}"]) {
                                                    sh("files/build.sh ${type}")
                                                }
                                            } else {
                                                sh("files/build.sh ${type}")
                                            }
                                        }
                                        project = sh(returnStdout: true, script: "find . -maxdepth 1 -type d | grep ${SERVICE_NAME}-${type} | sed -e 's/\\.\\///g'").trim()
                                        sh("ls -la ${project}")
                                        echo ("Built ${project} !")
                                        archiveArtifacts allowEmptyArchive: false, artifacts: "${project}/", fingerprint: true, followSymlinks: false
                                    }
                                }
                                sh("docker rm -f ${DOCKER_REG}/${ID} || true ")
                                sh("docker rm -f ${ID} || true ")
                                sh("docker rmi -f ${DOCKER_REG}/${ID} || true ")
                                sh("docker rmi -f ${ID} || true ")
                                sh """
                                   docker create --name ${ID} -w /${type} nginx:stable
                                   docker cp files/webgl.conf ${ID}:/etc/nginx/conf.d/
                                   docker start ${ID}
                                   docker exec ${ID} rm -f /etc/nginx/conf.d/default.conf
                                   docker stop ${ID}
                                   docker cp ${project}/. ${ID}:/${type}
                                   """
                                if (binding.hasVariable('VERSION')) {
                                    myapp = sh(returnStdout: true, script: "docker commit ${ID} ${DOCKER_REG}/${ID}:${VERSION}").trim()
                                } else {
                                    myapp = sh(returnStdout: true, script: "docker commit ${ID} ${DOCKER_REG}/${ID}").trim()
                                }
                                // Save this below for local testing in the future
                                //echo "Starting ${ID} container"
                                //sh "docker run --detach --rm --publish ${TEST_LOCAL_PORT}:80 ${DOCKER_REG}/${ID}"

                                // Grab IP from container
                                //host_ip = sh(returnStdout: true, script: '/sbin/ip route | awk \'/default/ { print $3 ":${TEST_LOCAL_PORT}" }\'')
                            }
                        }
                    }
                }
            }
            stage('ENV TEST - Publish Docker and Helm artifacts') {
                environment {
                    home = "${WORKSPACE}" 
                    env = 'test'
                    GOOGLE_APPLICATION_CREDENTIALS = credentials('sa-createstudio-jenkins')
                }
                when {
                    expression { "${PROJECT_TYPE}".contains('webgl')  }
                    anyOf {
                        expression { BRANCH_NAME ==~ /(main|staging|develop)/ }
                    }
                }
                steps {
                    dir("${PROJECT_DIR}") {
                        script {
                            echo "Pushing Docker Image -> ${DOCKER_REG}/${ID}:${VERSION}"
                            DockerPush(gkeStrCredsID: 'sa-gcp-jenkins')
                            echo "Packaging helm chart"
                            PackageHelmChart(chartDir: "./helm")
                            echo "Pushing helm chart"
                            UploadHelm(chartDir: "./helm")
                            downloadFile("k8s/configs/${env}/kubeconfig-labs-createstudio-${env}_environment", 'createstudio_ci_cd')
                            // need to simplify this
                            KUBE_CNF = "k8s/configs/${env}/kubeconfig-labs-createstudio-${env}_environment"
                            ApplyHelmChart(releaseName: "${ID}", chartName: "${SERVICE_NAME}", chartValuesFile: "helm/values.yaml", extraParams: "--kubeconfig ${KUBE_CNF} --namespace ${namespace} --set image.repository=${DOCKER_REG}/${SERVICE_NAME} --set image.tag=${ID}")
                        }
                    }
                }
            }
            /// Cleanup an deployments outside of the 3 main branches --> not being deployed right now
            /*stage('Cleanup') {
                environment {
                    env = 'test'
                }
                when {
                    expression { "${PROJECT_TYPE}".contains('webgl')  }
                    not { 
                        anyOf {
                            expression { BRANCH_NAME ==~ /(main|staging|develop)/ }
                        }
                    }
                }
                steps {
                    dir("${PROJECT_DIR}") {
                        script {
                            // Remove release if exists
                            downloadFile("k8s/configs/${env}/kubeconfig-labs-createstudio-${env}_environment", 'createstudio_ci_cd')
                            scriptToRun = "[ -z \"\$(helm ls --kubeconfig ${KUBE_CNF} | grep ${ID} 2>/dev/null)\" ] || helm delete ${ID} --kubeconfig ${KUBE_CNF}"
                            RunInDocker(dockerImage: "kiwigrid/gcloud-kubectl-helm", script: scriptToRun, name: "Remove Helm Release")
                        }
                    }
                }
            }*/
            stage('Update Version Manifest') {
                environment {
                    GOOGLE_APPLICATION_CREDENTIALS = credentials('sa-createstudio-jenkins')
                }
                when {
                    anyOf {
                        expression { BRANCH_NAME ==~ /(main|staging|develop)/ }
                    }
                }
                steps {
                    dir("${PROJECT_DIR}") {
                        container('docker') {
                            script {
                                docker.image("gcr.io/unity-labs-createstudio-test/basetools:1.0.0").inside("-w /workspace -v \${PWD}:/workspace -it") {
                                    manifestDateCheckPost = sh(returnStdout: true, script: "python3 /usr/local/bin/gcp_bucket_check.py | grep Updated")
                                    // Check if manifest has been updated, if so, re-run incrementing version
                                    if ( manifestDateCheckPre == manifestDateCheckPost ) {
                                        uploadFile("${buildManifest}", 'createstudio_ci_cd', "${PROJECT_DIR}")
                                    } else {
                                        echo "BuildManifest has Changed since last process! Re-running version incrementing"
                                        IncrementVersion()
                                        uploadFile("${buildManifest}", 'createstudio_ci_cd', "${PROJECT_DIR}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Run the 3 tests from the shared library on the currently running Docker container
            /*stage('Local tests') {
                when {
                    expression { "${PROJECT_TYPE}".contains('webgl') }
                }
                parallel {
                    stage('Curl http_code') {
                        steps {
                            curlRun ("http://${host_ip}", 'http_code')
                        }
                    }
                    stage('Curl total_time') {
                        steps {
                            curlRun ("http://${host_ip}", 'total_time')
                        }
                    }
                    stage('Curl size_download') {
                        steps {
                            curlRun ("http://${host_ip}", 'size_download')
                        }
                    }
                }
            }*/
        }
        post {
            always {
                echo 'One way or another, I have finished'
                archiveArtifacts allowEmptyArchive: false, artifacts: "**/*.log", fingerprint: true, followSymlinks: false
                SendSlack("${currentBuild.currentResult}")
            }
            success {
                echo 'I succeeded!'
            }
            unstable {
                echo 'I am unstable :/'
            }
            failure {
                echo 'I failed :('
            }
            changed {
                echo 'Things were different before...'
            }
        }
    }
}

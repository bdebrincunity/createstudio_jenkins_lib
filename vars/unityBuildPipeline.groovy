import java.text.SimpleDateFormat
// Helper functions

def dateFormat = new SimpleDateFormat("yyyyMMddHHmm")
def date = new Date()

// Upload to gcp
def uploadFile(String filename, String bucket, String strip_dir){
    googleStorageUpload bucket: "gs://${bucket}", credentialsId: 'sa-createstudio-buckets', pattern: "${filename}", pathPrefix: "${strip_dir}"
}
// Download from gcp
def downloadFile(String filename, String bucket){
    googleStorageDownload bucketUri: "gs://${bucket}/${filename}", credentialsId: 'sa-createstudio-buckets', localDirectory: "."
}

def getVersion(){
    /* Grab the current version
    * Check if we want to bump MAJOR or MINOR, if not then we will
    * always bump PATCH version
    * Then grab the new version and return to pipeline */
    status = downloadFile("${buildManifest}", 'createstudio_ci_cd')
    LATEST_VERSION = sh(script: "jq '.docker.\"${SERVICE_NAME}\"[].version' ${buildManifest}| tail -1", returnStdout: true).trim()
    // Remove build number so we can semver. Will add back new build number after
    //LATEST_VERSION = \"VERSION\".replaceFirst("..\$", "")
    sh("BUILD_ID -> ${BUILD_ID}")
    sh("echo ${LATEST_VERSION}")
    //sh("echo ${VERSION}")
    if ( "${LATEST_VERSION}" == null ) {
        sh ("jq '.docker += { \"${SERVICE_NAME}\": [{\"version\": \"0.0.1\", \"tags\":{\"UUID\": \"${UUID}\", \"last_build_time\": \"${date}\"}}]}' ${buildManifest} > ${buildManifest}2")
        sh ("mv ${buildManifest}2 ${buildManifest}")
        LATEST_VERSION = "0.0.1"
    }
    // Need to implement parameters here, this does nothing at the moment other than bumping patch
    // Maybe we bump on certain PR merge
    if (env.BUMP_MAJOR == true){
        version = sh(script: "semver bump major ${LATEST_VERSION}", returnStdout: true).trim()
    }
    else if (env.BUMP_MINOR  == true){
        version = sh(script: "semver bump minor ${LATEST_VERSION}", returnStdout: true).trim()
    }
    else {
        version = sh(script: "semver bump patch ${LATEST_VERSION}", returnStdout: true).trim()
    }
    new_version = version + ".${BUILD_NUMBER}"
    // Always run bumping patch version
    sh ("jq '.docker.\"${SERVICE_NAME}\" += [{\"version\": \"${new_version}\", \"tags\": { \"UUID\": \"${UUID}\", \"last_build_time\": \"${date}\"}}]' ${buildManifest} | sponge ${buildManifest}")
    return new_version
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
    def PROJECT_DIR = new File(currentScriptPath).parent
    def build_script = libraryResource 'com/createstudio/scripts/unity_build.sh'

    /*
        This is the main pipeline section with the stages of the CI/CD
     */
    pipeline {

        options {
            // Build auto timeout
            timeout(time: 60, unit: 'MINUTES')
            ansiColor('xterm')
        }

        // Some global default variables
        environment {
            SERVICE_NAME = "${pipelineParams.SERVICE_NAME}"
            PROJECT_TYPE = "${pipelineParams.PROJECT_TYPE}"
            SERVER_PORT = "${pipelineParams.SERVER_PORT}"
            TEST_LOCAL_PORT = "${pipelineParams.TEST_LOCAL_PORT}"
            DEPLOY_PROD = false
            DOCKER_REG = 'gcr.io/unity-labs-createstudio-test'
            HELM_REPO = 'https://chartmuseum.internal.unity3d.com/'
            NAME_ID = "${SERVICE_NAME}-${BRANCH_NAME}"
            KUBE_CNF = "k8s/configs/${env}/kubeconfig-labs-createstudio-${env}_environment"
            ID = NAME_ID.toLowerCase().replaceAll("_", "-").replaceAll('/', '-')
            UUID = UUID.randomUUID().toString()
            buildManifest = 'docker/build_manifest.json'
            gcpBucketCredential = 'sa-createstudio-bucket'
            registryCredential = 'sa-createstudio-jenkins'
            registry = 'gcr.io/unity-labs-createstudio-test'
            namespace = 'labs-createstudio'
            GIT_SSH_COMMAND = "ssh -o StrictHostKeyChecking=no"
        }

        parameters {
    //        string (name: 'GIT_BRANCH',           defaultValue: 'main',  description: 'Git branch to build')
            booleanParam (name: 'DEPLOY_TO_PROD', defaultValue: false,     description: 'If build and tests are good, proceed and deploy to production without manual approval')
        }

        agent any

        // Pipeline stages
        stages {
            ////////// Step 1 //////////
            stage('SCM Variables') {
                steps {
                    script {
                        echo "Global ID set to ${ID}"
                        VERSION = getVersion()
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
                                                withCredentials([
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
            stage('Create Mac Build') {
                environment { type = "mac" }
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
                                            sh("files/build.sh ${type}")
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
                                            sh("files/build.sh ${type}")
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
                                            sh("files/build.sh ${type}")
                                        }
                                        project = sh(returnStdout: true, script: "find . -maxdepth 1 -type d | grep ${type} | sed -e 's/\\.\\///g'").trim()
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
                                   docker create --name ${ID} -w /${PROJECT_TYPE} nginx:stable
                                   docker cp files/webgl.conf ${ID}:/etc/nginx/conf.d/
                                   docker start ${ID}
                                   docker exec ${ID} rm -f /etc/nginx/conf.d/default.conf
                                   docker stop ${ID}
                                   docker cp ${project}/. ${ID}:/${PROJECT_TYPE}
                                   """
                                myapp = sh(returnStdout: true, script: "docker commit ${ID} ${DOCKER_REG}/${SERVICE_NAME}:${ID}").trim()
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
                            echo "Pushing Docker Image -> ${DOCKER_REG}/${ID}"
                            DockerPush(gkeStrCredsID: 'sa-gcp-jenkins')
                            echo "Packaging helm chart"
                            PackageHelmChart(chartDir: "./helm")
                            echo "Pushing helm chart"
                            UploadHelm(chartDir: "./helm")
                            downloadFile("k8s/configs/${env}/kubeconfig-labs-createstudio-${env}_environment", 'createstudio_ci_cd')
                            ApplyHelmChart(releaseName: "${ID}", chartName: "${SERVICE_NAME}", chartValuesFile: "helm/values.yaml", extraParams: "--kubeconfig ${KUBE_CNF} --namespace ${namespace} --set image.repository=${DOCKER_REG}/${SERVICE_NAME} --set image.tag=${ID}")
                        }
                    }
                }
            }
            /// Cleanup an deployments outside of the 3 main branches
            stage('Cleanup') {
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
                            scriptToRun = "[ -z \"\$(helm ls --kubeconfig ${KUBE_CNF} | grep ${ID} 2>/dev/null)\" ] || helm delete ${ID} --kubeconfig ${KUBE_CNF}"
                            RunInDocker(dockerImage: "kiwigrid/gcloud-kubectl-helm", script: scriptToRun, name: "Remove Helm Release")
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
            }
            success {
                echo 'I succeeded!'
            }
            //unstable {
            //    echo 'I am unstable :/'
            //}
            failure {
                echo 'I failed :('
                archiveArtifacts allowEmptyArchive: false, artifacts: "**/*.log", fingerprint: true, followSymlinks: false
            }
            //changed {
            //    echo 'Things were different before...'
            //}
        }
    }
}

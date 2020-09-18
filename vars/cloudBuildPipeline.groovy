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
    def PROJECT_DIR = new File(currentScriptPath).parent

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
            BUILD_UUID = UUID.randomUUID().toString()
            GOOGLE_APPLICATION_CREDENTIALS = credentials('sa-createstudio-jenkins')
            buildManifest = 'docker/build_manifest.json'
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
        }

        agent any

        // Pipeline stages
        stages {
            ////////// Step 1 //////////
            stage('Update SCM Variables') {
                steps {
                    script {
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
            ////////// Step 3 //////////
            stage("Get Version") {
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
                                //PullCustomImages(gkeStrCredsID: 'sa-gcp-jenkins')
                                docker.image("gcr.io/unity-labs-createstudio-test/basetools:1.0.0").inside("-w /workspace -v \${PWD}:/workspace -it") {
                                    manifestDateCheckPre = sh(returnStdout: true, script: "python3 /usr/local/bin/gcp_bucket_check.py | grep Updated")
                                    println(manifestDateCheckPre)
                                    def VERSION = IncrementVersion()
                                    echo "Version is ${VERSION}"
                                }
                            }
                        }
                    }
                }
            }
            ////////// Step 4 //////////
            stage('Build and tests') {
                steps {
                    dir("${PROJECT_DIR}") {
                        container('docker') {
                            script {
                                // Kill container in case there is a leftover
                                sh "[ -z \"\$(docker ps -a | grep ${SERVICE_NAME} 2>/dev/null)\" ] || docker rm -f ${SERVICE_NAME}"
                                echo "Building application and Docker image"
                                if ( VERSION != null ) {
                                    myapp = sh("docker build -t ${DOCKER_REG}/${ID}:${VERSION} . || errorExit \"Building ${SERVICE_NAME} failed\"")
                                    echo "Starting ${SERVICE_NAME} container"
                                    sh "docker run --detach --name ${SERVICE_NAME} --rm --publish ${TEST_LOCAL_PORT}:80 ${DOCKER_REG}/${ID}:${VERSION}"
                                } else {
                                    myapp = sh("docker build -t ${DOCKER_REG}/${ID} . || errorExit \"Building ${SERVICE_NAME} failed\"")
                                    echo "Starting ${SERVICE_NAME} container"
                                    sh "docker run --detach --name ${SERVICE_NAME} --rm --publish ${TEST_LOCAL_PORT}:80 ${DOCKER_REG}/${ID}"
                                }
                                echo "Running local docker tests"

                                host_ip = sh(returnStdout: true, script: '/sbin/ip route | awk \'/default/ { print $3 ":${TEST_LOCAL_PORT}" }\'')
                            }
                        }
                    }
                }
            }
            ////////// Step 5 //////////
            stage('Publish Docker and Helm') {
                environment {
                    // Pulled from Step 3
                    CURRENT_VERSION = "${VERSION}"
                }
                when {
                    anyOf {
                        expression { BRANCH_NAME ==~ /(main|staging|develop)/ }
                    }
                }
                steps {
                    dir("${PROJECT_DIR}") {
                            script {
                            echo "Packaging helm chart"
                            PackageHelmChart(chartDir: "./helm", extraParams: "--version ${CURRENT_VERSION} --app-version ${CURRENT_VERSION}")
                            echo "Pushing helm chart"
                            UploadHelm(chartDir: "./helm")
                            echo "Pushing Docker chart"
                            DockerPush(gkeStrCredsID: 'sa-gcp-jenkins')
                        }
                    }
                }
            }
            ////////// Step 6 //////////
            stage('Deploy to TEST') {
                environment {
                    home = "${WORKSPACE}"
                    env = 'test'
                }
                when {
                    anyOf {
                        expression { BRANCH_NAME ==~ /(main|staging|develop)/ }
                    }
                }
                steps {
                    dir("${PROJECT_DIR}") {
                        script {
                            echo "Deploying application ${ID} to ${env} kubernetes cluster "
                            downloadFile("k8s/configs/${env}/kubeconfig-labs-createstudio-${env}_environment", 'createstudio_ci_cd')
                            ApplyHelmChart(releaseName: "${ID}", chartName: "${SERVICE_NAME}", chartValuesFile: "helm/values.yaml", extraParams: "--kubeconfig ${KUBE_CNF} --namespace ${namespace} --set image.repository=${DOCKER_REG}/${SERVICE_NAME} --set image.tag=${ID}")
                        }
                    }
                }
            }
            ////////// Step 7 //////////
            stage('Update Version Manifest') {
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
                                    println(manifestDateCheckPre)
                                    println(manifestDateCheckPost)
                                    if ( manifestDateCheckPre == manifestDateCheckPost ) {
                                        uploadFile("${buildManifest}", 'createstudio_ci_cd', "${PROJECT_DIR}")
                                    } else {
                                        echo "BuildManifest has Changed since last process! Re-running version incrementing"
                                        getVersion()
                                        uploadFile("${buildManifest}", 'createstudio_ci_cd', "${PROJECT_DIR}")
                                    }
                                }
                            }
                        }
                    }
                }
            }

        }
        post {
            //always {
            //    echo 'One way or another, I have finished'
            //    cleanWs() /* clean up our workspace */
            //}
            success {
                echo 'I succeeded!'
            }
            unstable {
                echo 'I am unstable :/'
            }
            failure {
                echo 'I failed :('
                archiveArtifacts allowEmptyArchive: false, artifacts: "**/*.log", fingerprint: true, followSymlinks: false
            }
            changed {
                echo 'Things were different before...'
            }
        }
    }
}

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

def getVersion(){
    /* Grab the current version
    * Check if we want to bump MAJOR or MINOR, if not then we will
    * always bump PATCH version
    * Then grab the new version and return to pipeline */
    status = downloadFile("${buildManifest}", 'createstudio_ci_cd')
    def dateFormat = new SimpleDateFormat("yyyyMMddHHmm")
    def date = new Date()
    def LATEST_VERSION = sh(script: "jq '.docker.\"${SERVICE_NAME}\"[].version' ${buildManifest}| tail -1", returnStdout: true).trim()
    // Remove build number so we can semver. Will add back new build number after
    //LATEST_VERSION = \"VERSION\".replaceFirst("..\$", "")
    if ( LATEST_VERSION == "" ) {
        echo "${SERVICE_NAME} does not exist in our build manifest. Will begin with version 0.1.0" 
        sh ("jq '.docker += { \"${SERVICE_NAME}\": [{\"version\": \"0.1.0\", \"tags\":{\"UUID\": \"${BUILD_UUID}\", \"last_build_time\": \"${date}\"}}]}' ${buildManifest} > ${buildManifest}2")
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
    new_version = version + "+build.${BUILD_NUMBER}"
    // Always run bumping patch version
    sh ("jq '.docker.\"${SERVICE_NAME}\" += [{\"version\": \"${new_version}\", \"tags\": { \"UUID\": \"${BUILD_UUID}\", \"last_build_time\": \"${date}\"}}]' ${buildManifest} | sponge ${buildManifest}")
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
            stage('Update SCM Variables') {
                steps {
                    dir("${PROJECT_DIR}") {
                        container('docker') {
                            script {
                                PullCustomImages(gkeStrCredsID: 'sa-gcp-jenkins')
                                docker.image("gcr.io/unity-labs-createstudio-test/base_tools").inside("-w /workspace -v \${PWD}:/workspace -it") {
                                    VERSION = getVersion()
                                    echo "Global ID set to ${ID}"
                                    def listName = PROJECT_TYPE.split(",")
                                    listName.each { item ->
                                        echo "${item}"
                                    }
                                }
                            }
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
            ////////// Step 2 //////////
            stage('Build and tests') {
                steps {
                    dir("${PROJECT_DIR}") {
                        container('docker') {
                            script {
                                echo "Building application and Docker image"
                                //myapp = BuildDockerImage(registry: registry, returnStdout: true)
                                myapp = sh("docker build -t ${DOCKER_REG}/${SERVICE_NAME} . || errorExit \"Building ${SERVICE_NAME} failed\"")

                                echo "Running local docker tests"

                                // Kill container in case there is a leftover
                                sh "[ -z \"\$(docker ps -a | grep ${SERVICE_NAME} 2>/dev/null)\" ] || docker rm -f ${SERVICE_NAME}"

                                echo "Starting ${SERVICE_NAME} container"
                                sh "docker run --detach --name ${SERVICE_NAME} --rm --publish ${TEST_LOCAL_PORT}:80 ${DOCKER_REG}/${SERVICE_NAME}"

                                host_ip = sh(returnStdout: true, script: '/sbin/ip route | awk \'/default/ { print $3 ":${TEST_LOCAL_PORT}" }\'')
                            }
                        }
                    }
                }
            }
            ////////// Step 3 //////////
            stage('Publish Docker and Helm') {
                when {
                    anyOf {
                        expression { BRANCH_NAME ==~ /(main|staging|develop)/ }
                    }
                }
                steps {
                    dir("${PROJECT_DIR}") {
                            script {
                            echo "Packaging helm chart"
                            PackageHelmChart(chartDir: "./helm")
                            echo "Pushing helm chart"
                            UploadHelm(chartDir: "./helm")
                            echo "Pushing Docker chart"
                            DockerPush(gkeStrCredsID: 'sa-gcp-jenkins')
                        }
                    }
                }
            }
            ////////// Step 4 //////////
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
        }
        post {
            //always {
            //    echo 'One way or another, I have finished'
            //    cleanWs() /* clean up our workspace */
            //}
            success {
                echo 'I succeeded!'
                script {
                    if ( "${BRANCH_NAME}" == 'develop' ) {
                        uploadFile("${PROJECT_DIR}/${buildManifest}", 'createstudio_ci_cd', "${PROJECT_DIR}")
                    } else if ( "${BRANCH_NAME}" == 'main' ) {
                        uploadFile("${PROJECT_DIR}/${buildManifest}", 'createstudio_ci_cd', "${PROJECT_DIR}")
                    }
                    } else if ( "${BRANCH_NAME}" == 'release' ) {
                        uploadFile("${PROJECT_DIR}/${buildManifest}", 'createstudio_ci_cd', "${PROJECT_DIR}")
                    }
                }
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

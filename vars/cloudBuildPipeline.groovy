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

    /*
        This is the main pipeline section with the stages of the CI/CD
     */
    pipeline {

        options {
            // Build auto timeout
            timeout(time: 60, unit: 'MINUTES')
        }

        // Some global default variables
        environment {
            IMAGE_NAME = "${pipelineParams.SERVICE_NAME}"
            TEST_LOCAL_PORT = 8080
            DEPLOY_PROD = false
            DOCKER_TAG = 'dev'
            DOCKER_REG = 'gcr.io/unity-labs-createstudio-test'
            HELM_REPO = 'https://chartmuseum.internal.unity3d.com/'
            BuildID = UUID.randomUUID().toString()
            buildManifest = 'docker/build_manifest.json'
            gcpBucketCredential = 'sa-createstudio-bucket'
            registryCredential = 'sa-createstudio-jenkins'
            registry = 'gcr.io/unity-labs-createstudio-test'
            namespace = 'labs-createstudio'
            //PROJECT_DIR = sh("dirname ${currentScriptPath}")
        }

        parameters {
//            string (name: 'GIT_BRANCH',           defaultValue: 'feature/JAR_jenkinslib',  description: 'Git branch to build')
            booleanParam (name: 'DEPLOY_TO_PROD', defaultValue: false,     description: 'If build and tests are good, proceed and deploy to production without manual approval')
        }

        agent any

        // Pipeline stages
        stages {

            ////////// Step 1 //////////
            stage('Git clone and setup') {
                steps {
                    echo "DOCKER_REG is ${DOCKER_REG}"
                    echo "HELM_REPO  is ${HELM_REPO}"
                    VERSION = getVersion()
                    // Define a unique name for the tests container and helm release
                    script {
                        branch = GIT_BRANCH.replaceAll('/', '-').replaceAll('\\*', '-')
                        NAME_ID = "${IMAGE_NAME}-${BRANCH_NAME}"
                        ID = NAME_ID.toLowerCase().replaceAll("_", "-").replaceAll('/', '-')
                        echo "Global ID set to ${ID}"
                        echo "Project DIR = ${PROJECT_DIR}"
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
                                myapp = sh("docker build -t ${DOCKER_REG}/${IMAGE_NAME} . || errorExit \"Building ${IMAGE_NAME} failed\"")

                                echo "Running local docker tests"

                                // Kill container in case there is a leftover
                                sh "[ -z \"\$(docker ps -a | grep ${IMAGE_NAME} 2>/dev/null)\" ] || docker rm -f ${IMAGE_NAME}"

                                echo "Starting ${IMAGE_NAME} container"
                                sh "docker run --detach --name ${IMAGE_NAME} --rm --publish ${TEST_LOCAL_PORT}:80 ${DOCKER_REG}/${IMAGE_NAME}"

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
                        container('docker') {
                            script {
                                echo "Packing helm chart"
                                PackageHelmChart()
                                echo "Pushing Docker chart"
                                docker.image('google/cloud-sdk:alpine').inside("-w /workspace -v \${PWD}:/workspace -it") {
                                    pushDockerImage()
                                }
                            }
                        }
                    }
                }
            }
            ////////// Step 4 //////////
            stage('Deploy to TEST') {
                when {
                    anyOf {
                        expression { BRANCH_NAME ==~ /(main|staging|develop)/ }
                    }
                }
                steps {
                    dir("${PROJECT_DIR}") {
                        container('docker') {
                            script {
                                env = 'test'
                                echo "Deploying application ${ID} to ${env} kubernetes cluster "
                                downloadFile("k8s/configs/${env}/kubeconfig-labs-createstudio-${env}_environment", 'createstudio_ci_cd')
                                installHelm()
                                sh("helm repo add chartmuseum ${HELM_REPO}")
                                sh("helm repo update")
                                // Remove release if exists
                                helmDelete (namespace, "${ID}", env)
                                // Deploy with helm
                                echo "Deploying"
                                helmInstall(namespace, "${ID}", env)
                            }
                        }
                    }
                }
            }
            stage('Deploy to STAGING') {
                when {
                    anyOf {
                        expression { BRANCH_NAME ==~ /(main|staging|develop)/ }
                    }
                }
                steps {
                    dir("${PROJECT_DIR}") {
                        container('docker') {
                            script {
                                env = 'staging'
                                echo "Deploying application ${ID} to ${env} kubernetes cluster "
                                downloadFile("k8s/configs/${env}/kubeconfig-labs-createstudio-${env}_environment", 'createstudio_ci_cd')
                                installHelm()
                                sh("helm repo add chartmuseum ${HELM_REPO}")
                                sh("helm repo update")
                                // Remove release if exists
                                helmDelete (namespace, "${ID}", env)
                                // Deploy with helm
                                echo "Deploying"
                                helmInstall(namespace, "${ID}", env)
                            }
                        }
                    }
                }
            }
        }
    }
}

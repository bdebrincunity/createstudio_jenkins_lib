
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

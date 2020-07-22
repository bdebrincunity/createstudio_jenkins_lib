def call(body) {
    // evaluate the body block, and collect configuration into the object
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()
   
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
        }
    
        parameters {
            string (name: 'GIT_BRANCH',           defaultValue: 'feature/JAR_jenkinslib',  description: 'Git branch to build')
            booleanParam (name: 'DEPLOY_TO_PROD', defaultValue: false,     description: 'If build and tests are good, proceed and deploy to production without manual approval')
    
    
            // The commented out parameters are for optionally using them in the pipeline.
            // In this example, the parameters are loaded from file ${JENKINS_HOME}/parameters.groovy later in the pipeline.
            // The ${JENKINS_HOME}/parameters.groovy can be a mounted secrets file in your Jenkins container.
    /*
            string (name: 'DOCKER_REG',       defaultValue: 'docker-artifactory.my',                   description: 'Docker registry')
            string (name: 'DOCKER_TAG',       defaultValue: 'dev',                                     description: 'Docker tag')
            string (name: 'DOCKER_USR',       defaultValue: 'admin',                                   description: 'Your helm repository user')
            string (name: 'DOCKER_PSW',       defaultValue: 'password',                                description: 'Your helm repository password')
            string (name: 'IMG_PULL_SECRET',  defaultValue: 'docker-reg-secret',                       description: 'The Kubernetes secret for the Docker registry (imagePullSecrets)')
            string (name: 'HELM_REPO',        defaultValue: 'https://artifactory.my/artifactory/helm', description: 'Your helm repository')
            string (name: 'HELM_USR',         defaultValue: 'admin',                                   description: 'Your helm repository user')
            string (name: 'HELM_PSW',         defaultValue: 'password',                                description: 'Your helm repository password')
    */
        }
    
        agent any
    
        // Pipeline stages
        stages {
    
            ////////// Step 1 //////////
            stage('Git clone and setup') {
                steps {
                    // Validate kubectl
                    //sh "kubectl cluster-info"
    
                    // Init helm client
                    //sh "helm init"
    
                    // Make sure parameters file exists
                    //script {
                    //    if (! fileExists("${PARAMETERS_FILE}")) {
                    //        echo "ERROR: ${PARAMETERS_FILE} is missing!"
                    //    }
                    //}
    
                    // Load Docker registry and Helm repository configurations from file
                    //load "${JENKINS_HOME}/parameters.groovy"
    
                    echo "DOCKER_REG is ${DOCKER_REG}"
                    echo "HELM_REPO  is ${HELM_REPO}"
    
                    // Define a unique name for the tests container and helm release
                    script {
                        branch = GIT_BRANCH.replaceAll('/', '-').replaceAll('\\*', '-')
                        NAME_ID = "${IMAGE_NAME}-${BRANCH_NAME}"
    			ID = NAME_ID.toLowerCase().replaceAll("_", "-").replaceAll('/', '-')
                        echo "Global ID set to ${ID}"
                    }
                }
            }
    
            ////////// Step 2 //////////
            stage('Build and tests') {
                steps {
                    script {
                        myapp = BuildDockerImage()
                    }
                }
            }
    
            ////////// Step 3 //////////
            stage('Publish Docker and Helm') {
                steps {
                   container('docker') {
                        script {
                            echo "Packing helm chart"
                            PackageHelmChart()
                            echo "Pushing helm chart"
                            docker.image('google/cloud-sdk:alpine').inside("-w /workspace -v \${PWD}:/workspace -it") {
                                pushDockerImage()
                            }
                        }
                    }
                }
            }
    
            ////////// Step 4 //////////
            stage('Deploy to test') {
                steps {
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
    
            stage('Cleanup Test') {
                steps {
                    script {
                        // Remove release if exists
                        helmDelete (namespace, "${ID}", "test")
                    }
                }
            }
        }
    }
}

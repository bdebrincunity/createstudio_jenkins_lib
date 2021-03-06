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
//            string (name: 'GIT_BRANCH',           defaultValue: 'feature/JAR_jenkinslib',  description: 'Git branch to build')
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
                        echo "env"
                        echo " Do you we have sparse checkout path ${SPARSE_PATH}"
                    }
                }
            }
    
            ////////// Step 2 //////////
            stage('Build and tests') {
                steps {
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
    
            // Run the 3 tests on the currently running ACME Docker container
            //stage('Local tests') {
            //    parallel {
            //        stage('Curl http_code') {
            //            steps {
            //                curlRun ("http://${host_ip}", 'http_code')
            //            }
            //        }
            //        stage('Curl total_time') {
            //            steps {
            //                curlRun ("http://${host_ip}", 'total_time')
            //            }
            //        }
            //        stage('Curl size_download') {
            //            steps {
            //                curlRun ("http://${host_ip}", 'size_download')
            //            }
            //        }
            //    }
            //}
    
            ////////// Step 3 //////////
            stage('Publish Docker and Helm') {
                environment {
                    home = "${WORKSPACE}" // Needed so tht AuthenticateGCloud and ApplyHelmChart play nice
                }
                steps {
                   container('docker') {
                        script {
                            echo "Packing helm chart"
                            PackageHelmChart()
                            echo "Pushing helm chart"
//                            pushDockerImage()
                            //UploadHelmChart(package_name: "${IMAGE_NAME}")
                            docker.image('google/cloud-sdk:alpine').inside("-w /workspace -v \${PWD}:/workspace -it") {
                                pushDockerImage()
                                //downloadFile('k8s/configs/test/kubeconfig-labs-createstudio-test_environment', 'createstudio_ci_cd')
                                //sh("helm repo add chartmuseum https://chartmuseum.internal.unity3d.com")
                                //sh("helm repo update")
                                //sh("helm upgrade --install ${IMAGE_NAME} chartmuseum/${IMAGE_NAME} --kubeconfig k8s/configs/test/kubeconfig-labs-createstudio-test_environment")
                            }
                        }
                    }
                    //echo "Pushing ${DOCKER_REG}/${IMAGE_NAME}:${DOCKER_TAG} image to registry"
                    //sh "${WORKSPACE}/build.sh --push --registry ${DOCKER_REG} --tag ${DOCKER_TAG} --docker_usr ${DOCKER_USR} --docker_psw ${DOCKER_PSW}"
    
                    //echo "Packing helm chart"
                    //sh "${WORKSPACE}/build.sh --pack_helm --push_helm --helm_repo ${HELM_REPO} --helm_usr ${HELM_USR} --helm_psw ${HELM_PSW}"
                }
            }
    
            ////////// Step 4 //////////
            stage('Deploy to test') {
                steps {
                    //container('docker') {
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
                    //}
                }
            }
    
            // Run the 3 tests on the deployed Kubernetes pod and service
//            stage('Test tests') {
//                parallel {
//                    stage('Curl http_code') {
//                        steps {
//                            curlTest (namespace, 'http_code')
//                        }
//                    }
//                    stage('Curl total_time') {
//                        steps {
//                            curlTest (namespace, 'time_total')
//                        }
//                    }
//                    stage('Curl size_download') {
//                        steps {
//                            curlTest (namespace, 'size_download')
//                        }
//                    }
//                }
//            }
    
            stage('Cleanup Test') {
                steps {
                    script {
                        // Remove release if exists
                        helmDelete (namespace, "${ID}", "test")
                    }
                }
            }

            stage('Deploy to staging') {
                steps {
                    container('docker') {
                        script {
                            env = 'staging'

                            echo "Deploying application ${ID} to ${env} kubernetes cluster "
                            // createNamespace (namespace)

                            docker.image("kiwigrid/gcloud-kubectl-helm").inside("-w /workspace -v \${PWD}:/workspace -it") {
                                downloadFile("k8s/configs/${env}/kubeconfig-labs-createstudio-${env}_environment", 'createstudio_ci_cd')
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
    
            // Run the 3 tests on the deployed Kubernetes pod and service
//            stage('Staging tests') {
//                parallel {
//                    stage('Curl http_code') {
//                        steps {
//                            curlTest (namespace, 'http_code')
//                        }
//                    }
//                    stage('Curl total_time') {
//                        steps {
//                            curlTest (namespace, 'time_total')
//                        }
//                    }
//                    stage('Curl size_download') {
//                        steps {
//                            curlTest (namespace, 'size_download')
//                        }
//                    }
//                }
//            }
    
            stage('Cleanup staging') {
                steps {
                    script {
                        // Remove release if exists
                        helmDelete (namespace, "${ID}")
                    }
                }
            }
    
            ////////// Step 6 //////////
            // Waif for user manual approval, or proceed automatically if DEPLOY_TO_PROD is true
            stage('Go for Production?') {
                when {
                    allOf {
                        environment name: 'GIT_BRANCH', value: 'master'
                        environment name: 'DEPLOY_TO_PROD', value: 'false'
                    }
                }
    
                steps {
                    // Prevent any older builds from deploying to production
                    milestone(1)
                    input 'Proceed and deploy to Production?'
                    milestone(2)
    
                    script {
                        DEPLOY_PROD = true
                    }
                }
            }
    
            stage('Deploy to Production') {
                when {
                    anyOf {
                        expression { DEPLOY_PROD == true }
                        environment name: 'DEPLOY_TO_PROD', value: 'true'
                    }
                }
                steps {
                    container('docker') {
                        script {
                            DEPLOY_PROD = true
                            env = 'production'

                            echo "Deploying application ${ID} to ${environment} kubernetes cluster "
                            // createNamespace (namespace)

                            docker.image("kiwigrid/gcloud-kubectl-helm").inside("-w /workspace -v \${PWD}:/workspace -it") {
                                downloadFile("k8s/configs/${env}/kubeconfig-labs-createstudio-${env}_environment", 'createstudio_ci_cd')
                                sh("helm repo add chartmuseum ${HELM_REPO}")
                                sh("helm repo add chartmuseum https://chartmuseum.internal.unity3d.com")
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
    
//                steps {
//                    script {
//                        DEPLOY_PROD = true
//                        namespace = 'production'
//    
//                        echo "Deploying application ${IMAGE_NAME}:${DOCKER_TAG} to ${namespace} namespace"
//                        createNamespace (namespace)
//    
//                        // Deploy with helm
//                        echo "Deploying"
//                        helmInstall (namespace, "${ID}")
//                    }
//                }
            }
    
            // Run the 3 tests on the deployed Kubernetes pod and service
//            stage('Production tests') {
//                when {
//                    expression { DEPLOY_PROD == true }
//                }
//    
//                parallel {
//                    stage('Curl http_code') {
//                        steps {
//                            curlTest (namespace, 'http_code')
//                        }
//                    }
//                    stage('Curl total_time') {
//                        steps {
//                            curlTest (namespace, 'time_total')
//                        }
//                    }
//                    stage('Curl size_download') {
//                        steps {
//                            curlTest (namespace, 'size_download')
//                        }
//                    }
//                }
//            }
        }
    }
}

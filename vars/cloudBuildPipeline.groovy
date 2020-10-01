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
            timeout(time: 180, unit: 'MINUTES')
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
            booleanParam (name: 'BUMP_PATCH', defaultValue: false, description: 'Bump Minor Semver')
        }

        agent any

        // Pipeline stages
        stages {
            ////////// Step 1 //////////
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
                                    //println(manifestDateCheckPre)
                                    VERSION = IncrementVersion()
                                    //echo "Version is ${VERSION}"
                                }
                            }
                        }
                    }
                }
            }
            ////////// Step 4 //////////
            stage('Build Project') {
                steps {
                    dir("${PROJECT_DIR}") {
                        container('docker') {
                            script {
                                // Kill container in case there is a leftover
                                sh "[ -z \"\$(docker ps -a | grep ${SERVICE_NAME} 2>/dev/null)\" ] || docker rm -f ${SERVICE_NAME}"
                                echo "Building application and Docker image"
                                // Check if VERSION var is set from Step 3
                                if (binding.hasVariable('VERSION')) {
                                    myapp = sh("docker build -t ${DOCKER_REG}/${SERVICE_NAME}:${BRANCH}-${VERSION} . || errorExit \"Building ${SERVICE_NAME} failed\"")
                                    echo "Starting ${SERVICE_NAME} container"
                                    sh "docker run --detach --name ${SERVICE_NAME} --rm --publish ${TEST_LOCAL_PORT}:80 ${DOCKER_REG}/${SERVICE_NAME}:${BRANCH}-${VERSION}"
                                } else {
                                    myapp = sh("docker build -t ${DOCKER_REG}/${SERVICE_NAME} . || errorExit \"Building ${SERVICE_NAME} failed\"")
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
            stage('Test Project') {
                environment {
                    home = "${WORKSPACE}"
                    // Connection string for PSQL docker container
                    ConnectionStrings__default = "Host=localhost;Database=createdataservice_test;Username=postgres;Password=Aa123456"
                    ASPNETCORE_ENVIRONMENT = "Testing"
                    Cloud__GCP__Storage__BucketName = "test-bucket"
                }
                steps {
                    dir("${PROJECT_DIR}") {
                        container('docker') {
                            script {
                                sh("apk update")
                                sh("apk add docker-compose")
                                sh("docker-compose -f docker/docker-compose.test.yml up -d db")
                                docker.image('mcr.microsoft.com/dotnet/core/sdk:3.1').inside("-w /workspace -v ${PWD}:/workspace -v /var/run/docker.sock:/var/run/docker.sock --network container:Psql -u 1000 -it") {
                                    //runTests = sh(returnStdout: true, script: "dotnet test --logger \"trx;LogFileName=results.trx\"")
                                    sh("dotnet test --logger \"trx;LogFileName=results.trx\"")
                                } 
                                // Sidecar containers should work, but for some reason it's not. Will leave this here for future work.
                                // https://www.jenkins.io/doc/book/pipeline/docker/#running-sidecar-containers
                                /*docker.image('postgres:12').withRun("-e PGPASSWORD=Aa123456 -e POSTGRES_DB=createdataservice_test") { c ->
                                    docker.image('postgres:12').inside("--link ${c.id}:pg") {
                                        sh """
                                            until ! pg_isready
                                            do
                                              echo "Waiting for PostgreSQL..."
                                              sleep 5
                                            done
                                            sleep 2
                                        """
                                    }
                                    docker.image("mcr.microsoft.com/dotnet/core/sdk:3.1").inside("--link ${c.id}:pg") {
                                        sh("dotnet test")
                                    }
                                }*/
                            }
                        }
                    }
                }
                post {
                    always {
                        xunit (
                            thresholds: [failed(unstableThreshold: '2')],
                            tools: [MSTest(deleteOutputFiles: true, failIfNotNew: true, pattern: '**/*.trx', skipNoTestFiles: false, stopProcessingIfError: true)]
                        )
                        //step([$class: 'MSTestPublisher', testResultsFile:"**/*.trx", failOnError: true, keepLongStdio: true])
                        echo 'Publishing Test Results!'
                    }
                }
            }
            ////////// Step 5 //////////
            stage('Publish Docker and Helm') {
                environment {
                    // Pulled from Step 3
                    CURRENT_VERSION = "${VERSION}"
                    GOOGLE_APPLICATION_CREDENTIALS = credentials('sa-createstudio-jenkins')
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
                            PackageHelmChart(chartDir: "./helm")
                            // Bug in UploadHelm, doesn't actually take extra params properly
                            //, extraParams: "--version ${CURRENT_VERSION} --app-version ${CURRENT_VERSION}")
                            echo "Pushing helm chart"
                            UploadHelm(chartDir: "./helm")
                            echo "Pushing Docker chart"
                            DockerPush(gkeStrCredsID: 'sa-gcp-jenkins')
                        }
                    }
                }
            }
            ////////// Step 7 //////////
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
                                    println(manifestDateCheckPre)
                                    println(manifestDateCheckPost)
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
            ////////// Step 6 //////////
            stage('Deploy to TEST') {
                environment {
                    home = "${WORKSPACE}"
                }
                when {
                    anyOf {
                        expression { BRANCH_NAME ==~ /(main|staging|develop)/ }
                    }
                }
                steps {
                    dir("${PROJECT_DIR}") {
                        script {
                            env = 'test'
                            echo "Deploying application ${ID} to ${env} kubernetes cluster "
                            downloadFile("k8s/configs/${env}/kubeconfig-labs-createstudio-${env}_environment", 'createstudio_ci_cd')
                            KUBE_CNF = "k8s/configs/${env}/kubeconfig-labs-createstudio-${env}_environment"
                            ApplyHelmChart(releaseName: "${ID}", chartName: "${SERVICE_NAME}", chartValuesFile: "helm/values.yaml", extraParams: "--kubeconfig ${KUBE_CNF} --namespace ${namespace} --set image.repository=${DOCKER_REG}/${SERVICE_NAME} --set image.tag=${BRANCH}-${VERSION}")
                        }
                    }
                }
            }
        }
        post {
            always {
                echo 'One way or another, I have finished'
            }
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

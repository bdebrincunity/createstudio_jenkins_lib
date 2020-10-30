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

    /*
        This is the main pipeline section with the stages of the CI/CD
     */
    pipeline {

        options {
            // Build auto timeout
            timeout(time: 300, unit: 'MINUTES')
            ansiColor('xterm')
        }

        // Some global default variables
        environment {
            SERVICE_NAME = "${pipelineParams.SERVICE_NAME}"
            SLACK_CHANNEL = "${pipelineParams.SLACK_CHANNEL}"
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
            booleanParam (name: 'BUMP_PATCH', defaultValue: false, description: 'Bump Minor Semver')
            booleanParam (name: 'DEBUG', defaultValue: false, description: 'Turn on debugging and verbose output where enabled')
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
                        last_started = getCurrentStage()
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
                            last_started = getCurrentStage()
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
                                last_started = getCurrentStage()
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
            ////////// Step 4 //////////
            stage('Build Project') {
                steps {
                    dir("${PROJECT_DIR}") {
                        container('docker') {
                            script {
                                last_started = getCurrentStage()
                                echo "Building application and Docker image"
                                if (binding.hasVariable('VERSION')) {
                                    sh("docker rm -f ${DOCKER_REG}/${SERVICE_NAME}:${BRANCH}-${VERSION} || true")
                                    docker.build("${DOCKER_REG}/${SERVICE_NAME}:${BRANCH}-${VERSION}", "-f Dockerfile .")
                                    myContainer = docker.image("${DOCKER_REG}/${SERVICE_NAME}:${BRANCH}-${VERSION}")
                                    //echo "Starting ${SERVICE_NAME} container"
                                    //sh "docker run --detach --name ${SERVICE_NAME} --rm --publish ${TEST_LOCAL_PORT}:80 ${DOCKER_REG}/${SERVICE_NAME}:${BRANCH}-${VERSION}"
                                } else {
                                    sh("docker rm -f ${DOCKER_REG}/${SERVICE_NAME}-${BRANCH} || true")
                                    docker.build("${DOCKER_REG}/${SERVICE_NAME}-${BRANCH}", "-f Dockerfile .")
                                    myContainer = docker.image("${DOCKER_REG}/${SERVICE_NAME}-${BRANCH}:latest")
                                    //echo "Starting ${SERVICE_NAME} container"
                                    //sh "docker run --detach --name ${SERVICE_NAME} --rm --publish ${TEST_LOCAL_PORT}:80 ${DOCKER_REG}/${SERVICE_NAME}"
                                }
                                host_ip = sh(returnStdout: true, script: '/sbin/ip route | awk \'/default/ { print $3 ":${TEST_LOCAL_PORT}" }\'')
                            }
                        }
                    }
                }
            }
            stage('Test Project') {
                environment {
                    home = "${WORKSPACE}"
                    ConnectionStrings__default = "Host=${SERVICE_NAME}-${BRANCH};Database=createdataservice_test;Username=postgres;Password=Aa123456"
                    ASPNETCORE_ENVIRONMENT = "Testing"
                    Cloud__GCP__Storage__BucketName = "test-bucket"
                }
                steps {
                    dir("${PROJECT_DIR}") {
                        container('docker') {
                            script {
                                last_started = getCurrentStage()
                                def myDbContainer = "${SERVICE_NAME}-${BRANCH}-db"
                                sh("docker rm -f  ${myDbContainer} || true")
                                sh("docker run -d -e 'POSTGRES_PASSWORD=Aa123456' -e 'POSTGRES_DB=createdataservice_test' -it --name ${myDbContainer} postgres:12")
                                docker.image('mcr.microsoft.com/dotnet/core/sdk:3.1-alpine3.12').inside("--network container:${myDbContainer} -w /workspace -v ${PWD}:/workspace -u 1000 -it") {
                                    sh("dotnet test --logger \"trx;LogFileName=results.trx\" --blame")
                                }
                                // https://www.jenkins.io/doc/book/pipeline/docker/#running-sidecar-containers
                                // keep getting connection refused.......
                                /*docker.image('postgres:12').withRun("-e POSTGRES_PASSWORD=Aa123456 -e POSTGRES_DB=createdataservice_test -it") { c ->
                                    docker.image('postgres:12').inside("--link ${c.id}:psql -v /var/run/postgresql:/var/run/postgresql -p 5432:5432 -it") {
                                        // doesnt work, I think its due to running docker in docker and is reading psql from the host and not container
                                        sh """
                                            until ! pg_isready
                                            do
                                              echo "Waiting for PostgreSQL..."
                                              sleep 5
                                            done
                                            sleep 2
                                        """
                                    }
                                    // Need to switch this to the actual built container from above
                                    //myContainer.inside("--link ${c.id}:psql -it") {
                                    docker.image('mcr.microsoft.com/dotnet/core/sdk:3.1-alpine3.12').inside("--link ${c.id}:psql -w /workspace -v ${PWD}:/workspace -it") {
                                        sh("dotnet test --logger \"trx;LogFileName=results.trx\"")
                                    }
                                }*/
                            }
                        }
                    }
                }
                post {
                    always {
                        echo 'Publishing Test Results!'
                        xunit (
                            thresholds: [failed(failureThreshold: '0', unstableThreshold: '2')],
                            tools: [MSTest(deleteOutputFiles: true, failIfNotNew: true, pattern: '**/*.trx', skipNoTestFiles: false, stopProcessingIfError: true)]
                        )
                        //step([$class: 'MSTestPublisher', testResultsFile:"**/*.trx", failOnError: true, keepLongStdio: true])
                    }
                }
            }
            stage('Perform Static Analysis') {
                environment {
                    SONARQUBE_PRODUCTION_ACCESS_TOKEN = credentials('SONARQUBE_PRODUCTION_ACCESS_TOKEN')
                    SONARQUBE_STAGING_ACCESS_TOKEN    = credentials('SONARQUBE_STAGING_ACCESS_TOKEN')
                }
                steps {
                    dir("${PROJECT_DIR}") {
                        container('docker') {
                            script {
                                if (pipelineParams.SONARQUBE_SCANNER.toLowerCase() == "msbuild") { // If yes, run the container.
                                    // Identify environment (staging|production) and project
                                    def docker_params = " -e SONARQUBE_ENVIRONMENT=${pipelineParams.SONARQUBE_ENVIRONMENT}  -e SONARQUBE_PROJECT_KEY=${pipelineParams.SONARQUBE_PROJECT_KEY}"

                                    // Only set the tokens if they are not null.
                                    if (env.SONARQUBE_PRODUCTION_ACCESS_TOKEN?.trim()) {
                                        docker_params += " -e SONARQUBE_PRODUCTION_ACCESS_TOKEN=${env.SONARQUBE_PRODUCTION_ACCESS_TOKEN}"
                                    }
                                    if (env.SONARQUBE_STAGING_ACCESS_TOKEN?.trim()) {
                                        docker_params += " -e SONARQUBE_STAGING_ACCESS_TOKEN=${env.SONARQUBE_STAGING_ACCESS_TOKEN}"
                                    }

                                    // Check PR/branch variables and set the ones we actually have.
                                    if (env.CHANGE_ID.trim()) {
                                        docker_params += " -e CHANGE_ID=${CHANGE_ID}"
                                    }
                                    if (env.BRANCH_NAME.trim()) {
                                        docker_params += " -e BRANCH_NAME=${BRANCH_NAME}"
                                    }
                                    if (env.CHANGE_TARGET.trim()) {
                                        docker_params += " -e CHANGE_TARGET=${CHANGE_TARGET}"
                                    }
                                    if (env.CHANGE_BRANCH.trim()) {
                                        docker_params += " -e CHANGE_BRANCH=${CHANGE_BRANCH}"
                                    }

                                    // Be more verbose if we're debugging.
                                    if (pipelineParams.DEBUG == true) {
                                        echo "*************** Using SonarQube for MSBuild. ***************"
                                        echo "Pipeline parameters: ${pipelineParams}"
                                        echo "Workspace directory: ${WORKSPACE}"
                                        docker_params += " -e DEBUG=true"
                                    }

                                    // This is where the magic happens -- see createstudio_orchestration repo /docker/SonarQube-MSBuild
                                    docker.image("gcr.io/unity-labs-createstudio-test/sonarqube-msbuild").inside("${docker_params}") {
                                        sh "/sq-scan.sh"
                                    }
                                } else {
                                    echo "Not using MSBuild, so not analyzing at this point."
                                    // This may change if someone figures out how to run SQ on Unity builds
                                }
                            } // script
                        } // container('docker')
                    } // dir("...")
                } // steps
            } // stage
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
                            last_started = getCurrentStage()
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
                                last_started = getCurrentStage()
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
                    ConnectionStrings__default = "Host=localhost;Database=createdataservice_develop;Username=cs;Password=@Mn%50dvKngB@sEu"
                    ASPNETCORE_ENVIRONMENT = "Testing"
                    Cloud__GCP__Storage__BucketName = "test-bucket"
                }
                when {
                    anyOf {
                        expression { BRANCH_NAME ==~ /(main|staging|develop)/ }
                    }
                }
                steps {
                    dir("${PROJECT_DIR}") {
                        script {
                            last_started = getCurrentStage()
                            env = 'test'
                            echo "Deploying application ${ID} to ${env} kubernetes cluster "
                            downloadFile("k8s/configs/${env}/kubeconfig-labs-createstudio-${env}_environment", 'createstudio_ci_cd')
                            KUBE_CNF = "k8s/configs/${env}/kubeconfig-labs-createstudio-${env}_environment"
                            // GOOGLE APPLICATION CREDENTIALS to access ALL buckets
                            withCredentials([string(credentialsId: 'sa-gcp-buckets', variable: "GC_KEY")]) {
                                GAC_KEY = sh(returnStdout: true, script: 'echo \"${GC_KEY}\" | base64 -w 0').trim()
                            }
                            // Service account to access Cloud SQL
                            withCredentials([string(credentialsId: 'sa-gcp-sql', variable: "GC_KEY")]) {
                                PROXY_KEY = sh(returnStdout: true, script: 'echo \"${GC_KEY}\" | base64 -w 0').trim()
                            }
                            ApplyHelmChart(
                                releaseName: "${ID}",
                                chartName: "${SERVICE_NAME}",
                                chartValuesFile: "helm/values.yaml",
                                extraParams: """--wait --timeout 30s --atomic --kubeconfig ${KUBE_CNF} --namespace ${namespace} \
                                    --set image.repository=${DOCKER_REG}/${SERVICE_NAME} \
                                    --set image.tag=${BRANCH}-${VERSION} \
                                    --set 'env.open.ASPNETCORE_ENVIRONMENT=${ASPNETCORE_ENVIRONMENT}' \
                                    --set 'env.open.ConnectionStrings__default=${ConnectionStrings__default}' \
                                    --set 'env.open.Cloud__GCP__Storage__BucketName=${Cloud__GCP__Storage__BucketName}' \
                                    --set 'env.open.Cloud__GCP__Storage__JsonServiceAccountKey=${GAC_KEY}' \
                                    --set 'env.secrets.GOOGLE_APPLICATION_CREDENTIALS=${GAC_KEY}' \
                                    --set 'env.secrets.PSQL_PROXY_CREDENTIALS=${PROXY_KEY}' \
                                """
                            )
                        }
                    }
                }
            }
        }
        post {
            always {
                echo 'One way or another, I have finished'
                //archiveArtifacts allowEmptyArchive: true, artifacts: "**/*.log", fingerprint: true, followSymlinks: false
                SendSlack("${currentBuild.currentResult}", "${last_started}")
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

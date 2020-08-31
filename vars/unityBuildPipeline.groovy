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
        }

        // Some global default variables
        environment {
            IMAGE_NAME = "${pipelineParams.SERVICE_NAME}"
            PROJECT_TYPE = "${pipelineParams.PROJECT_TYPE}"
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
    //        string (name: 'GIT_BRANCH',           defaultValue: 'main',  description: 'Git branch to build')
            booleanParam (name: 'DEPLOY_TO_PROD', defaultValue: false,     description: 'If build and tests are good, proceed and deploy to production without manual approval')
        }

        agent any
        // Pipeline stages
        stages {

            ////////// Step 1 //////////
            stage('Update SCM') {
                steps {
                    
                    echo "DOCKER_REG is ${DOCKER_REG}"
                    echo "HELM_REPO  is ${HELM_REPO}"

                    // Define a unique name for the tests container and helm release
                    container('docker') {
                        script {
                            branch = GIT_BRANCH.replaceAll('/', '-').replaceAll('\\*', '-')
                            NAME_ID = "${IMAGE_NAME}-${BRANCH_NAME}"
                            ID = NAME_ID.toLowerCase().replaceAll("_", "-").replaceAll('/', '-')
                            echo "Global ID set to ${ID}"
                            docker.image("jgpelaez/git-lfs").inside("-w /workspace -v \${PWD}:/workspace -it") {
                                sh("GIT_TRACE=1 git lfs init")
                                sh("GIT_TRACE=1 git lfs pull")
                            }
                        }
                   }
                }
            }
            stage('Create Unity Build') {
                steps {
                    dir("${PROJECT_DIR}") {
                        container('docker') {
                            script {
                                echo "Build ${PROJECT_TYPE} - ${ID}"
                                withCredentials([
                                    [$class: 'UsernamePasswordMultiBinding', credentialsId:'unity_pro_login', usernameVariable: 'UNITY_USERNAME', passwordVariable: 'UNITY_PASSWORD'],
                                    [$class: 'StringBinding', credentialsId: 'unity_pro_license_content', variable: 'UNITY_LICENSE_CONTENT'],
                                    [$class: 'StringBinding', credentialsId: 'unity_pro_serial', variable: 'UNITY_SERIAL']
                                ]){
                                    docker.image("gableroux/unity3d:2019.4.3f1-${PROJECT_TYPE}").inside("-w /workspace -v \${PWD}:/workspace -it") {
                                        // Will try to get this to work in the future with a library resource
//                                        withEnv(["PROJECT_TYPE=${PROJECT_TYPE}", "UNITY_SERIAL=${UNITY_SERIAL}", "UNITY_USERNAME=${UNITY_USERNAME}", "UNITY_PASSWORD=${UNITY_PASSWORD}", "UNITY_LICENSE_CONTENT=${UNITY_LICENSE_CONTENT}"]){
//                                            sh("${build_script}")
                                       sh("files/build.sh ${PROJECT_TYPE}")
                                       project = sh(returnStdout: true, script: "find . -maxdepth 1 -type d | grep ${PROJECT_TYPE} | tr -d './'").trim()
                                       echo ("${project}")
                                       sh("ls -la")
                                       archiveArtifacts allowEmptyArchive: true, artifacts: "${project}/", fingerprint: true, followSymlinks: false
                                    }
                                }
                            }
                        }
                    }
                }
            }
            ////////// Step 2 //////////
            stage('Deploy Build to test') {
                environment {
                    home = "${WORKSPACE}" // Needed so that AuthenticateGCloud
                }
                when {
                    expression { "${PROJECT_TYPE}" == 'webgl' }
                }
                steps {
                    dir("${PROJECT_DIR}") {
                        container('docker') {
                            script {
                                //AuthenticateGCP(gkeStrCredsID: "sa-gcp-jenkins")
                                withCredentials([string(credentialsId: 'sa-gcp-jenkins', variable: "GC_KEY")]) {
                                  sh 'echo "$GC_KEY" | tee key.json'
                                }
                                // This needs to be a bit simpler eventually.....
                                docker.image("gcr.io/unity-labs-createstudio-test/base_tools").inside("-w /workspace -v \${PWD}:/workspace -v /var/run/docker.sock:/var/run/docker.sock -it") {
                                    sh """
                                      gcloud auth activate-service-account --key-file=key.json
                                      yes | gcloud auth configure-docker
                                      docker stop ${ID} || true
                                      docker rm ${ID} || true
                                      docker create --name ${ID} -w /${PROJECT_TYPE} nginx:stable
                                      docker cp files/webgl.conf ${ID}:/etc/nginx/conf.d/
                                      docker start ${ID}
                                      docker exec ${ID} rm -f /etc/nginx/conf.d/default.conf
                                      docker stop ${ID}
				      docker cp ${project}/. ${ID}:/${PROJECT_TYPE}
                                      docker commit ${ID} gcr.io/unity-labs-createstudio-test/${ID}
                                      docker push gcr.io/unity-labs-createstudio-test/${ID}
                                   """
                                }
                            }
                        }
                    }
                }
            }
        }
/*        post {
            always {
                // Dangling containers
                sh 'docker ps -q -f status=exited | xargs --no-run-if-empty docker rm'
                // Dangling Images
                sh 'docker images -q -f dangling=true | xargs --no-run-if-empty docker rmi'
                // Dangling Volumes
                sh 'docker volume ls -qf dangling=true | xargs -r docker volume rm'
                // Prune
                sh 'docker system prune -af --volumes'
            }
        }*/
    }
}

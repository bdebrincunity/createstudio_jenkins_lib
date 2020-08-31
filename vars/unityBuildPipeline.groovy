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
            IMAGE_NAME = "${pipelineParams.SERVICE_NAME}"
            PROJECT_TYPE = "${pipelineParams.PROJECT_TYPE}"
            TEST_MY_LIST = "${pipelineParams.test_list}"
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
            //GIT_LFS_SKIP_SMUDGE = 1
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
                    script {
                        branch = GIT_BRANCH.replaceAll('/', '-').replaceAll('\\*', '-')
                        NAME_ID = "${IMAGE_NAME}-${BRANCH_NAME}"
                        ID = NAME_ID.toLowerCase().replaceAll("_", "-").replaceAll('/', '-')
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
            // Parallel builds work, but share workspace which doesn't play nice with unity
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
                                //AuthenticateGCP(gkeStrCredsID: 'sa-gcp-jenkins')
                                withCredentials([
                                    [$class: 'UsernamePasswordMultiBinding', credentialsId:'unity_pro_login', usernameVariable: 'UNITY_USERNAME', passwordVariable: 'UNITY_PASSWORD'],
                                    [$class: 'StringBinding', credentialsId: 'unity_pro_license_content', variable: 'UNITY_LICENSE_CONTENT'],
                                    [$class: 'StringBinding', credentialsId: 'unity_pro_serial', variable: 'UNITY_SERIAL']
                                ]){
                                    docker.image("gableroux/unity3d:2019.4.3f1-${type}").inside("-w /workspace -v \${PWD}:/workspace -it") {
                                        sshagent (credentials: ['ssh_createstudio']) {
                                            sh("files/build.sh ${type}")
                                        }
                                        project = sh(returnStdout: true, script: "find . -maxdepth 1 -type d | grep osx | sed -e 's/\\.\\///g'").trim()
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
                                AuthenticateGCP(gkeStrCredsID: 'sa-gcp-jenkins')
                                withCredentials([
                                    [$class: 'UsernamePasswordMultiBinding', credentialsId:'unity_pro_login', usernameVariable: 'UNITY_USERNAME', passwordVariable: 'UNITY_PASSWORD'],
                                    [$class: 'StringBinding', credentialsId: 'unity_pro_license_content', variable: 'UNITY_LICENSE_CONTENT'],
                                    [$class: 'StringBinding', credentialsId: 'unity_pro_serial', variable: 'UNITY_SERIAL']
                                ]){
                                    docker.image("gableroux/unity3d:2019.4.3f1-${type}").inside("-w /workspace -v \${PWD}:/workspace -it") {
                                        sshagent (credentials: ['ssh_createstudio']) {
                                            sh("files/build.sh ${type}")
                                        }
                                        project = sh(returnStdout: true, script: "find . -maxdepth 1 -type d | grep osx | sed -e 's/\\.\\///g'").trim()
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
                    home = "${WORKSPACE}" // Needed so that AuthenticateGCloud
                }
                when {
                    expression { "${PROJECT_TYPE}".contains('webgl') }
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
        post {
            //always {
            //    echo 'One way or another, I have finished'
            //    deleteDir() /* clean up our workspace */
            //}
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

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

    pipeline {
      agent {
        kubernetes {
          label 'jenkins-ci-test'
          defaultContainer 'jnlp'
          yaml """
    apiVersion: v1
    kind: Pod
    metadata:
      name: jenkins-ci-maven
    spec:
      containers:
      - name: maven
        image: maven:3-alpine
        command:
        - cat
        tty: true
      - name: openjdk
        image: openjdk:8-jre
        command:
        - cat
        tty: true
      - name: webgl
        image: gableroux/unity3d:2019.4.3f1-webgl
        command:
        - cat
        tty: true
    """
        }
      }
      stages {
        stage('Maven') {
          steps {
            container ("maven") {
              echo 'Hello, Maven'
              sh 'mvn --version'
            }
          }
        }
        stage('JDK') {
          steps {
            container ("openjdk") {
              echo 'Hello, JDK'
              sh 'java -version'
            }
          }
        }
        stage('Unity') {
          steps {
            dir("${PROJECT_DIR}") {
              container ("webgl") {
                echo 'Hello, Unity'
                sh 'ls -la /opt'
                sh 'files/build.sh webgl'
              }
            }
          }
        }
      }
    }
}

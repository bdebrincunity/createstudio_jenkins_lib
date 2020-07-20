def call () {
    withCredentials([file(credentialsId: registryCredential, variable: 'GC_KEY')]) {
        sh '''
            echo "$GC_KEY" | tee key.json
            gcloud auth activate-service-account --key-file="${GC_KEY}"
            yes | gcloud auth configure-docker
        '''
        echo "Pushing image To GCR"
        docker.image("${DOCKER_REG}/${IMAGE_NAME}").push("${BuildID}")
        docker.image("${DOCKER_REG}/${IMAGE_NAME}").push("latest")
        //app.push("${BuildID}")
        //myapp.push("${VERSION}")
        //app.push("latest")
    }
}

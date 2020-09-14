package com.createstudio

class DockerUtilities {

    static pushImage(context) {
        context.echo "Pushing ${context.env.image.replace("%2F", "-")}..."
        context.container("docker") {
            context.sh("""
                set +x
                docker login -u _json_key -p \"\$(cat key.json)\" https://gcr.io
                set -x
                """)
            context.sh("docker push ${context.env.image.replace("%2F", "-")}")
        }
    }
}

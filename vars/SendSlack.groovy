def call(def buildStatus) {


    def getLastCommitMessage = {
        last_commit = sh(returnStdout: true, script: 'git log -1 --pretty=%B').trim()
    }

    def getGitAuthor = {
        commit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
        author = sh(returnStdout: true, script: "git --no-pager show -s --format='%an' ${commit}").trim()
        author_email = sh(returnStdout: true, script: "git --no-pager show -s --format='%ae' ${commit}").trim()
    }

    getLastCommitMessage()
    getGitAuthor()
    // set default of build status
    buildStatus =  buildStatus ?: 'STARTED'
    // define our colous based on build status
    def colorMap = [ 'STARTED': '#F0FFFF', 'SUCCESS': '#008B00', 'UNSTABLE': '#FFFE89', 'FAILURE': '#FF0000' ]
    // Lookup user ids from changeset commit authors
    // https://github.com/jenkinsci/slack-plugin#user-id-look-up
    // for some reason it does not work though.... Saving for future use
    //def userIds = slackUserIdsFromCommitters()
    //def userIdsString = userIds.collect { "<@$it>" }.join(' ')
    def userId = slackUserIdFromEmail(author_email)

    withEnv([
            "dir=${sh([returnStdout: true, script: 'echo ${PROJECT_DIR}']).trim()}",
            "stage_name=${sh([returnStdout: true, script: 'echo ${last_started}']).trim()}",
    ]) {
        // build out message
        def msg = """BuildStatus: *${buildStatus}*\n \
                     Stage: *${STAGE_NAME}*\n \
                     Project: *${env.SERVICE_NAME}*\n \
                     BuildNumber: *${env.BUILD_NUMBER}*\n \
                     URL: ${env.BUILD_URL}\n \
                     Author: <@${userId}>\n \
                     Changes: ```${last_commit}```\n \
                     CommitID: `${commit}`\n
                  """
        // get our colors
        def colorName = colorMap[buildStatus]
        // send slack message based on above criteria
        def slackResponse = slackSend(color: colorName, message: "${msg}", notifyCommitters: true)

        def files = findFiles(glob: "${dir}/unity-build-player*.log")
        if (buildStatus == 'UNSTABLE' || buildStatus == 'FAILURE') {
            files.each { file ->
                slackUploadFile(channel: slackResponse.threadId, filePath: file.path, initialComment: "Attaching " + file.name + " to give you some context")
            }
        } else {
            slackResponse
        }
    }
}

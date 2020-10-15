def call(def buildStatus, def stageId) {


    def getLastCommitMessage = {
        last_commit = sh(returnStdout: true, script: 'git log -1 --pretty=%B').trim()
    }

    def getGitAuthor = {
        commit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
        author = sh(returnStdout: true, script: "git --no-pager show -s --format='%an' ${commit}").trim()
        //author_email = sh(returnStdout: true, script: "git --no-pager show -s --format='%ae' ${commit}").trim()
        author_email = sh(returnStdout: true, script: "git --no-pager show -s --format='%ae'").trim()
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
    println "author: ${author} , author_email: ${author_email}"
    def userId = slackUserIdFromEmail(author_email)
    // build out message
    def msg = "BuildStatus: *${buildStatus}*\nStage: *${stageId}*\nProject: *${env.SERVICE_NAME}*\nBuildNumber: *${env.BUILD_NUMBER}*\nURL: ${env.BUILD_URL}\nAuthor: <@${userId ?: 'git email not set'}>\nChanges: ```${last_commit}```\nCommitID: `${commit}`"
    // get our colors
    def colorName = colorMap[buildStatus]
    // send slack message based on above criteria
    def slackResponse = slackSend(color: colorName, message: "${msg}", notifyCommitters: true)

    withEnv([
            "dir=${sh([returnStdout: true, script: 'echo ${PROJECT_DIR}']).trim()}",
    ]) {
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

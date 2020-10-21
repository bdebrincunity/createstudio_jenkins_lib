@NonCPS
def reportOnTestsForBuild() {
  def build = manager.build
  println("Build Number: ${build.number}")
  if (build.getAction(hudson.tasks.junit.TestResultAction.class) == null) {
    println("No tests")
    return ("No Tests")
  }

  // The string that will contain our report.
  String testReport;

  testReport = "URL: ${env.BUILD_URL}\n"

  def testResults =    build.getAction(hudson.tasks.junit.TestResultAction.class).getFailCount();
  def failed = build.getAction(hudson.tasks.junit.TestResultAction.class).getFailedTests()
  println("Failed Count: ${testResults}")
  println("Failed Tests: ${failed}")
  def failures = [:]

  def result = build.getAction(hudson.tasks.junit.TestResultAction.class).result

  if (result == null) {
    testReport = testReport + "No test results"
  } else if (result.failCount < 1) {
    testReport = testReport + "No failures"
  } else {
    testReport = testReport + "overall fail count: ${result.failCount}\n\n"
  failedTests = result.getFailedTests();

  failedTests.each { test ->
    failures.put(test.fullDisplayName, test)
    testReport = testReport + "\n-------------------------------------------------\n"
    testReport = testReport + "Failed test: ${test.fullDisplayName}\n" +
    "name: ${test.name}\n" +
    "age: ${test.age}\n" +
    "failCount: ${test.failCount}\n" +
    "failedSince: ${test.failedSince}\n" +
    "errorDetails: ${test.errorDetails}\n"
    }
  }
  return (testReport)
}

def call(def buildStatus, def stageId) {


    def getLastCommitMessage = {
        last_commit = sh(returnStdout: true, script: "git log remotes/origin/${env.BRANCH_NAME} -1 --pretty=format:'%h - %an, %ar : %B'").trim()
    }

    def getGitAuthor = {
        commit = sh(returnStdout: true, script: "git rev-parse remotes/origin/${env.BRANCH_NAME}").trim()
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
    sh """
        git config user.name ${author}
        git config user.email ${author_email}
    """
    def userIds = slackUserIdsFromCommitters()
    def userIdsString = userIds.collect { "<@$it>" }.join(' ')
    println "author: ${author} , author_email: ${author_email}"
    println "userID's ${userIdsString} or ${userIds}"
    def userId = slackUserIdFromEmail(author_email)
    // build out message, need to sort out 
    if (buildStatus == 'SUCCESS') {
        // overside stageID on successfule build
        stage = 'All Stages Passed'
    } else {
        stage = stageId
    }

    def msg = "BuildStatus: *${buildStatus}*\nStage: *${stage}*\nProject: *${env.SERVICE_NAME}*\nBuildNumber: *${env.BUILD_NUMBER}*\nURL: ${env.BUILD_URL}\nAuthor: <@${userId ?: author}>\nLastCommit: ```${last_commit}```\nCommitID: `${commit}`"
    // get our colors
    def colorName = colorMap[buildStatus]
    // send slack message based on above criteria
    def slackResponse = slackSend(color: colorName, channel: "#${env.SLACK_CHANNEL}", message: "${msg}", notifyCommitters: true)

    // check if we are running a CORE job
    Boolean isCoreJob =  "${JOB_NAME}".contains("CORE")

    if (isCoreJob) {
        echo "We are in a CORE job"
        // Save below for future optimization, right now its just stdout
        def report = reportOnTestsForBuild()
        if (buildStatus == 'UNSTABLE' || buildStatus == 'FAILURE') {
            sh("echo \"${report}\" >> test_report.log")
            files = findFiles(glob: "**/*.log")
        }
    } else {
        echo "We are in a UNITY job"
        files = findFiles(glob: "**/unity-build-player*.log")
        println "This is the report: ${files}"
    }

    withEnv([
            "dir=${sh([returnStdout: true, script: 'echo ${PROJECT_DIR}']).trim()}",
    ]) {
        //def files = findFiles(glob: "${dir}/unity-build-player*.log")
        if (buildStatus == 'UNSTABLE' || buildStatus == 'FAILURE') {
            files.each { file ->
                slackUploadFile(channel: slackResponse.threadId, filePath: file.path, initialComment: "Attaching *" + file.name + "* to give you some context")
            }
        }
    }
}

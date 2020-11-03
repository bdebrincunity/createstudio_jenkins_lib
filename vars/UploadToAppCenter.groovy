def call(Map args = [:]) {
    Map argsDefault = [
            projectType: "",
            projectPath: "",
            distGroups: "",
    ]

    Map mergedArgs = argsDefault + args
    println "The dist groups are ${mergedArgs.distGroups}"

    def getLastCommitMessage = {
        last_commit = sh(returnStdout: true, script: "git log remotes/origin/${env.BRANCH_NAME} -1 --pretty=format:'%h - %an, %ar : %B'").trim()
    }
    getLastCommitMessage()

    def appName = "${PROJECT_ID}-${SERVICE_NAME}-${mergedArgs.projectType}"
    // Grab VERSION var, otherwise default to 0.1.0
    def buildVer = System.getenv("VERSION") ?: "0.1.0"
    def fullBuildVersion = "${BRANCH_NAME}-${buildVer}-build.${BUILD_NUMBER}"

    echo "Publishing -- ${appName} -- to AppCenter!"
    echo "Artifact to push --> ${mergedArgs.projectPath}.zip"
    echo "Version to push --> ${fullBuildVersion}"
    appCenter apiToken: APPCENTER_API_TOKEN,
        ownerName: 'Unity-Create-Studio',
        appName: "${appName}",
        pathToApp: "${mergedArgs.projectPath}.zip",
        distributionGroups: "${mergedArgs.distGroups}",
        buildVersion: "${fullBuildVersion}",
        releaseNotes: "${last_commit}"
    
    def PublicURL = "https://install.appcenter.ms/orgs/Unity-Create-Studio/apps/${appName}/distribution_groups/external"
    def finalUrl = "<" + PublicURL + "|" + "${mergedArgs.projectPath}" + ">"
    // return our formatted slack message to append to our ProjectMap
    return(finalUrl)
}

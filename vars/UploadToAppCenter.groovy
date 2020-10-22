def call(Map args = [:]) {
    Map argsDefault = [
            projectType: "",
            projectPath: "",
            distGroups: "Internal",
    ]

    Map mergedArgs = argsDefault + args

    def appName = "${PROJECT_ID}-${SERVICE_NAME}-${mergedArgs.projectType}"
    // Grab VERSION var, otherwise default to 0.1.0
    def buildVer = System.getenv("VERSION") ?: "0.1.0"
    def fullBuildVersion = "${BRANCH_NAME}-${buildVer}-build.${BUILD_NUMBER}"

    if(BRANCH_NAME ==~ /(main|staging|develop)/) {
        echo 'Publishing App to AppCenter!'
        appCenter apiToken: APPCENTER_API_TOKEN,
            ownerName: 'Unity-Create-Studio',
            appName: "${appName}",
            pathToApp: "${mergedArgs.projectPath}.zip",
            distributionGroups: "${mergedArgs.distGroups}",
            buildVersion: "${fullBuildVersion}"
    }

}

def call(Map args = [:]) {
    Map argsDefault = [
            project: "",
    ]

    Map mergedArgs = argsDefault + args

    if(BRANCH_NAME ==~ /(main|staging|develop)/) {
        echo "Zip up ${mergedArgs.project}"
        zip zipFile: "${mergedArgs.project}.zip", archive: false, dir: "${mergedArgs.project}"
        echo "Archive Artifact ${mergedArgs.project}"
        archiveArtifacts allowEmptyArchive: false, artifacts: "${mergedArgs.project}", fingerprint: true, followSymlinks: false
    }

}

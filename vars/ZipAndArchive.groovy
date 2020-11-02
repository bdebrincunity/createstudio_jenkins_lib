def call(Map args = [:]) {
    Map argsDefault = [
            project: "",
    ]

    Map mergedArgs = argsDefault + args

    echo "Zip up ${mergedArgs.project}"
    zip zipFile: "${mergedArgs.project}.zip", archive: false, dir: "${mergedArgs.project}"
    echo "Archive Artifact ${mergedArgs.project}"
    archiveArtifacts allowEmptyArchive: false, artifacts: "${mergedArgs.project}.zip", fingerprint: true, followSymlinks: false
}

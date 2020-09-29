import java.text.SimpleDateFormat
import com.createstudio.DockerUtilities

def call(Map args = [:]) {
    Map argsDefault = [
            name       : "Deploy: Increment Version",
    ]

    Map mergedArgs = argsDefault + args
    context = this

    //static downloadFile(String filename, String bucket){
    //    googleStorageDownload bucketUri: "gs://${bucket}/${filename}", credentialsId: 'sa-createstudio-buckets', localDirectory: "."
    //}

    /* Grab the current version
    * Check if we want to bump MAJOR or MINOR, if not then we will
    * always bump PATCH version
    * Then grab the new version and return to pipeline */
    //status = downloadFile("${buildManifest}", 'createstudio_ci_cd')
    
    status = googleStorageDownload bucketUri: "gs://${gcpBucketCICD}/${buildManifest}", credentialsId: 'sa-createstudio-buckets', localDirectory: "."
    def dateFormat = new SimpleDateFormat("yyyyMMddHHmm")
    def date = new Date()
    def LATEST_VERSION = sh(script: "jq '.docker.${SERVICE_NAME}[].version' ${buildManifest}| tail -1", returnStdout: true).trim()
    if ( LATEST_VERSION == "" ) {
        echo "${SERVICE_NAME} does not exist in our build manifest. Will begin with version 0.1.0-build.${BUILD_NUMBER}"
        sh ("jq '.docker += { \"${SERVICE_NAME}\": [{\"version\": \"0.1.0-build.${BUILD_NUMBER}\", \"tags\":{\"UUID\": \"${BUILD_UUID}\", \"last_build_time\": \"${date}\"}}]}' ${buildManifest} | sponge ${buildManifest}")
        LATEST_VERSION = "0.1.0-build.${BUILD_NUMBER}"
        return LATEST_VERSION
    } else {
        // Maybe we bump on certain PR merge?
        if (env.BUMP_MAJOR == true){
            version = sh(script: "semver bump major ${LATEST_VERSION}", returnStdout: true).trim()
        }
        else if (env.BUMP_MINOR  == true){
            version = sh(script: "semver bump minor ${LATEST_VERSION}", returnStdout: true).trim()
        }
        else if (env.BUMP_PATCH  == true){
            version = sh(script: "semver bump patch ${LATEST_VERSION}", returnStdout: true).trim()
        }
        // Check if we have bumped major minor or patch
        if (binding.hasVariable('version')){
            new_version = version + "-build.${BUILD_NUMBER}"
        // Otherwise app stays same version
        } else {
            new_version = "${LATEST_VERSION}-build.${BUILD_NUMBER}" 
        }
        sh ("jq '.docker.\"${SERVICE_NAME}\" += [{\"version\": \"${new_version}\", \"tags\": { \"UUID\": \"${BUILD_UUID}\", \"last_build_time\": \"${date}\"}}]' ${buildManifest} | sponge ${buildManifest}")
        return new_version
    }
}

def call(def filename, def bucket){
    googleStorageDownload bucketUri: "gs://${bucket}/${filename}", credentialsId: 'sa-createstudio-buckets', localDirectory: "${WORKSPACE}"
}

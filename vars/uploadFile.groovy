def call(def filename, def bucket){
    googleStorageUpload bucket: "gs://${bucket}", credentialsId: 'sa-createstudio-buckets', pattern: "${filename}"
}

#!/bin/bash
set -eu

if [ ! -v UNITY_USERNAME ]; then
  echo "ERROR: UNITY_USERNAME environment variable is not set"
  exit 1
fi

if [ ! -v UNITY_PASSWORD ]; then
  echo "ERROR: UNITY_PASSWORD environment variable is not set"
  exit 1
fi

if [ ! -v UNITY_SERIAL ]; then
  echo "ERROR: UNITY_SERIAL environment variable is not set"
  exit 1
fi

#if [[ $# -ne 1 || ! -v PROJECT_TYPE ]]; then
if [ ! -v PROJECT_TYPE ]; then
  echo "ERROR: please specify command or set PROJECT_TYPE variable"
  echo "  check - runs file/folder structure check"
  echo "  test - runs unit tests"
  echo "  windows - runs 64-bit Windows build"
  echo "  linux - runs 64-bit Linux build"
  echo "  macos - runs macOS build"
  echo "  webgl - runs webgl build"
  exit 1
fi


CHECK_UNITY_LOG=$(readlink -f "$(dirname $0)/check-unity-log.sh")

#export HOME=/tmp

#cd /mnt

PREFIX=viewer_builder
SUFFIX=

if [ -v GIT_TAG ]; then
  export BUILD_VERSION=${GIT_TAG}
  DEVELOPMENT_BUILD=
  SUFFIX=${SUFFIX}-${GIT_TAG}
else
  export BUILD_VERSION="dev"
  DEVELOPMENT_BUILD=-developmentBuild
  if [ -v GIT_BRANCH ]; then
    SUFFIX=${SUFFIX}-${GIT_BRANCH}
  fi
  if [ -v JENKINS_BUILD_ID ]; then
    SUFFIX=${SUFFIX}-${JENKINS_BUILD_ID}
  fi
fi

# Replace any '/' with '-' using bash's Pattern Replace
# ${VAR//pattern/replacement}

SUFFIX=${SUFFIX//\//-}

function check_unity_log {
    ${CHECK_UNITY_LOG} $@
}

#xvfb-run --auto-servernum --server-args='-screen 0 640x480x24' /opt/Unity/Editor/Unity -logFile /dev/stdout -batchmode -username "$UNITY_USERNAME" -password "$UNITY_PASSWORD" -serial "$UNITY_SERIAL"

function get_unity_license {
    echo "Fetching unity license"

    mkdir -p dummy-unity-project
    pushd dummy-unity-project

    xvfb-run --auto-servernum --server-args='-screen 0 640x480x24' /opt/Unity/Editor/Unity \
        -logFile /dev/stdout \
        -batchmode \
        -serial "${UNITY_SERIAL}" \
        -username "${UNITY_USERNAME}" \
        -password "${UNITY_PASSWORD}" \
        -quit

    popd
}

function finish {
  /opt/Unity/Editor/Unity \
    -batchmode \
    -force-vulkan \
    -silent-crashes \
    -quit \
    -nographics \
    -returnlicense
}
trap finish EXIT

get_unity_license

#cd -
if [ "$1" == "check" ]; then

  /opt/Unity/Editor/Unity \
    -batchmode \
    -force-vulkan \
    -silent-crashes \
    -quit \
    -projectPath . \
    -executeMethod Simulator.Editor.Check.Run \
    -saveCheck /mnt/${PREFIX}-check${SUFFIX}.html \
    -logFile /dev/stdout | tee unity-check.log

  check_unity_log unity-check.log

  exit 0

elif [ "$1" == "test" ]; then

  /opt/Unity/Editor/Unity \
    -batchmode \
    -force-vulkan \
    -silent-crashes \
    -projectPath . \
    -runEditorTests \
    -editorTestsResultFile /mnt/${PREFIX}-test${SUFFIX}.xml \
    -logFile /dev/stdout | tee unity-test.log \
  || true

  check_unity_log unity-test.log

  exit 0

fi

if [ "$1" == "windows" ]; then

  BUILD_TARGET=Win64
  BUILD_OUTPUT=${PREFIX}-windows64${SUFFIX}
  BUILD_CHECK=simulator.exe

elif [ "$1" == "linux" ]; then

  BUILD_TARGET=Linux64
  BUILD_OUTPUT=${PREFIX}-linux64${SUFFIX}
  BUILD_CHECK=simulator

elif [ "$1" == "macos" ]; then

  BUILD_TARGET=OSXUniversal
  BUILD_OUTPUT=${PREFIX}-macOS${SUFFIX}
  BUILD_CHECK=simulator.app/Contents/MacOS/simulator

elif [[ "$1" == "webgl" || -z "$PROJECT_TYPE"]]; then

  BUILD_TARGET=WebGL
  BUILD_OUTPUT=${PREFIX}-webgl${SUFFIX}
  BUILD_CHECK=viewer.html

else

  echo "Unknown command $1"
  exit 1

fi


/opt/Unity/Editor/Unity ${DEVELOPMENT_BUILD} \
  -nographics \
  -batchmode \
  -force-vulkan \
  -silent-crashes \
  -quit \
  -skipMissingProjectID \
  -skipMissingProjectID \
  -projectPath . \
  -executeMethod Builder.Build ${BUILD_TARGET} \
  -buildTarget ${BUILD_TARGET} \
  -buildPlayer /tmp/${BUILD_OUTPUT} \
  -logFile /dev/stdout | tee unity-build-player-${BUILD_TARGET}.log


#/opt/Unity/Editor/Unity ${DEVELOPMENT_BUILD} \
#  -batchmode \
#  -force-vulkan \
#  -silent-crashes \
#  -nographics \
#  -quit \
#  -projectPath /root/project \
#  -executeMethod Build.build \
#  -buildTarget ${BUILD_TARGET} \
#  -buildPlayer /tmp/${BUILD_OUTPUT} \
#  -logFile /dev/stdout | tee unity-build-player-${BUILD_TARGET}.log

#check_unity_log unity-build-player-${BUILD_TARGET}.log
echo "printing /tmp"
ls -la /tmp/ 2> /dev/null
echo "printing pwd"
pwd
ls -la

#if [ ! -f ${BUILD_OUTPUT}/${BUILD_CHECK} ]; then
#if [ ! -f ${BUILD_OUTPUT}/${BUILD_CHECK} ]; then
#  echo "ERROR: *****************************************************************"
#  echo "ERROR: Executable was not build, scroll up to see actual error"
#  echo "ERROR: *****************************************************************"
#  exit 1
#fi

#cd /tmp
#zip -r WebGLProdBuild.zip WebGLProdBuild

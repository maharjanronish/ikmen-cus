#!/usr/bin/env bash
# Resumable Android build runner - runs build.sh with a time budget
source /home/z/my-project/tools/env.sh
export ANDROID_APK_REPO=/home/z/my-project/ikemen-droid
export APP_VERSION=0.103-mobile-minimal
export APP_BUILDTIME=2026.10.25
export ANDROID_APK_OUT=/home/z/my-project/download/IkemenGO-Mobile-Minimal.apk
cd /home/z/my-project/Ikemen-GO
exec timeout -k 10 "${1:-580}" ./build/build.sh Android

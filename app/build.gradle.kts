name: Build CipherScope APK

on:
  push:
    branches: [ "main", "master" ]
  workflow_dispatch:

jobs:
  build:
    name: Assemble Android Debug APK
    runs-on: ubuntu-latest

    steps:
      - name: Checkout Code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      # Automatically creates root Gradle files if missing on GitHub
      - name: Bootstrap Missing Root Files
        run: |
          # 1. Create settings.gradle.kts if missing
          if [ ! -f "settings.gradle.kts" ] && [ ! -f "settings.gradle" ]; then
            echo "Creating missing settings.gradle.kts..."
            cat << 'EOF' > settings.gradle.kts
          pluginManagement {
              repositories {
                  google()
                  mavenCentral()
                  gradlePluginPortal()
              }
          }
          dependencyResolutionManagement {
              repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
              repositories {
                  google()
                  mavenCentral()
              }
          }
          rootProject.name = "CipherScope"
          include(":app")
          EOF
          fi

          # 2. Create root build.gradle.kts if missing
          if [ ! -f "build.gradle.kts" ] && [ ! -f "build.gradle" ]; then
            echo "Creating missing root build.gradle.kts..."
            cat << 'EOF' > build.gradle.kts
          buildscript {
              repositories {
                  google()
                  mavenCentral()
              }
              dependencies {
                  classpath("com.android.tools.build:gradle:8.3.2")
                  classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.23")
                  classpath("com.google.gms:google-services:4.4.1")
              }
          }
          EOF
          fi

      # Automatically installs Gradle & creates wrapper if missing
      - name: Ensure Gradle Wrapper Exists
        run: |
          if [ ! -f "gradlew" ] || [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
            echo "Generating Gradle wrapper..."
            sudo apt-get update -qq
            sudo apt-get install -y -qq gradle
            gradle wrapper --gradle-version 8.6
          fi
          chmod +x gradlew

      - name: Build Debug APK
        run: ./gradlew :app:assembleDebug --stacktrace

      - name: Upload APK Artifact
        uses: actions/upload-artifact@v4
        with:
          name: CipherScope-v1.0-Debug
          path: app/build/outputs/apk/debug/app-debug.apk
          retention-days: 7

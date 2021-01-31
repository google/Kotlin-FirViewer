# Kotlin FIR Viewer

Small tool to view inspect Kotlin FIR structure.

## How to use?

This plugin only works with Kotlin plugin in FIR mode. To use it, follow the instructions below.

1. go to the release page and download the most recent release.
2. Open IntelliJ (probably your dev build that has the Kotlin FIR plugin with `idea.fir.plugin=true`)
3. Open plugins setting. Click the gear icon on top, select "Install plugin from disk...", and pick the downloaded zip file.
4. Click OK and you are good to go.

## Build Instruction

This plugin depends on the Kotlin plugin in FIR mode. Since Kotlin FIR mode is currently not released yet, you will need a local build of the Kotlin IDE plugin. Also, since the master branch of Kotlin project is built with non-released version of Kotlin compiler, this plugin will need to be compiled with the same (or more recent) version of Kotlin compiler (explained below).

1. Clone https://github.com/JetBrains/kotlin.git, open `<project>/gradle.properties`, and add (or uncomment)
   `idea.fir.plugin=true`. Then `cd` into the Kotlin project

2. `./gradlew install` to install the updated Kotlin compiler and dependencies to maven local.
   
3. `./gradlew ideaPlugin` to build the Kotlin plugin.

4. Copy the built plugin at `dist/artifacts/ideaPlugin/Kotlin/lib/kotlin-plugin.jar` to the project root of FirViewer.
   
   Note: you may need to update the version of `org.jetbrains.kotlin.jvm` in `build.gradle` to match the version built in step 2. To figure out the version, do `ls <maven local>/org/jetbrains/kotlin/jvm/org.jetbrains.kotlin.jvm.gradle.plugin`

5. `cd` into FirViewer project and build with `./gradlew buildPlugin`

6. The resulted build is located at `build/distributions`
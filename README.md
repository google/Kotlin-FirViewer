# Kotlin FIR Viewer

A small tool to inspect Kotlin FIR structure.

![image](https://user-images.githubusercontent.com/29584386/106402741-d2c64c80-63df-11eb-9b7d-5f89dbe967e8.png)

## Installation

Note: This plugin **only** works with Kotlin plugin in FIR mode (Set `idea.fir.plugin=true` in [gradle.properties](https://github.com/JetBrains/kotlin/blob/master/gradle.properties)).

1. Go to the [release](https://github.com/google/Kotlin-FirViewer/releases) page and download the most recent release.
2. Launch IntelliJ with FIR plugin.
3. Open plugins setting. Click the gear icon on top, select "Install plugin from disk...", and pick the downloaded zip file and click OK.

## How to use?

The plugin provides three tool windows
* FIR Viewer (View -> Tool Windows -> FIR Viewer): shows FIR structures for the current opened file
* KT Viewer (View -> Tool Windows -> KT Viewer): shows the Kotlin PSI structures for the current opened file plus some information exposed by the new idea-frontend-api (those carries star icons)
* CFG Viewer (View -> Tool Windows -> CFG Viewer): shows the FIR control flow graph. To use this, you must have `dot`(graphviz) available in your PATH. You can install it via `homebrew install graphviz` or `sudo apt-get install graphviz`

## Build Instruction

This plugin depends on the Kotlin plugin in FIR mode. Since Kotlin FIR mode is currently not released yet, you will need a local build of the Kotlin IDE plugin. Also, since the master branch of Kotlin project is built with non-released version of Kotlin compiler, this plugin will need to be compiled with the same (or more recent) version of Kotlin compiler (explained below).

1. Clone https://github.com/JetBrains/kotlin.git

2. Clone https://github.com/JetBrains/intellij-community.git and put it inside <kotlin-repo>/intellij

3. Open intellij-community= project in IntelliJ and add an artifacts bulding module `kotlin.fir` and its dependency.

4. Run `./gradlew install` in the Kotlin repo to install needed dependencies in the local maven repo.

5. Build the artifact created in step 3 and copy the artifact to the project root of `FirViewer`. Make sure the copied
   artifact is named `kotlin.fir.jar`.

6. `cd` into FirViewer project and build FirViewer with `./gradlew buildPlugin`

7. The resulted plugin file is located at `build/distributions`

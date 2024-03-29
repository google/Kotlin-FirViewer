# Kotlin FIR Viewer

A small tool to inspect Kotlin FIR structure.

![image](https://user-images.githubusercontent.com/29584386/106402741-d2c64c80-63df-11eb-9b7d-5f89dbe967e8.png)

## Installation

Note: This plugin **only** works with Kotlin plugin in FIR mode.

1. Go to the [release](https://github.com/google/Kotlin-FirViewer/releases) page and download the most recent release.
2. Launch IntelliJ with FIR plugin.
3. Open plugins setting. Click the gear icon on top, select "Install plugin from disk...", and pick the downloaded zip file and click OK.

## How to use?

The plugin provides three tool windows
* FIR Viewer (View -> Tool Windows -> FIR Viewer): shows FIR structures for the current opened file
* KT Viewer (View -> Tool Windows -> KT Viewer): shows the Kotlin PSI structures for the current opened file plus some information exposed by the new idea-frontend-api (those carries star icons)
* CFG Viewer (View -> Tool Windows -> CFG Viewer): shows the FIR control flow graph. To use this, you must have `dot`(graphviz) available in your PATH. You can install it via `homebrew install graphviz` or `sudo apt-get install graphviz`

## Build Instruction

This plugin depends on the Kotlin plugin in FIR mode. Since Kotlin FIR mode is currently not released yet, you will need a local build of the Kotlin IDE plugin. Also, since the master branch of Kotlin project is built with non-released version of Kotlin compiler, this plugin will need to be compiled with the same (or more recent) version of Kotlin compiler (see beloow for instructions).

1. Clone https://github.com/JetBrains/kotlin.git

2. Clone https://github.com/JetBrains/intellij-community.git and put it inside <kotlin-repo>/intellij

3. Open intellij-community project in IntelliJ and add an artifact bulding module `kotlin.fir` and its dependency. The output should be in `<intellij project root>/out/artifacts/kotlin_fir_jar` See the following screenshot
   ![image](https://user-images.githubusercontent.com/29584386/141865850-f31c4444-d024-4c2e-a500-8376cc072cbb.png)
   ![image](https://user-images.githubusercontent.com/29584386/141865919-fdadd4ef-2d68-4861-9475-cb4de0acee51.png)

4. Run `./gradlew install` in the Kotlin repo to install needed dependencies in the local maven repo.

5. Build the artifact created in step 3 and copy the artifact to the project root of `FirViewer`. Make sure the copied
   artifact is named `kotlin.fir.jar` (this should be the default name).

6. `cd` into FirViewer project and build FirViewer with `./gradlew buildPlugin`. Note that you may want to bump up the plugin version in `build.gradle` in order to create a new release.

7. The resulted plugin file is located at `build/distributions`.
8. If everything works fine. Please consider committing your change, pushing upstream, and publishing a new release so others can use it.

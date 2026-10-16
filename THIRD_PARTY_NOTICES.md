# Third-party notices

The root MIT license applies to AgentSpace's original code. Existing third-party copyright and license headers remain in place.

## Vendored Termux terminal code

`app/src/main/third_party/termux/` contains the terminal emulator and renderer from Termux v0.118.3, commit `5b657c6adf4304e5198951ce815fe0205dcac29c`. These components are identified by upstream as an Apache-2.0 exception to the Termux application's GPLv3 license. See the [vendoring record](app/src/main/third_party/termux/README.md), [upstream license notice](app/src/main/assets/licenses/termux-upstream-license.md) and [Apache-2.0 text](app/src/main/assets/licenses/Apache-2.0.txt).

## JSch

The build uses `com.github.mwiede:jsch:2.28.0`. Its supplied BSD-style license and bundled JZlib/jBCrypt notices are retained in [docs/licenses/](docs/licenses/) and copied into application license assets. Those notices retain their original copyright holders and conditions.

## Gradle wrapper

The Gradle wrapper and scripts are third-party build tooling. Their existing Apache-2.0 headers are retained; the Apache license text is included above.

## Downloaded dependencies

AndroidX/Compose, Kotlin, JUnit, JSON-java and Meta Spatial SDK dependencies are downloaded by the build and are not vendored in this source repository. Exact direct versions are declared in the Gradle build files. Their respective licenses and SDK terms continue to apply. This notice is a source inventory, not a complete transitive dependency notice bundle for binary redistribution. Review and include all applicable notices and Meta SDK terms before distributing an APK.

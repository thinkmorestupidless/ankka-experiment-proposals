// Packaging the service as a container image (`sbt Docker/publishLocal`).
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")
// Formatting, with the family's .scalafmt.conf; the pre-commit hook refuses unformatted commits.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")

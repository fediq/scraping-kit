// The following plugin doesn't work because of https://github.com/sbt/sbt-bintray/issues/104
// addSbtPlugin("org.foundweekends" % "sbt-bintray" % "0.4.0")
// So I had to downgrade to it's previous major release
addSbtPlugin("me.lessis" % "bintray-sbt" % "0.3.0")

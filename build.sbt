javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

val awsSdkVersion = "1.11.75"

val circeVersion = "0.5.2"

lazy val root = (project in file(".")).
  settings(
    name := "letsencrypt-route53-lambda",
    version := "1.0",
    scalaVersion := "2.11.8",
    retrieveManaged := true,
    libraryDependencies ++= Seq(
      "org.shredzone.acme4j" % "acme4j" % "0.9",
      "org.shredzone.acme4j" % "acme4j-utils" % "0.9",
      "io.monix" %% "monix-execution" % "2.1.2",
      "io.monix" %% "monix-eval" % "2.1.2",
      "org.typelevel" %% "cats-core" % "0.7.2",
      "com.amazonaws" % "aws-lambda-java-core" % "1.0.0" ,
      "com.amazonaws" % "aws-java-sdk-s3" % awsSdkVersion,
      "com.amazonaws" % "aws-java-sdk-route53" % awsSdkVersion,
      "com.amazonaws" % "aws-java-sdk-kms" % awsSdkVersion,
      "ch.qos.logback" % "logback-classic"   % "1.1.7"
    )
  )

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser",
  "io.circe" %% "circe-java8"
).map(_ % circeVersion)

assemblyMergeStrategy in assembly := {
   {
    case PathList("META-INF", xs @ _*) => MergeStrategy.discard
    case x => MergeStrategy.first
   }
}


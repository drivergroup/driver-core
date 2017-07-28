import sbt._
import Keys._

lazy val akkaHttpV = "10.0.5"

lazy val core = (project in file("."))
  .driverLibrary("core")
  .settings(lintingSettings ++ formatSettings)
  .settings(libraryDependencies ++= Seq(
    "com.typesafe.akka"            %% "akka-http-core"       % akkaHttpV,
    "com.typesafe.akka"            %% "akka-http-spray-json" % akkaHttpV,
    "com.typesafe.akka"            %% "akka-http-testkit"    % akkaHttpV,
    "com.pauldijou"                %% "jwt-core"             % "0.9.2",
    "org.scalatest"                % "scalatest_2.11"        % "2.2.6" % "test",
    "org.scalacheck"               %% "scalacheck"           % "1.12.5" % "test",
    "org.mockito"                  % "mockito-core"          % "1.9.5" % "test",
    "com.github.swagger-akka-http" %% "swagger-akka-http"    % "0.9.1",
    "com.amazonaws"                % "aws-java-sdk-s3"       % "1.11.26",
    "com.google.cloud"             % "google-cloud-pubsub"   % "0.17.2-alpha",
    "com.google.cloud"             % "google-cloud-storage"  % "1.0.1",
    "com.typesafe.slick"           %% "slick"                % "3.1.1",
    "com.typesafe"                 % "config"                % "1.2.1",
    "com.typesafe.scala-logging"   %% "scala-logging"        % "3.4.0",
    "ch.qos.logback"               % "logback-classic"       % "1.1.3",
    "org.webjars"                  % "webjars-locator"       % "0.32",
    "org.webjars"                  % "swagger-ui"            % "3.0.20"
  ))

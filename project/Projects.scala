import sbt._
import sbt.Keys._

object Projects extends Build {
  import Dependencies._
  import Settings._

  lazy val root = Project("footpath-routing", file("."))
    .settings(basicSettings: _*)
    .settings(formatSettings: _*)
    .settings(noPublishing: _*)
    .aggregate(pathGenerator)

  lazy val pathGenerator = Project("path-generator",file("path-generator"))
    .settings(basicSettings: _*)
    .settings(formatSettings: _*)
    .settings(libraryDependencies ++=
      compile(typesafeConfig, slf4jApi ,logbackCore, logbackClassic, akkaActor, akkaSlf4j, akkaTestKit,
        scalaReflect) ++
      test(scalatest, mockito))
    .settings(noPublishing: _*)

  val noPublishing = Seq(publish := (), publishLocal := (), publishArtifact := false)
}

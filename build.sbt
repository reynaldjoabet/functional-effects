scalaVersion := "3.10.1"

version := "1.0"

LocalRootProject / name := "Functional-Effects"

lazy val root = project.in(file("."))

lazy val zio = project.in(file("./zio"))

ThisBuild / scalacOptions := Seq(
  "-encoding",
  "UTF-8",
  "-no-indent",
  "-deprecation",
  "-feature",
  "-unchecked",
  // "-Werror",
  // "-Wunused:all",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-language:strictEquality",
  "-Xcheck-macros",
  "-Xmax-inlines:64"
)

ThisBuild / scalaVersion := "3.3.7"

ThisBuild / name := "Functional-Effects"

ThisBuild / version := "1.0"

lazy val root = project in file(".")

lazy val zio = project in file("./zio")

ThisBuild / scalacOptions ++= Seq(
  "-encoding",
  "UTF-8",
  "-deprecation",
  "-feature",
  "-Xcheck-macros",
  // "-Ycheck:all", // also for checking macros
  "-Ycheck-mods",
  "-Ydebug-type-error",
  "-Xprint-types", // Without this flag, we will not see error messages for exceptions during given-macro expansion!
  "-Yshow-print-errors",
  "-language:experimental.macros",
  "-language:implicitConversions",
  "-language:higherKinds",
  "-language:namedTypeArguments",
  "-language:dynamics",
  "-Ykind-projector:underscores",
  "-unchecked",
  "-no-indent"
  // "-explain"
)

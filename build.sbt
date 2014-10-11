name := "akre"

version := "0.8.2"

description := "A Redis client for Scala, implemented using Akka."

homepage := Some(url("https://github.com/programmiersportgruppe/akre"))

organization := "org.programmiersportgruppe"



scalacOptions := Seq("-Xfatal-warnings")

crossScalaVersions := Seq("2.10.4", "2.11.2")

scalaVersion := crossScalaVersions.value.head



conflictManager := ConflictManager.strict

dependencyOverrides += "org.scala-lang" % "scala-library" % scalaVersion.value



lazy val akkaVersion = Def.setting("2.3.6")

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % akkaVersion.value

libraryDependencies ++= (
  if (scalaVersion.value startsWith "2.11.") Seq("org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.2")
  else Nil
)



libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.2" % "test"

libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % akkaVersion.value % "test"

// To make IntelliJ's test runner happy
libraryDependencies ++= (
  if (scalaVersion.value startsWith "2.11.") Seq("org.scala-lang.modules" %% "scala-xml" % "1.0.2" % "test")
  else Nil
)

plugins {
  kotlin("jvm") version "2.3.10"
  id("application")
  id("com.gradleup.shadow") version "9.0.0"
  id("com.ncorti.ktfmt.gradle") version "0.24.0"
}

group = "at.ac.uibk.dps.cirrina.execution.object"

version = "1.0-SNAPSHOT"

repositories { mavenCentral() }

ktfmt { googleStyle() }

dependencies {
  implementation("org.eclipse.zenoh:zenoh-kotlin:1.7.2")

  implementation("org.slf4j:slf4j-api:2.0.16")
  runtimeOnly("ch.qos.logback:logback-classic:1.5.16")

  compileOnly(fileTree("/opt/cirrina/lib") { include("*.jar") })

  testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(25) }

application { mainClass.set("at.ac.uibk.dps.cirrina.execution.object.EventPublisherKt") }

tasks.test { useJUnitPlatform() }

tasks.shadowJar {
  archiveFileName.set("CSMSmartFactoryEventPublisher.jar")

  manifest { attributes["Main-Class"] = application.mainClass.get() }

  mergeServiceFiles()
}

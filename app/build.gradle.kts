plugins {
    id("snmptool.java-conventions")
    application
    alias(libs.plugins.javafx)
}

dependencies {
    implementation(project(":core"))
}

javafx {
    version = libs.versions.javafx.get()
    modules("javafx.controls")
}

application {
    mainModule = "hr.avrbanac.snmptool.app"
    mainClass = "hr.avrbanac.snmptool.app.SnmpToolApp"
    // JavaFX loads its native libraries itself; allow it explicitly (JEP 472).
    applicationDefaultJvmArgs = listOf("--enable-native-access=javafx.graphics")
}

// ---------------------------------------------------------------------------------------------------------
// Native packaging with jpackage (see "Packaging" in docs/development.md).
//
// All modules (app, core, SNMP4J, JavaFX) are explicit JPMS modules, so jpackage links a trimmed runtime
// with jlink itself. The package type defaults to an app image (a self-contained folder); installers are
// built with -PpackageType=deb|rpm (Linux), msi|exe (Windows) or dmg|pkg (macOS), on that platform.

val packageType = providers.gradleProperty("packageType").getOrElse("app-image")
// JDK whose jpackage (and therefore runtime) is used; defaults to the Java 25 toolchain. Release builds use a
// JDK built against an older glibc (e.g. Eclipse Temurin) so the package runs on older distributions.
val jpackageJdk = providers.gradleProperty("jpackageJdk")
val appName = "snmp-tool"
// Icon per platform, rendered from packaging/snmp-tool.svg by packaging/render-icons.sh:
// packaging/linux/snmp-tool.png, packaging/windows/snmp-tool.ico; macOS (packaging/macos/snmp-tool.icns)
// has none yet, so jpackage uses its default icon there.
val osName = System.getProperty("os.name").lowercase()
val iconFile = layout.projectDirectory.file(
    when {
        osName.startsWith("windows") -> "packaging/windows/$appName.ico"
        osName.startsWith("mac") -> "packaging/macos/$appName.icns"
        else -> "packaging/linux/$appName.png"
    }
).asFile

val jpackageInput = tasks.register<Sync>("jpackageInput") {
    description = "Collects the application's module jars for jpackage."
    from(configurations.runtimeClasspath)
    from(tasks.jar)
    into(layout.buildDirectory.dir("jpackage/modules"))
}

tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "Builds a native application image or installer with jpackage (-PpackageType=...)."
    dependsOn(jpackageInput)

    val launcher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val modules = layout.buildDirectory.dir("jpackage/modules")
    val output = layout.buildDirectory.dir("jpackage/$packageType")
    inputs.files(jpackageInput)
    inputs.property("packageType", packageType)
    inputs.property("jpackageJdk", jpackageJdk.getOrElse(""))
    outputs.dir(output)

    doFirst {
        // jpackage refuses to overwrite earlier output.
        delete(output)
        val jdk = jpackageJdk.map { file(it) }.getOrElse(launcher.get().metadata.installationPath.asFile)
        val jpackage = jdk.resolve("bin/jpackage")
        check(jpackage.isFile) { "No jpackage in $jdk" }
        val args = mutableListOf(
            jpackage.absolutePath,
            "--type", packageType,
            "--name", appName,
            "--app-version", project.version.toString(),
            "--description", "MIB browser with basic SNMP operations",
            "--module-path", modules.get().asFile.absolutePath,
            "--module", "hr.avrbanac.snmptool.app/hr.avrbanac.snmptool.app.SnmpToolApp",
            "--java-options", "--enable-native-access=javafx.graphics",
            // Large vendor MIB sets need about 1 GB for the model (docs/architecture.md, Performance).
            "--java-options", "-XX:MaxRAMPercentage=50",
            "--jlink-options", "--strip-debug --no-header-files --no-man-pages --strip-native-commands",
            "--dest", output.get().asFile.absolutePath,
        )
        if (iconFile.isFile) {
            args += listOf("--icon", iconFile.absolutePath)
        }
        when (packageType) {
            "deb", "rpm" -> args += listOf(
                "--linux-package-name", appName,
                "--linux-app-category", "net",
                "--linux-menu-group", "Network",
                "--linux-shortcut",
            )
            "msi", "exe" -> args += listOf("--win-menu", "--win-shortcut", "--win-dir-chooser")
        }
        commandLine(args)
    }
}

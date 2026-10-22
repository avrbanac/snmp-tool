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

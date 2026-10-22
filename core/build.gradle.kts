plugins {
    id("snmptool.java-conventions")
    `java-library`
}

dependencies {
    implementation(libs.snmp4j)
    // The in-process test agent uses SNMP4J directly.
    testImplementation(libs.snmp4j)
}

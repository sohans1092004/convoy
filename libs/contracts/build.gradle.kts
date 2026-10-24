// Generated event payload records (tools/gen_contracts.py) + a schema validator for publisher/consumer tests.
plugins { `java-test-fixtures` }

dependencies {
    api("com.fasterxml.jackson.core:jackson-annotations")
    testFixturesApi(libs.json.schema.validator)
    testFixturesApi("com.fasterxml.jackson.core:jackson-databind")
}

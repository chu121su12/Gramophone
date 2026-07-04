val japaneseIpadicConfig = layout.projectDirectory.file(
    "src/main/dictionary/ja_JP/ipadic.properties"
)
val japaneseIpadicScript = layout.projectDirectory.file(
    "src/main/dictionary/ja_JP/generate-ipadic.sh"
)
val japaneseIpadicCacheDir = layout.projectDirectory.dir(
    "src/main/dictionary/ja_JP/cache"
)
val japaneseIpadicGeneratedResDir = layout.buildDirectory.dir("generated/res/japaneseIpadic")
val japaneseIpadicGeneratedRaw = japaneseIpadicGeneratedResDir.map {
    it.file("raw/japanese_ipadic.tsv")
}

tasks.register<Exec>("checkJapaneseIpadicDictionaryRemote") {
    group = "dictionary"
    description = "Checks pinned MeCab IPADIC URL metadata against the remote archive."
    inputs.file(japaneseIpadicConfig)
    inputs.file(japaneseIpadicScript)
    commandLine("bash", japaneseIpadicScript.asFile.absolutePath, "--check-remote", japaneseIpadicConfig.asFile.absolutePath)
}

val generateJapaneseIpadicDictionary = tasks.register<Exec>("generateJapaneseIpadicDictionary") {
    group = "dictionary"
    description = "Generates compact Japanese transliteration dictionary from cached MeCab IPADIC."
    inputs.file(japaneseIpadicConfig)
    inputs.file(japaneseIpadicScript)
    inputs.dir(japaneseIpadicCacheDir).optional()
    outputs.file(japaneseIpadicGeneratedRaw)
    commandLine(
        "bash",
        japaneseIpadicScript.asFile.absolutePath,
        japaneseIpadicConfig.asFile.absolutePath,
        japaneseIpadicGeneratedRaw.get().asFile.absolutePath
    )
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generateJapaneseIpadicDictionary)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Resources") }.configureEach {
    dependsOn(generateJapaneseIpadicDictionary)
}

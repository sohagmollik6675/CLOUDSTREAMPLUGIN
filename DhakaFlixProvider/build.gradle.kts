// Use CloudStream's standard extension build setup
version = 1

cloudstream {
    // Your name
    authors = listOf("local")

    // Language of content
    language = "en"

    // Description shown in CloudStream
    description = "DhakaFlix – streams movies from your local server at 172.16.50.14"

    /**
     * Status of the plugin:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta / not working
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    iconUrl = ""
}

import Testing
@testable import HerdrKit

/// A build without the gitignored token file still reads its Info.plist key: Xcode leaves it
/// empty, or `$(…)` when the setting reaches the plist unexpanded. Any of those means Luciq off.
@Test(arguments: [nil, "", "   ", "\n\t", "$(LUCIQ_APP_TOKEN)", "${LUCIQ_APP_TOKEN}"] as [String?])
func noTokenMeansLuciqOff(_ raw: String?) {
    #expect(LuciqToken(parsing: raw) == nil)
}

@Test(arguments: ["0123456789abcdef0123456789abcdef", " 0123456789abcdef0123456789abcdef\n"])
func aRealValueIsTheToken(_ raw: String) {
    #expect(LuciqToken(parsing: raw)?.value == "0123456789abcdef0123456789abcdef")
}

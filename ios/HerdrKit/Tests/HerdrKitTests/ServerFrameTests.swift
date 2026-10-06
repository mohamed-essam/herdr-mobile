import Foundation
import Testing
@testable import HerdrKit

@Test func decodesWelcome() throws {
    let json = #"{"t":"welcome","herdrVersion":"0.9.1","herdrProtocol":3,"companionProtocol":10}"#
    #expect(try ServerFrame(json: Data(json.utf8)) == .welcome)
}

@Test func keepsTheTypeOfFramesM1DoesNotHandle() throws {
    #expect(try ServerFrame(json: Data(#"{"t":"panes","panes":[]}"#.utf8)) == .other("panes"))
}

@Test(arguments: ["not json", #"{"panes":[]}"#, "[]"])
func rejectsFramesWithoutAType(_ text: String) {
    #expect(throws: (any Error).self) { try ServerFrame(json: Data(text.utf8)) }
}

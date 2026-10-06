import Testing
@testable import HerdrKit

@Test func doublesFromOneSecondUpToFifteen() {
    var b = Backoff()
    #expect((0..<6).map { _ in b.next() } == [1, 2, 4, 8, 15, 15].map(Duration.seconds))
}

@Test func resetStartsOverAtOneSecond() {
    var b = Backoff()
    _ = b.next(); _ = b.next()
    b.reset()
    #expect(b.next() == .seconds(1))
}

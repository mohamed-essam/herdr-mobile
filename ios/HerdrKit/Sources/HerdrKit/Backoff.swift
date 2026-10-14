/// Reconnect delays, as on Android: 1 s doubling up to 15 s, reset on a healthy connection.
struct Backoff {
    private let first: Duration
    private var delay: Duration

    init(first: Duration = .seconds(1)) {
        self.first = first
        delay = first
    }

    mutating func next() -> Duration {
        defer { delay = min(delay * 2, .seconds(15)) }
        return delay
    }

    mutating func reset() { delay = first }
}

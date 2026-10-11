import Foundation
import Observation

/// A chat stream: a pane's main one (`agentId` nil) or one of its agent threads.
public struct ChatKey: Hashable, Sendable {
    public let paneId: String
    public let agentId: String?

    public init(paneId: String, agentId: String? = nil) {
        self.paneId = paneId
        self.agentId = agentId
    }
}

public enum ImageState: Equatable, Sendable {
    case loading
    case ready(Data)
    case missing
}

/// Every chat stream's state and the images they show. Feed it frames with `apply(_:)`
/// (a `welcome` re-opens what is open) and images with `apply(image:)`. Ported from
/// Android's ChatRepository.kt.
@MainActor @Observable
public final class ChatStore {
    /// Why the last answer or interrupt failed, until `clearError()`.
    public private(set) var error: String?

    private var views: [ChatKey: ChatView] = [:]
    private var images: [ImageKey: ImageState] = [:]

    @ObservationIgnored private var opened: Set<ChatKey> = []
    @ObservationIgnored private var lastId = 0
    /// Least recently used first.
    @ObservationIgnored private var imageOrder: [ImageKey] = []
    @ObservationIgnored private var imageQueue: [ImageKey] = []
    /// Each request in flight has a token, so a stale timeout can't touch a newer request.
    @ObservationIgnored private var imagesInFlight: [ImageKey: Int] = [:]
    @ObservationIgnored private var imagesRetried: Set<ImageKey> = []
    @ObservationIgnored private var imageTokens = 0

    @ObservationIgnored private let send: @MainActor (ClientMessage) -> Void
    @ObservationIgnored private let request: @MainActor (ClientRequest) async throws -> ServerFrame
    @ObservationIgnored private let now: @MainActor () -> Int
    @ObservationIgnored private let imageReplyTimeout: Duration
    @ObservationIgnored private let imageRetry: Duration

    /// `send` and `request` go to the companion (`CompanionClient`); `now` is epoch ms. An
    /// image reply not seen within `imageReplyTimeout` counts as missing, and a missing
    /// image is asked for once more after `imageRetry`: it can reach the companion after its event.
    public init(send: @escaping @MainActor (ClientMessage) -> Void,
                request: @escaping @MainActor (ClientRequest) async throws -> ServerFrame,
                now: @escaping @MainActor () -> Int,
                imageReplyTimeout: Duration = .seconds(15), imageRetry: Duration = .seconds(3)) {
        self.send = send
        self.request = request
        self.now = now
        self.imageReplyTimeout = imageReplyTimeout
        self.imageRetry = imageRetry
    }

    public func view(_ key: ChatKey) -> ChatView { views[key] ?? ChatView() }

    public func clearError() { error = nil }

    // MARK: Frames

    public func apply(_ frame: ServerFrame) {
        let key: ChatKey
        switch frame {
        case .welcome: return reconnected()
        case .chatSnapshot(let s): key = ChatKey(paneId: s.paneId, agentId: s.agentId)
        case .chatEvent(let paneId, _, _, _, let agentId): key = ChatKey(paneId: paneId, agentId: agentId)
        case .chatState(let paneId, _), .chatAgent(let paneId, _, _), .chatTasks(let paneId, _): key = ChatKey(paneId: paneId)
        default: return
        }
        // Only scalars of the old view: holding a copy would make the in-place apply copy every entry.
        let (wasGap, wasLoaded, oldEpoch) = (view(key).gap, view(key).loaded, view(key).epoch)
        views[key, default: ChatView()].apply(frame, now: now())
        let after = view(key)
        // A seq gap: re-open once; the companion answers with a fresh snapshot.
        if !wasGap && after.gap && opened.contains(key) { sendOpen(key) }
        if key.agentId == nil { reopenThreads(of: key.paneId, after: frame, newEpoch: wasLoaded && after.epoch != oldEpoch) }
        // The companion may hold images now that it lacked before: give the pane's missing ones another go.
        if case .chatSnapshot = frame {
            retryMissingImages(paneId: key.paneId)
            pumpImages()
        }
    }

    /// The companion closes a pane's threads at a main resync and evicts old ones; an open
    /// thread would then freeze. A new main epoch re-opens every open thread of the pane,
    /// and an agent's upsert re-opens its thread when that is open and was answered missing.
    private func reopenThreads(of paneId: String, after frame: ServerFrame, newEpoch: Bool) {
        let again: [ChatKey] = switch frame {
        case .chatSnapshot where newEpoch: opened.filter { $0.paneId == paneId && $0.agentId != nil }
        case .chatAgent(_, let agent?, _):
            [ChatKey(paneId: paneId, agentId: agent.agentId)].filter { opened.contains($0) && view($0).missing }
        default: []
        }
        for k in again { sendOpen(k) }
    }

    public func open(_ key: ChatKey) {
        opened.insert(key)
        sendOpen(key)
    }

    private func sendOpen(_ key: ChatKey) { send(.chatOpen(paneId: key.paneId, agentId: key.agentId)) }

    public func close(_ key: ChatKey) {
        opened.remove(key)
        send(.chatClose(paneId: key.paneId, agentId: key.agentId))
    }

    private func reconnected() {
        for k in opened { sendOpen(k) }
        // Replies to requests sent on the old socket will never come: queue afresh every image still due.
        imagesInFlight = [:]
        retryMissingImages(paneId: nil)
        imageQueue = imageOrder.filter { images[$0] == .loading }
        pumpImages()
    }

    // MARK: Paging

    /// Asks for the page before the oldest entry. A no-op unless the view is loaded, the
    /// companion has more, and no page is in flight. A lost reply allows paging again.
    public func loadOlder(_ key: ChatKey) {
        var v = view(key)
        guard v.loaded, v.hasMore, !v.loadingOlder, !v.gap else { return }
        let token = nextId("h")
        let before = v.entries.first?.seq ?? v.lastSeq + 1
        v.startLoadingOlder(token: token)
        views[key] = v
        Task {
            do {
                if case .chatHistoryPage(let page) = try await request(
                    .chatHistory(paneId: key.paneId, epoch: v.epoch, beforeSeq: before, limit: historyPageLimit, agentId: key.agentId)) {
                    views[key]?.applyPage(page, token: token)
                }
            } catch {}
            views[key]?.olderTimedOut(token: token)
        }
    }

    // MARK: Sending

    /// Sends `text` to the pane's agent, shown as a pending bubble until its `user_text` arrives.
    public func send(paneId: String, text: String) async {
        let t = text.trimmed
        guard !t.isEmpty else { return }
        let key = ChatKey(paneId: paneId), id = nextId("p")
        views[key, default: ChatView()].addPending(id: id, text: t, now: now())
        do {
            _ = try await request(.chatSend(paneId: paneId, text: t))
        } catch CompanionError.timedOut {
            // It may still have been queued: a late delivery confirms it, or it expires.
        } catch {
            views[key]?.failPending(id: id, error: errorLabel(error))
        }
    }

    /// Sends a failed or undelivered message again.
    public func retry(paneId: String, pendingId: String) async {
        let key = ChatKey(paneId: paneId)
        guard let p = view(key).pending.first(where: { $0.id == pendingId && $0.status != .queued }) else { return }
        views[key]?.removePending(id: pendingId)
        await send(paneId: paneId, text: p.text)
    }

    public func expirePending() {
        let t = now()
        // Writing only where a bubble is queued: every write re-renders the chat screens.
        for (k, v) in views where v.pending.contains(where: { $0.status == .queued }) { views[k]?.expire(now: t) }
    }

    /// Answers an AskUserQuestion (question text → answer). The question stays answering
    /// until its tool_result arrives; a failure frees it and sets `error`. Ignored while an
    /// answer is in flight or once the question was answered.
    public func answer(paneId: String, toolUseId: String, answers: [String: String]) async {
        let key = ChatKey(paneId: paneId)
        let v = view(key)
        guard !v.answering.contains(toolUseId), !v.answered.contains(toolUseId) else { return }
        views[key, default: ChatView()].markAnswering(toolUseId)
        do {
            _ = try await request(.chatAnswer(paneId: paneId, toolUseId: toolUseId, answers: answers))
        } catch {
            views[key]?.clearAnswering(toolUseId)
            self.error = answerErrorLabel(error)
        }
    }

    /// Esc to the pane, the way the terminal interrupts a working agent.
    public func interrupt(paneId: String) async {
        do { _ = try await request(.sendKeys(paneId: paneId, keys: "esc")) } catch { self.error = errorLabel(error) }
    }

    private func nextId(_ prefix: String) -> String {
        lastId += 1
        return "\(prefix)\(lastId)"
    }

    // MARK: Images

    private struct ImageKey: Hashable { let paneId: String, id: String }

    /// The image's state; nil until `loadImage` asks for it. Reading it is no use in the LRU.
    public func image(paneId: String, id: String) -> ImageState? { images[ImageKey(paneId: paneId, id: id)] }

    /// Asks for an image the first time (or after it left the 30-image cache), and marks it as just used.
    public func loadImage(paneId: String, id: String) {
        let key = ImageKey(paneId: paneId, id: id)
        imageOrder.removeAll { $0 == key }
        imageOrder.append(key)
        guard images[key] == nil else { return }
        images[key] = .loading
        imageQueue.append(key)
        if imageOrder.count > imageCacheSize {
            let evicted = imageOrder.removeFirst()
            images[evicted] = nil
            imagesRetried.remove(evicted)
        }
        pumpImages()
    }

    public func apply(image: ChatImage) {
        let key = ImageKey(paneId: image.paneId, id: image.id)
        imagesInFlight[key] = nil
        if images[key] == .loading {
            if image.missing && !imagesRetried.contains(key) {
                imagesRetried.insert(key)
                Task {
                    try? await Task.sleep(for: imageRetry)
                    if images[key] == .loading && !imageQueue.contains(key) { imageQueue.append(key) }
                    pumpImages()
                }
            } else {
                images[key] = image.data.map(ImageState.ready) ?? .missing
            }
        }
        pumpImages()
    }

    /// Puts the missing images of `paneId` (every pane when nil) back to loading, with the
    /// retry-once rule afresh, and queues them.
    private func retryMissingImages(paneId: String?) {
        for k in imageOrder where (paneId == nil || k.paneId == paneId) && images[k] == .missing {
            images[k] = .loading
            imagesRetried.remove(k)
            if !imageQueue.contains(k) && imagesInFlight[k] == nil { imageQueue.append(k) }
        }
    }

    /// Moves queued images into free request slots; one unanswered in time counts as missing.
    private func pumpImages() {
        while imagesInFlight.count < maxImageRequests, !imageQueue.isEmpty {
            let k = imageQueue.removeFirst()
            guard imagesInFlight[k] == nil, images[k] != nil else { continue }
            imageTokens += 1
            let token = imageTokens
            imagesInFlight[k] = token
            send(.chatImage(paneId: k.paneId, id: k.id))
            Task { [imageReplyTimeout] in
                try? await Task.sleep(for: imageReplyTimeout)
                if imagesInFlight[k] == token {
                    apply(image: ChatImage(paneId: k.paneId, id: k.id, mediaType: nil, data: nil, missing: true))
                }
            }
        }
    }
}

/// Decoded images kept in memory, across panes.
let imageCacheSize = 30
/// Image requests in flight at once; each reply can be several MB.
let maxImageRequests = 3

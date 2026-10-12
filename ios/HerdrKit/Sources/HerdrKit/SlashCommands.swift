/// The composer's picker rows for `draft`: nil while it isn't a command name being typed
/// (`/` and no whitespace yet) or there are no `commands`. Names starting with what's
/// typed come first, then those containing it, each in the list's order; case is ignored.
/// Ported from Android's SlashCommands.kt.
public func slashMatches(_ draft: String, _ commands: [SlashCommand]) -> [SlashCommand]? {
    guard !commands.isEmpty, draft.hasPrefix("/"), !draft.contains(where: \.isWhitespace) else { return nil }
    let q = draft.dropFirst().lowercased()
    let prefix = commands.filter { $0.name.lowercased().hasPrefix(q) }
    return prefix + commands.filter { !$0.name.lowercased().hasPrefix(q) && $0.name.lowercased().contains(q) }
}

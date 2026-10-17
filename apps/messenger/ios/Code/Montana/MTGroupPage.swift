import SwiftUI

// ════════════════════════════════════════════════════════════
// A GROUP'S PAGE AS THE REFERENCE LAYS IT (the author's words 17.10.2026 18:4x-18:5x MSK: «channels and groups fully as in the
// reference folder, 1 to 1»; «we do not need our own kinds of pages inside groups and channels -- everything as in the reference»):
// the head's count, the people's rows and the invite link's page. The group's state and its words stay MTGroup's; this file only
// draws them, and the page (MontanaPeerInfoScreen) asks it for a group alone -- a channel keeps its own road.
// ════════════════════════════════════════════════════════════

enum MTGroupPage {
    /// «N members, M online», the reference's head of a group, said once for the chat's top and the page alike: M is this phone and
    /// whoever of the group it sees online by the one ladder (presenceWord) -- the owner's phone everyone it carries to, a member's
    /// phone the owner; nothing is said of the others. A channel says its count alone.
    @MainActor
    static func head(_ key: String, store: ChatStore) -> String {
        let n = MTGroup.shared.people(key) ?? 1
        let members = String(localized: "\(n) members", bundle: MTLanguage.bundle)
        guard MTGroup.shared.kind(key) == .group else { return members }
        let lit = 1 + MTGroup.shared.heldConversations(key).filter { c in (store.presenceWord(c)?.tier ?? 0) != 0 }.count
        guard lit != 1 else { return members }
        return members + ", " + String(localized: "\(lit) online", bundle: MTLanguage.bundle)
    }

    /// The conversation this phone holds with a pipe: the road to a person's own page and to a letter to them.
    @MainActor
    static func row(of pipe: String, store: ChatStore) -> Chat? {
        let root = MTSamePair.root(pipe)
        return store.listChats().first { c in !c.isGroup && MTSamePair.root(c.convRef) == root }
    }

    /// THE PEOPLE OF A GROUP'S PAGE, AS THE REFERENCE LISTS THEM: the owner first, the administrators after, everyone else by name,
    /// this phone among them under its own name. Each row carries what this phone may do to the person: the owner names and
    /// dismisses administrators and takes anyone out; an administrator takes out a member who is none (MTGroup.kick asks the owner).
    @MainActor
    static func lines(_ key: String, store: ChatStore, remove: @escaping (String) -> Void, open: @escaping (Chat) -> Void,
                      write: @escaping (Chat) -> Void) -> [MTGroupMemberLine] {
        guard let g = MTGroup.shared.state(key) else { return [] }
        let owns = g.mine && g.left != true
        let iAdmin = !g.mine && g.left != true && g.admins?.contains(g.me) == true
        var people = MTGroup.shared.pagePeople(key)
        people.append((seat: g.me, name: MTGroup.clean(E2E.myDisplayName(), MTGroup.nameLimit)))
        func role(_ seat: String) -> MTSpeakerHead.Role? {
            seat == MTGroup.ownerSeat ? .owner : (g.admins?.contains(seat) == true ? .admin : nil)
        }
        func rank(_ seat: String) -> Int { role(seat) == .owner ? 0 : (role(seat) == .admin ? 1 : 2) }
        let ordered = people.sorted { a, b in
            rank(a.seat) != rank(b.seat) ? rank(a.seat) < rank(b.seat) : a.name.localizedCaseInsensitiveCompare(b.name) == .orderedAscending
        }
        return ordered.map { p in
            let me = p.seat == g.me
            let ref = MTGroup.speaker(g.id, p.seat)
            let pipe = me ? nil : MTGroup.shared.speakerPipe(ref)
            let chat = pipe.flatMap { x in row(of: x, store: store) }
            let r = role(p.seat)
            let mayRemove = !me && (owns || (iAdmin && r == nil))
            var deeds: [MTMemberDeed] = []
            if let chat { deeds.append(MTMemberDeed(id: "send", title: "Send Message", icon: "message") { write(chat) }) }
            if owns, !me, g.kind == .group {
                deeds.append(r == .admin
                    ? MTMemberDeed(id: "dismiss", title: "Dismiss Admin", icon: "person.badge.minus") {
                        MTGroup.shared.setAdmin(key, seat: p.seat, on: false, store: store) }
                    : MTMemberDeed(id: "promote", title: "Promote to Admin", icon: "person.badge.shield.checkmark") {
                        MTGroup.shared.setAdmin(key, seat: p.seat, on: true, store: store) })
            }
            if mayRemove {
                deeds.append(MTMemberDeed(id: "remove", title: "Remove from Group", icon: "person.fill.xmark", destructive: true) { remove(p.seat) })
            }
            let name = p.name.isEmpty ? MTGroup.memberWord : p.name
            return MTGroupMemberLine(seat: p.seat, name: name, me: me,
                                     face: pipe.flatMap { x in store.avatarFor(ref: x) }, tint: MTSpeakerFace.tint(ref),
                                     presence: me ? String(localized: "online", bundle: MTLanguage.bundle)
                                                  : pipe.flatMap { x in store.presenceWord(x)?.text },
                                     role: r, deeds: deeds,
                                     remove: mayRemove ? { remove(p.seat) } : nil,
                                     open: chat.map { c in { open(c) } })
        }
    }
}

/// One person of a group's page: what the row shows and what the hand may do there.
struct MTGroupMemberLine {
    let seat: String
    let name: String
    let me: Bool
    let face: String?
    let tint: Color
    let presence: String?
    let role: MTSpeakerHead.Role?
    let deeds: [MTMemberDeed]
    /// The swipe's «Delete»: the person taken out of the group, asked first; nil where this phone may not.
    let remove: (() -> Void)?
    /// The person's own page, where this phone holds their pipe.
    let open: (() -> Void)?
}

/// A PERSON'S ROW ON A GROUP'S PAGE, AS THE REFERENCE DRAWS IT: the face, the name, whether they are online under it, the role on a
/// capsule of its own colour at the right (owner in violet, admin in green); the row opens the person, a hold names the deeds, a swipe
/// to the left takes them out.
struct MTGroupMemberRow: View {
    let line: MTGroupMemberLine
    var body: some View {
        Button { line.open?() } label: {
            HStack(spacing: 12) {
                face
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: line.name).foregroundColor(.white).lineLimit(1)   // USER-DATA: a person's name
                    if let p = line.presence {
                        // USER-DATA: the presence ladder's own word, already in the person's language (ChatStore.presenceWord)
                        Text(verbatim: p).font(.caption).foregroundColor(.gray).lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                if let role = line.role {
                    Text(role.word).font(.subheadline).foregroundColor(role.tint)
                        .padding(.horizontal, 7).padding(.vertical, 2)
                        .background(role.tint.opacity(0.2), in: Capsule())
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .frame(minHeight: 56)
            .contentShape(Rectangle())   // the whole row answers (the author's word 22.09)
        }
        .buttonStyle(.plain)
        .contextMenu {
            ForEach(line.deeds) { d in
                Button(role: d.destructive ? .destructive : nil, action: d.action) { Label(d.title, systemImage: d.icon) }
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if let r = line.remove { Button(role: .destructive, action: r) { Text("Delete") } }
        }
    }
    @ViewBuilder private var face: some View {
        if line.me {
            MTSelfFace(size: 40, initial: MontanaAvatar.initial(title: line.name, name: ""))
        } else {
            AvatarCircle(photoURL: line.face, color: line.tint, initial: MontanaAvatar.initial(title: line.name, name: line.seat), size: 40)
        }
    }
}

/// THE GROUP'S INVITE LINK, AS THE REFERENCE'S PAGE KEEPS IT (stage R.6 laid the link; the author's word 17.10.2026 moves it here
/// from the group's page): the link, «Copy» and «Share» under it, and «Revoke Link» -- a new mark that ends every link given
/// before (MTGroup.resetInvite), asked first. Whoever opens the link meets the owner by its card and asks with the mark.
struct MTGroupInviteLinksPage: View {
    let chat: String
    @State private var link: URL?
    @State private var askRevoke = false
    var body: some View {
        List {
            Section {
                if let link {
                    Text(verbatim: link.absoluteString).foregroundColor(.white).lineLimit(1).truncationMode(.middle)   // USER-DATA: the group's link
                    HStack(spacing: 12) {
                        Button {
                            UIPasteboard.general.url = link
                            UINotificationFeedbackGenerator().notificationOccurred(.success)
                        } label: { Text("Copy").frame(maxWidth: .infinity, minHeight: 44) }
                        .buttonStyle(.borderedProminent)
                        Button { MTShare.present([link]) } label: { Text("Share").frame(maxWidth: .infinity, minHeight: 44) }
                            .buttonStyle(.borderedProminent)
                    }
                    .tint(.blue)
                }
            } header: {
                Text("Invite Link")
            } footer: {
                Text("Anyone who follows this link will be able to join your group.")
            }
            .listRowBackground(MTGlassRowPlate())
            Section {
                Button(role: .destructive) { askRevoke = true } label: {
                    Text("Revoke Link").frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())
                }
            }
            .listRowBackground(MTGlassRowPlate())
        }
        .scrollContentBackground(.hidden).montanaPageGround()
        .navigationTitle("Invite Links").navigationBarTitleDisplayMode(.inline)
        .onAppear { link = MTGroup.shared.inviteLink(chat) }
        .confirmationDialog(Text("Are you sure you want to revoke this link? Once you do, no one will be able to join the group using it."),
                            isPresented: $askRevoke, titleVisibility: .visible) {
            Button("Revoke", role: .destructive) {
                MTGroup.shared.resetInvite(chat)
                link = MTGroup.shared.inviteLink(chat)
            }
        }
    }
}

/// «CLEAR MESSAGES» OF A GROUP, AS THE REFERENCE ASKS IT: every letter and event of the group's feed leaves this phone alone, after the
/// platform's own question; the others keep theirs.
struct MTGroupClearAsk: ViewModifier {
    @EnvironmentObject private var store: ChatStore
    let chat: Chat
    @Binding var asked: Bool
    func body(content: Content) -> some View {
        content.confirmationDialog(Text("Are you sure you want to delete all messages in \(store.title(for: chat))?"),
                                   isPresented: $asked, titleVisibility: .visible) {
            Button("Delete All", role: .destructive) { store.deleteLocally(chat: chat.name, store.messages[chat.name] ?? []) }
        }
    }
}

/// THE LINE UNDER A GROUP'S NAME IN ITS CHAT (the reference's head): who types, while anyone does -- the green dot of the pair's own
/// «typing…» beside it -- and «N members, M online» otherwise. A view of its own, so a typing word redraws this line, never the feed.
struct MTGroupHeadLine: View {
    @EnvironmentObject private var store: ChatStore
    @ObservedObject private var typing = MTGroupTyping.shared
    let key: String
    var body: some View {
        if let t = typing.line(key) {
            HStack(spacing: 4) {
                Circle().fill(Color.green).frame(width: 9, height: 9)
                Text(verbatim: t).truncationMode(.tail)   // USER-DATA: the typing people's names inside the catalogue's sentence
            }
        } else {
            Text(verbatim: MTGroupPage.head(key, store: store))   // USER-DATA: the catalogue's count, said by MTGroupPage.head
        }
    }
}

/// «N SEEN» OVER A GROUP LETTER'S MENU (the reference's small group, the checklist's B.13): how many read a letter of mine, and a tap
/// opens their names under it; «Nobody Viewed» while none has.
struct MTSeenByRow: View {
    let names: [String]
    @State private var open = false
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                if !names.isEmpty { withAnimation(.easeOut(duration: 0.15)) { open.toggle() } }
            } label: {
                HStack {
                    if names.isEmpty { Text("Nobody Viewed") } else { Text("\(names.count) Seen") }
                    Spacer()
                    Image(systemName: "eye").foregroundColor(.white.opacity(0.8))
                }
                .foregroundColor(.white).font(.system(size: 16))
                .padding(.horizontal, 14).padding(.vertical, 11)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if open {
                ForEach(names, id: \.self) { n in
                    Text(verbatim: n).font(.system(size: 15)).foregroundColor(.white.opacity(0.85)).lineLimit(1)   // USER-DATA: a reader's name
                        .padding(.horizontal, 14).padding(.vertical, 6)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }
}

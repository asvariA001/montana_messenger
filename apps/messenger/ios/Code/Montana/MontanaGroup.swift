import Foundation
import UIKit

// ════════════════════════════════════════════════════════════
// GROUPS AND CHANNELS, CARRIED BY THE PHONES THEMSELVES
// ════════════════════════════════════════════════════════════
//
// The author's words 05.10.2026: «Group (choose Contacts to add to the group)», «Channel», «you build the full working function
// of everything». No server holds a group. The owner's phone holds a pipe with every member -- the members were chosen from its
// own correspondents -- and carries every word to the others over those pipes: a star through the owner (the groups and channels
// checklist). A word of a group rides the one delivery queue, one copy per receiver under a name of its own (the queue keeps one
// item per name), and the row it lands as is witnessed by its receivers: a member's letter by the owner's receipt, the owner's
// letter by the receipt of every member.

/// What a group is to its people: a room where everyone writes, or a channel where only its owner posts. Said once, in the
/// invitation, and read the same way on every phone.
enum MTGroupKind: String, Codable {
    case group = "g"
    case channel = "c"
}

/// One person the owner carries the group's words to: the seat the group knows them by, and the pipe this phone holds with them.
/// The seat rides the wire; the pipe never does.
struct MTGroupMember: Codable, Equatable {
    var seat: String
    var pipe: String
}

/// A group as this phone holds it.
struct MTGroupState: Codable, Equatable {
    var id: String
    var kind: MTGroupKind
    var title: String
    var about: String = ""
    /// The pipe to the owner; empty when this phone owns the group.
    var owner: String
    /// This phone's own seat; the owner sits at MTGroup.ownerSeat.
    var me: String
    /// Whom the owner carries to. A member's phone carries to the owner alone and holds nobody here.
    var members: [MTGroupMember]
    /// The people in the group, the owner included, as the owner counted them.
    var count: Int
    /// The name each seat gave itself in its last word: a person's name reaches the group from that person, never from the owner.
    var names: [String: String]
    var at: Double
    /// This phone left the group or its owner took it out: the rows stay readable, nothing is written or taken into it.
    var left: Bool? = nil
    /// Its owner took this phone out (their «out»), as against a leaving by this phone's own hand: the chat's head says which
    /// (the reference folder: «you were removed from the group» / «you have left the group»).
    var removed: Bool? = nil
    /// The seats the owner of a carried group named its administrators (stage R.6, the reference folder: «Promote»): they take
    /// another's letter away for everyone and ask the owner to take a person out. Named by the owner alone, in its invitation.
    var admins: [String]? = nil
    /// The owner's standing mark of the group's invite link (stage R.6, the reference folder: «Invite Link»): whoever opens the
    /// link meets the owner by its card and asks with this mark; a new mark (reset) ends every link given before. Owner only.
    var invite: String? = nil
    /// The face last taken from an invitation, by its length and its tail: a repeat of the same word lays no second file.
    var faceTag: String? = nil
    /// THE GROUP'S FACE ON THIS PHONE, a file of the avatars folder (17.10.2026): the group is its one owner, so the row, the copy
    /// and the notices read it here -- it used to live on the row alone and left with a row a copy did not carry.
    var face: String? = nil
    /// WHO READ THIS PHONE'S LETTERS, AND HOW FAR (the checklist's B.13): each reader's seat with the birth millisecond of the newest
    /// letter they read. This phone's own record, never on the wire; bounded by the group's people (I-14).
    var readBy: [String: Int64]? = nil
    /// THE OWNER'S POSTS GO WITHOUT SOUND (the reference's «Silent Broadcast», the bell in a channel's field): this phone's own choice
    /// for its channel, never on the wire as a setting -- each post says it (MTGroupWord.sl).
    var silent: Bool? = nil
    /// THE CHANNEL SIGNS ITS POSTS (the reference's «Sign Messages»): the owner's choice, carried to every subscriber in its invitation
    /// (MTGroupWord.sg); each post then wears its writer's name in its stamp.
    var signs: Bool? = nil
    /// THE CHANNEL TAKES NO REACTIONS (the reference's «Allow Reactions» off): false -- its owner turned them off; carried to every
    /// subscriber in its invitation (MTGroupWord.rx). Absent -- every reaction is taken.
    var reacts: Bool? = nil
    /// THE PEOPLE ITS OWNER TOOK OUT OF A CHANNEL (the reference's «Removed Users»): they cannot come back by the invite link until the
    /// owner lets them, or adds them again. The owner's phone alone keeps them; nothing of it rides the wire.
    var banned: [MTGroupMember]? = nil
    /// WHAT WAS DONE IN A CHANNEL (the reference's «Recent Actions»): the owner's phone writes each deed as it does it, by the names it
    /// knew then, and shows the last two days. Never on the wire.
    var acts: [MTChannelAct]? = nil
    var mine: Bool { owner.isEmpty }
    var canManage: Bool { mine && left != true }
    /// A group's every person writes; a channel's owner and the administrators it named post (the reference's «Post Messages»).
    var canWrite: Bool { left != true && (kind == .group || mine || admins?.contains(me) == true) }
}

/// One deed of a channel, as its owner's phone wrote it (MTGroupState.acts): what, about whom by the names known then, when.
struct MTChannelAct: Codable, Equatable {
    /// invited, joined, removed, left, title, about, photo, promoted, demoted, signOn, signOff, reactOn, reactOff
    var e: String
    var p: [String]? = nil
    var t: String? = nil
    var at: Double
}

/// A copy of a group's word on its way to one receiver. The queue knows the copy by its own name; the group knows by this record
/// whose row the copy's receipt moves and what to carry again when a hand asks.
struct MTGroupCopy: Codable, Equatable {
    var group: String
    /// The row the copy carries (a bare letter name); empty for an invitation.
    var letter: String
    var to: String
    /// A copy of a letter of mine: its receipt is a witness of my row.
    var own: Bool
    /// How many copies the letter left in: every one of them must be receipted before the row is delivered.
    var of: Int
    var held: Bool
    var failed: Bool
    var text: String
    var at: Double
}

/// The group's word on the wire. «inv» -- the owner to one member: the group, its kind, its title, its words about itself, its face,
/// how many people are in it, the receiver's seat and the owner's own name. «say» -- a letter: its one name, the speaker's seat and
/// the name the speaker gives, the words, the quote. Short keys; a key a reader does not know is skipped by it.
struct MTGroupWord: Codable {
    var t: String
    var g: String
    var k: String? = nil
    var ti: String? = nil
    var ds: String? = nil
    var fc: String? = nil
    var c: Int? = nil
    var me: String? = nil
    var n: String? = nil
    var id: String? = nil
    var s: String? = nil
    var tx: String? = nil
    var qt: String? = nil
    var qm: String? = nil
    /// A room's invitation (MTGroupRoom, 07.10): the seats it is for. It is carried to them alone, and it rings there -- the
    /// notification extension shows it by the group's face though the app is closed.
    var ts: [String]? = nil
    /// 1: the targeted word rings on the seats it is for (an invitation); a room's other targeted word (a key handed to one who came
    /// in) rides silent.
    var rg: Int? = nil
    /// 1 on an «out»: the owner deleted the group for everyone (the reference folder: «Delete for All»). A build that
    /// does not read it takes the word as a taking out -- the group stands there read-only, as the owner's word allows.
    var dl: Int? = nil
    /// The owner's administrators on an «inv» (stage R.6): the seats it named; absent -- none.
    var ad: [String]? = nil
    /// 1 on a channel's post sent without sound (the reference's «Silent Broadcast»): it rides silent and rings nothing where it lands.
    /// A build that does not read it skips the key; the push is silent there too.
    var sl: Int? = nil
    /// 1 on a channel's «inv»: its posts are signed (Sign Messages). A build that does not read it skips the key.
    var sg: Int? = nil
    /// 0 on a channel's «inv»: its posts take no reactions (Allow Reactions off). A build that does not read it skips the key.
    var rx: Int? = nil
}

/// A GROUP'S EVENT, A ROW IN THE MIDDLE OF ITS FEED (stage R, the reference folder's service rows: «X created the group»,
/// «X added Y», «X removed Y», «X left the group», a new name or face, a voice chat begun and ended). The row is this
/// phone's own; the owner's word «ev» names whom an event was about by seat alone -- a person's name reaches the group from
/// that person, never from the owner -- so the row asks the group for the names when it is drawn.
struct MTGroupEvent: Codable {
    static let mark = "\u{200B}\u{200B}GE:"   // COMPAT-LOCAL: this phone's own row, never on the wire
    /// made, added, removed, left, named, face, call, ended
    var e: String
    var g: String
    /// The seat that did it.
    var a: String
    /// The seats it was about.
    var p: [String]? = nil
    /// The group's name (made, named).
    var t: String? = nil
    /// A room's kind (call, ended): call or live; v 1 -- with the camera.
    var k: String? = nil
    var v: Int? = nil
    /// A room's id (call): the row's tap is the way in while the room lives.
    var r: String? = nil
    /// How long the room lived, in seconds (ended).
    var d: Int? = nil
    static func of(_ text: String) -> MTGroupEvent? {
        guard text.hasPrefix(mark), let data = String(text.dropFirst(mark.count)).data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(MTGroupEvent.self, from: data)
    }
    var row: String? {
        guard let data = try? JSONEncoder().encode(self), let js = String(data: data, encoding: .utf8) else { return nil }
        return Self.mark + js
    }
}

/// THE MESSENGER HOLDS NO ORGANISATION'S CHAT (Montana Business's, carried by nobody): every group here is carried by its owner.
/// Said once, so the rooms, the events and the roles read the same words in both apps (stage R).
extension MTGroupState {
    var bosses: [String]? { nil }
}

/// The one owner of groups and channels on this phone: their state, their words on the wire, the owner's carrying and the
/// receipts of every copy. Read and written on the main thread, where the store lands every letter.
final class MTGroup {
    static let shared = MTGroup()
    /// Whether this phone keeps the group's people (adds, takes out, renames, describes): its owner.
    func manages(_ key: String) -> Bool { state(key)?.canManage == true }
    static let mark = "\u{200B}\u{200B}GR:"
    static let stateKey = "groups.held"
    static let copiesKey = "groups.copies"
    // COMPAT-LOCAL: the feed's key and a speaker's reference name rows of this phone; neither rides the wire.
    static let keyHead = "grp:"
    static let speakerHead = "gm:"
    static let ownerSeat = "0"
    static let titleLimit = 64
    static let aboutLimit = 255
    static let nameLimit = 64
    static let faceSide: CGFloat = 160
    static let faceLimit = 24_000
    /// [I-14]: a group is bounded by the people its owner carries to.
    static let peopleLimit = 200
    /// A copy outlives the queue's own term by a day and is let go with it.
    static let copyLife: Double = 8 * 24 * 3600
    /// A channel's post with its files' posters, at most (MTBoard.thumbLimit a poster, MTBoard.mediaLimit files, the words).
    static let wallPostLimit = 1_000_000
    /// [I-14]: the posts one word of a channel's views names, at most (MTChannelViews).
    static let viewsBatch = 200

    private(set) var groups: [String: MTGroupState] = [:]
    private var copies: [String: MTGroupCopy] = [:]
    /// The names of the newest answers already applied: a repeat of a copy is applied once, and carried on once.
    private var answersHeard: [String] = []
    /// [I-15] A MEMBER'S ROOM WORDS ARE TAKEN AT A PERSON'S PACE (07.10, the rooms of Montana Business), by the clock of the phone
    /// that takes them -- a clock the speaker does not set. The owner of a group says every member's room word again to everyone
    /// else -- one word is a copy for every member -- so a member who floods would make phones shout without end. Thirty words a
    /// minute per member are taken; a copy past the pace is not taken (no receipt) and knocks again when the minute has room:
    /// nothing is lost, the pace is a person's. Time, not money, is the barrier.
    static let carriedPerMinute = 30
    private var carriedAt: [String: [Double]] = [:]
    /// A STORE THAT WOULD NOT OPEN IS NOT AN EMPTY ONE (the device key not yet at hand, a launch before the first unlock): it is
    /// read again before it is used, nothing is written over it, and a word that needs it waits unanswered -- one group written
    /// over an unread store would write every other group away.
    private var unread = false

    private init() {
        load()
        NotificationCenter.default.addObserver(forName: .montanaSeedForgotten, object: nil, queue: .main) { [weak self] _ in
            self?.load()   // another person's groups are not this one's
        }
    }
    /// The store was laid again under the living (a copy, a seat lifted): the groups are read from it, never written over it.
    func reread() { load() }
    private func load() {
        let held = MontanaLocalVault.getDecrypted(Self.stateKey).flatMap { try? JSONDecoder().decode([String: MTGroupState].self, from: $0) }
        let kept = MontanaLocalVault.getDecrypted(Self.copiesKey).flatMap { try? JSONDecoder().decode([String: MTGroupCopy].self, from: $0) }
        unread = (held == nil && UserDefaults.standard.data(forKey: Self.stateKey) != nil)
            || (kept == nil && UserDefaults.standard.data(forKey: Self.copiesKey) != nil)
        if unread { MontanaTrace.mark("group_unread", "the groups' store did not open -- read again before use") }
        groups = held ?? [:]
        let edge = Date().timeIntervalSince1970 - Self.copyLife
        copies = (kept ?? [:]).filter { edge < $0.value.at }
    }
    /// The store, read again if it would not open before.
    private func ready() -> Bool {
        if unread { load() }
        return !unread
    }
    private func saveGroups() {
        guard !unread, let d = try? JSONEncoder().encode(groups) else { return }
        MontanaLocalVault.setEncrypted(Self.stateKey, d)
    }
    private func saveCopies() {
        guard !unread, let d = try? JSONEncoder().encode(copies) else { return }
        MontanaLocalVault.setEncrypted(Self.copiesKey, d)
    }

    // -- names ---------------------------------------------------------------------------------

    static func isKey(_ chat: String) -> Bool { chat.hasPrefix(keyHead) }
    static func key(of id: String) -> String { keyHead + id }
    static func id(of key: String) -> String? { isKey(key) ? String(key.dropFirst(keyHead.count)) : nil }
    func state(_ key: String) -> MTGroupState? {
        guard Self.isKey(key), ready() else { return nil }
        return Self.id(of: key).flatMap { groups[$0] }
    }
    func kind(_ key: String) -> MTGroupKind? { state(key)?.kind }
    /// The people in the group, the owner included -- the head of the group's chat and its page say this number.
    func people(_ key: String) -> Int? { state(key)?.count }
    /// The conversations this phone holds with people of the group, by their roots -- the owner's phone with everyone it carries
    /// to, a member's phone with the owner alone: whom it may see online at all. Nothing is asked of anyone here.
    func heldConversations(_ key: String) -> [String] {
        guard let g = state(key) else { return [] }
        return g.mine ? g.members.map { MTSamePair.root($0.pipe) } : [MTSamePair.root(g.owner)]
    }
    /// Whether this phone may write into the group: everyone in a group, the owner alone in a channel.
    func canWrite(_ key: String) -> Bool {
        state(key)?.canWrite == true
    }
    /// Whether this phone is out of the group -- it left, or the owner took it out (the chat says so instead of a field).
    func isOut(_ key: String) -> Bool { state(key)?.left == true }
    /// Whether the owner's phone already carries the group to this person (the page's «add» lists the others only).
    func carries(to pipe: String, in key: String) -> Bool {
        let p = MTSamePair.root(pipe)
        return state(key)?.members.contains { MTSamePair.root($0.pipe) == p } == true
    }
    /// The people of the group's page with their seats: the owner's phone lists everyone it carries to, a member's the owner.
    func shownSeats(_ key: String) -> [(seat: String, name: String)] {
        guard let g = state(key) else { return [] }
        if g.mine { return g.members.map { ($0.seat, shownName(pipe: $0.pipe, said: g.names[$0.seat])) } }
        return [(Self.ownerSeat, shownName(pipe: g.owner, said: g.names[Self.ownerSeat]))]
    }
    /// THE PEOPLE OF A GROUP'S PAGE (stage R, the reference folder lists every member): the owner's phone everyone it carries to;
    /// a member's phone the owner first, then everyone who spoke -- the owner names nobody, the others come by their own words.
    func pagePeople(_ key: String) -> [(seat: String, name: String)] {
        guard let g = state(key) else { return [] }
        var out = shownSeats(key)
        if !g.mine {
            let spoke = g.names.filter { e in e.key != Self.ownerSeat && e.key != g.me && !out.contains(where: { o in o.seat == e.key }) }
            for (seat, n) in spoke.sorted(by: { $0.value.localizedCaseInsensitiveCompare($1.value) == .orderedAscending }) { out.append((seat, n)) }
        }
        return out
    }
    /// The names of the people this phone sees in the group, for its page: the owner's phone names everyone it carries to, a
    /// member's phone names the owner -- the others give their names by their own words.
    func shownPeople(_ key: String) -> [String] {
        guard let g = state(key) else { return [] }
        if g.mine { return g.members.map { shownName(pipe: $0.pipe, said: g.names[$0.seat]) } }
        return [shownName(pipe: g.owner, said: g.names[Self.ownerSeat])]
    }

    static func speaker(_ id: String, _ seat: String) -> String { speakerHead + id + "/" + seat }
    static func isSpeaker(_ ref: String) -> Bool { ref.hasPrefix(speakerHead) }
    /// The name a speaker's rows wear on this phone: the person as this phone knows them where it holds their pipe, else the name
    /// they gave themselves in their last word.
    func speakerName(_ ref: String) -> String {
        guard let at = seatOf(ref) else { return Self.memberWord }
        return shownName(pipe: speakerPipe(ref), said: at.g.names[at.seat])
    }
    /// The pipe this phone holds to a speaker of a group, when it holds one: the owner's phone to every member it carries to, a
    /// member's phone to the owner -- the others are known by their own words alone.
    func speakerPipe(_ ref: String) -> String? {
        guard let at = seatOf(ref) else { return nil }
        return (!at.g.mine && at.seat == Self.ownerSeat) ? at.g.owner : at.g.members.first { m in m.seat == at.seat }?.pipe
    }
    private func seatOf(_ ref: String) -> (g: MTGroupState, seat: String)? {
        let parts = ref.dropFirst(Self.speakerHead.count).split(separator: "/", maxSplits: 1).map(String.init)
        guard ready(), parts.count == 2, let g = groups[parts[0]] else { return nil }
        return (g, parts[1])
    }
    /// The line above a speaker's bubble: in a group, every letter of another says who wrote it; a channel speaks as itself.
    func speakerLine(_ m: Message, in chat: String) -> String? {
        guard !m.isFromMe, let ref = m.senderRef, Self.isSpeaker(ref), state(chat)?.kind == .group else { return nil }
        return speakerName(ref)
    }
    private func shownName(pipe: String?, said: String?) -> String {
        let neutral = String(localized: "Correspondent", bundle: MTLanguage.bundle)
        if let pipe, !pipe.isEmpty {
            let book = MTNameBook.display(conv: pipe)
            if book != neutral, !book.isEmpty { return book }
        }
        let own = said ?? ""
        return own.isEmpty ? Self.memberWord : own
    }
    static var memberWord: String { String(localized: "Member", bundle: MTLanguage.bundle) }

    // -- birth ---------------------------------------------------------------------------------

    /// A GROUP OR A CHANNEL IS BORN ON THIS PHONE: its owner's phone. The people are the correspondents chosen, each by the pipe
    /// this phone holds with them; each is told by an invitation of their own. The row stands in the list at once; the invitations
    /// ride the queue until every one is receipted. A channel may start before it has subscribers.
    @MainActor @discardableResult
    func create(_ kind: MTGroupKind, title: String, about: String = "", face: Data, people: [Chat], store: ChatStore) -> Chat? {
        let name = Self.clean(title, Self.titleLimit)
        var pipes: [String] = []
        for c in people where !c.isGroup {
            let p = MTSamePair.root(c.convRef)
            if MontanaConv.holds(p), !ChatStore.refusesCold(p), !pipes.contains(p) { pipes.append(p) }
        }
        pipes = Array(pipes.prefix(Self.peopleLimit))
        guard ready(), !name.isEmpty, kind == .channel || !pipes.isEmpty else {
            MontanaTrace.mark("group_refused", "birth -- title=\(name.isEmpty ? 0 : 1) people=\(pipes.count) store=\(unread ? 0 : 1)")
            return nil
        }
        let id = Self.mintId()
        var taken: Set<String> = [Self.ownerSeat]
        var members: [MTGroupMember] = []
        for p in pipes {
            var seat = Self.mintSeat()
            while taken.contains(seat) { seat = Self.mintSeat() }
            taken.insert(seat)
            members.append(MTGroupMember(seat: seat, pipe: p))
        }
        let g = MTGroupState(id: id, kind: kind, title: name, about: Self.clean(about, Self.aboutLimit), owner: "", me: Self.ownerSeat,
                             members: members, count: members.count + 1, names: [:], at: Date().timeIntervalSince1970)
        groups[id] = g
        saveGroups()
        let row = stand(g, face: face.isEmpty ? nil : saveChatAvatar(face), store: store)
        lay(MTGroupEvent(e: "made", g: id, a: Self.ownerSeat, t: name), store: store)   // «You created the group»
        let thumb = Self.thumb(face)
        let me = Self.clean(E2E.myDisplayName(), Self.nameLimit)
        for m in members {
            let w = MTGroupWord(t: "inv", g: id, k: kind.rawValue, ti: name, ds: g.about.isEmpty ? nil : g.about, fc: thumb,
                                c: g.count, me: m.seat, n: me.isEmpty ? nil : me)
            _ = carry(w, to: m.pipe, letter: "", own: false)
        }
        MontanaTrace.mark("group_born", "kind=\(kind.rawValue) people=\(g.count)")
        return row
    }

    /// The group's row on the list: the one standing or archived, or a new one at the top. A deleted group's row comes back
    /// with its next word, as a deleted conversation does.
    /// `fresh` -- a word of the group raised it now; a row stood back by standAll keeps its place by its newest letter.
    @MainActor @discardableResult
    private func stand(_ g: MTGroupState, face: String?, store: ChatStore, fresh: Bool = true) -> Chat {
        let key = Self.key(of: g.id)
        // THE GROUP OWNS ITS FACE (17.10.2026): a new face is written into the group's state first; the row and the notices'
        // mirror follow it -- a row lost to a copy or a shelf comes back wearing it, and a banner wears it while the app sleeps.
        if let face, groups[g.id] != nil, groups[g.id]?.face != face {
            groups[g.id]?.face = face
            saveGroups()
            store.mirrorFaceToShared(key)
        }
        if store.deletedChats.contains(key) { store.deletedChats.remove(key) }
        var rows = store.storedChats()
        if let i = rows.firstIndex(where: { $0.id == key }) {
            if rows[i].displayName != g.title || (face != nil && rows[i].photoURL != face) {
                rows[i].displayName = g.title
                if let face { rows[i].photoURL = face }
                store.saveStored(chats: rows)
            }
            return rows[i]
        }
        if let held = store.storedArchived().first(where: { $0.id == key }) { return held }
        let row = Chat(name: key, lastMessage: "", time: nowHHMM(), unread: 0, status: "", photoURL: face ?? groups[g.id]?.face,
                       isGroup: true, isAdmin: g.mine, members: [], convId: key, displayName: g.title)
        rows.insert(row, at: 0)
        store.saveStored(chats: rows)
        if fresh { store.bump(key) }
        return row
    }

    /// EVERY GROUP THIS PHONE HOLDS STANDS ON A SHELF (the author's word 17.10.2026 16:0x MSK: «groups and channels did not come
    /// back»). A group has no pipe, so no builder derives its row from the feed: the row lived on the saved shelf alone, and a
    /// copy that did not carry the shelf -- or a shelf written over -- left the group's state and its feed on the phone with no
    /// row to open them by (the Mac 17.10 16:03 MSK: feed_no_row=grp:9cf40d,grp:3d8826). The group is its row's owner: at every
    /// load and after every copy laid, a held group with no row on either shelf stands one, in its place by its newest letter,
    /// unless the person deleted it; a face only the old row knew is taken into the group once.
    @MainActor
    func standAll(store: ChatStore) {
        guard ready(), !groups.isEmpty else { return }
        let rows = store.storedChats(), archived = store.storedArchived()
        var stood = 0, faces = 0
        for id in groups.keys.sorted() {
            guard let g = groups[id] else { continue }
            let key = Self.key(of: id)
            let row = rows.first { $0.id == key } ?? archived.first { $0.id == key }
            if g.face == nil, let f = row?.photoURL, !f.isEmpty {
                groups[id]?.face = f
                faces += 1
            }
            guard row == nil, !store.deletedChats.contains(key) else { continue }
            stand(groups[id] ?? g, face: nil, store: store, fresh: false)
            stood += 1
        }
        if faces > 0 { saveGroups() }
        if stood + faces > 0 { MontanaTrace.mark("group_stood", "rows=\(stood) faces=\(faces) held=\(groups.count)") }
    }

    // -- the wire ------------------------------------------------------------------------------

    /// One copy to one receiver, under a name of its own. A copy has no row of its own (headless): the row it carries stands in
    /// the group's feed under the letter's name, and the group answers for it (rides) where the mirror law asks. A letter and an
    /// invitation ring (the receiver's phone shows the group's face); an answer, a leaving and a taking out are silent words.
    @MainActor
    private func carry(_ w: MTGroupWord, to pipe: String, letter: String, own: Bool, ring: Bool = false) -> String? {
        guard MontanaConv.holds(pipe), !ChatStore.refusesCold(pipe),
              let d = try? JSONEncoder().encode(w), let js = String(data: d, encoding: .utf8) else { return nil }
        let name = ChatStore.mintMid().mid
        let text = Self.mark + js
        copies[name] = MTGroupCopy(group: w.g, letter: letter, to: pipe, own: own, of: 1, held: false, failed: false,
                                   text: text, at: Date().timeIntervalSince1970)
        saveCopies()
        // A DELETION WAKES THE EXTENSION OF A BUILD THAT PROVED IT TAKES IT QUIETLY (E2E.deletionQuiet, 16.10.2026): the deleted letter's
        // notice leaves that screen at once; the copy waits for its receipt as every group word does.
        let wakes = w.t == "sig" && (w.tx ?? "").hasPrefix(deleteMark) && E2E.deletionQuiet(pipe)
        MontanaDeliveryEngine.shared.enqueue(to: pipe, chat: pipe, mid: name, text: text,
                                             silent: (!ring && w.t != "say" && w.t != "inv" && !wakes) || w.sl == 1,   // a silent post rings nothing
                                             headless: true)   // a room's invitation rings where it is carried (07.10)
        MontanaTrace.mark("group_tx", mid: name, "t=\(w.t) to=\(String(pipe.prefix(10)))")
        return name
    }

    /// A letter to everyone this phone carries to in the group: the owner to every member but the speaker, a member to the owner.
    @MainActor @discardableResult
    private func spread(_ w: MTGroupWord, in g: MTGroupState, except seat: String?, own: Bool) -> Int {
        let targets = g.mine ? g.members.filter { $0.seat != seat }.map(\.pipe) : [g.owner]
        let names = targets.compactMap { carry(w, to: $0, letter: w.id ?? "", own: own) }
        for n in names { copies[n]?.of = names.count }
        if !names.isEmpty { saveCopies() }
        return names.count
    }

    /// A LETTER OF MINE INTO A GROUP (ChatStore.send): the row is born in the group's feed under its one name, on the clock, and a
    /// copy leaves for every receiver; the receipts raise the row (follow). A channel takes the owner's letters alone.
    @MainActor
    func send(_ text: String, in key: String, replyText: String?, replyToId: MID?, replyWireMid: String?, store: ChatStore) -> Message {
        guard let g = state(key), canWrite(key), Self.carries(text) else {
            MontanaTrace.mark("group_refused", "send -- group=\(state(key) == nil ? 0 : 1) chat=\(String(key.prefix(12)))")
            return Message(text: "", isFromMe: true, time: nowHHMM(), senderRef: nil)
        }
        let minted = ChatStore.mintMid()
        let row = Message(text: text, isFromMe: true, time: nowHHMM(), replyText: replyText, deliveryStatus: .sending,
                          msgId: "mid:" + minted.mid, senderRef: nil, createdAt: Double(minted.ms) / 1000, replyToId: replyToId)
        store.append(key, row)
        let me = Self.clean(E2E.myDisplayName(), Self.nameLimit)
        // The title and the kind ride every letter: the receiver's notification extension holds no group, and names it by these.
        var w = MTGroupWord(t: "say", g: g.id, k: g.kind.rawValue, ti: g.title, n: me.isEmpty ? nil : me, id: minted.mid, s: g.me, tx: text,
                            qt: (replyText ?? "").isEmpty ? nil : replyText, qm: (replyWireMid ?? "").isEmpty ? nil : replyWireMid)
        w.sl = g.kind == .channel && g.silent == true ? 1 : nil   // the bell in the owner's field (Silent Broadcast)
        if spread(w, in: g, except: nil, own: true) == 0, let i = store.messages[key]?.firstIndex(where: { $0.mid == row.mid }) {
            store.putOut(key, i, because: .nobodyToCarry)   // nobody to carry to: honest red, a hand may try again
        }
        return row
    }

    /// A MEDIA LETTER OF MINE INTO A GROUP (ChatStore.sendMediaToPeer, the author's words 06.10.2026 14:2x-14:3x MSK: «groups and channels with the whole of a chat, every kind of data»): its row
    /// stands in the feed already under its one name, its file's sealed pieces lie on the nodes for every receiver -- the node's term
    /// keeps them, no single receipt takes them away -- and its manifest leaves to everyone this phone carries to, as a word does.
    @MainActor
    func carryMedia(_ letter: String, mid: String, in key: String, store: ChatStore) -> Bool {
        guard let g = state(key), canWrite(key), Self.carries(letter), letter.hasPrefix(mediaMark), Self.isLetterName(mid) else {
            MontanaTrace.mark("group_refused", "media -- group=\(state(key) == nil ? 0 : 1) chat=\(String(key.prefix(12)))")
            return false
        }
        let me = Self.clean(E2E.myDisplayName(), Self.nameLimit)
        var w = MTGroupWord(t: "say", g: g.id, k: g.kind.rawValue, ti: g.title, n: me.isEmpty ? nil : me, id: mid, s: g.me, tx: letter)
        w.sl = g.kind == .channel && g.silent == true ? 1 : nil   // the bell in the owner's field (Silent Broadcast)
        return spread(w, in: g, except: nil, own: true) > 0
    }

    /// A CHANNEL'S POST LEAVES TO EVERY SUBSCRIBER (the author's words 06.10.2026 14:2x-14:4x MSK: «in channels posts as on the Wall of Thoughts; everything the wall publishes is published in a channel», MTBoard.lay): the wall's own word «cpost» with the post as the channel's page
    /// shows it, carried as the channel's letter; no row of the feed is born of it (ChatStore.append hands a wall's word to MTBoard).
    @MainActor
    func carryWallPost(_ post: MTBoardSeen, in key: String) {
        guard let g = state(key), g.canManage, g.kind == .channel,
              let d = try? JSONEncoder().encode(MTBoardWord(t: "cpost", ps: [post])), let js = String(data: d, encoding: .utf8) else { return }   // COMPAT-GATED: a channel's carrier alone
        let text = MTBoard.mark + js
        guard Self.carries(text) else { MontanaTrace.mark("group_refused", "wall post bytes=\(text.utf8.count)"); return }
        let me = Self.clean(E2E.myDisplayName(), Self.nameLimit)
        let w = MTGroupWord(t: "say", g: g.id, k: g.kind.rawValue, ti: g.title, n: me.isEmpty ? nil : me, id: ChatStore.mintMid().mid, s: g.me, tx: text)
        let n = spread(w, in: g, except: nil, own: false)
        MontanaTrace.mark("group_tx", "wall post copies=\(n)")
    }

    /// A HAND ASKED AGAIN (the red mark's retry): every copy of the letter that has not been receipted rides again under its own
    /// name; a letter with no copy left leaves anew to everyone. The row goes back to the clock by the one road back.
    @MainActor
    func resend(_ m: Message, in key: String, store: ChatStore) {
        guard let g = state(key), g.canWrite, m.isFromMe,
              let i = store.messages[key]?.firstIndex(where: { $0.mid == m.mid }) else { return }
        let letter = m.mid.hasPrefix("mid:") ? String(m.mid.dropFirst(4)) : m.mid
        store.restartSend(key, i)
        let waiting = copies.filter { $0.value.own && $0.value.group == g.id && $0.value.letter == letter }
        if waiting.isEmpty {
            let me = Self.clean(E2E.myDisplayName(), Self.nameLimit)
            let w = MTGroupWord(t: "say", g: g.id, k: g.kind.rawValue, ti: g.title, n: me.isEmpty ? nil : me, id: letter, s: g.me, tx: m.text,
                                qt: (m.replyText ?? "").isEmpty ? nil : m.replyText)
            if spread(w, in: g, except: nil, own: true) == 0 { store.putOut(key, i, because: .nobodyToCarry) }
            MontanaTrace.mark("group_resend", mid: letter, "anew")
            return
        }
        for (name, c) in waiting {
            copies[name]?.failed = false
            MontanaDeliveryEngine.shared.enqueue(to: c.to, chat: c.to, mid: name, text: c.text, silent: false, headless: true)
        }
        saveCopies()
        MontanaDeliveryEngine.shared.drainAll()
        MontanaTrace.mark("group_resend", mid: letter, "copies=\(waiting.count)")
    }

    /// AN ANSWER TO A LETTER -- a reaction, an edit of one's own words, a deletion of one's own letter for everyone: carried to
    /// every phone of the group like a letter and applied by the same road in the group's feed (ChatStore.append), where it is
    /// never a row. Everyone in a group or a channel may react; only a letter's own speaker changes or takes it away (answered).
    @MainActor
    func signal(_ text: String, in key: String, store: ChatStore) {
        guard let g = state(key), g.left != true, Self.answers(text) else {
            MontanaTrace.mark("group_refused", "answer -- group=\(state(key) == nil ? 0 : 1)")
            return
        }
        let me = Self.clean(E2E.myDisplayName(), Self.nameLimit)
        let w = MTGroupWord(t: "sig", g: g.id, n: me.isEmpty ? nil : me, id: ChatStore.mintMid().mid, s: g.me, tx: text)
        spread(w, in: g, except: nil, own: false)
    }

    /// A LETTER TAKEN AWAY FOR EVERYONE STAYS ON THIS PHONE (MTLevelAccess.keepDeleted, 16.10.2026 No.72): its speaker alone is told, by
    /// the keeper's own word «kp» -- the owner carries it to that seat; in a channel the speaker is its owner. An older build refuses the
    /// word and receipts it (take's default): its speaker is not told there.
    @MainActor
    func tellKept(_ sid: String, speaker ref: String?, in key: String) {
        guard let g = state(key), g.left != true else { return }
        let head = Self.speaker(g.id, "")
        let seat = ref.flatMap { r in r.hasPrefix(head) ? String(r.dropFirst(head.count)) : nil } ?? Self.ownerSeat
        guard seat != g.me else { return }
        var w = MTGroupWord(t: "kp", g: g.id, id: ChatStore.mintMid().mid, s: g.me, tx: deleteKeptMark + sid)   // COMPAT-GATED: refused by older builds
        w.ts = [seat]
        if g.mine {
            if let m = g.members.first(where: { m in m.seat == seat }) { _ = carry(w, to: m.pipe, letter: "", own: false) }
        } else {
            _ = carry(w, to: g.owner, letter: "", own: false)
        }
        MontanaTrace.mark("delete_kept", "group word to seat=\(seat)")
    }
    /// The keeper's word reached the group: the owner carries it on to the speaker's seat; the speaker is told.
    @MainActor
    private func keptHeard(_ w: MTGroupWord, from pipe: String) -> Verdict {
        guard let g = groups[w.g], g.left != true, let to = w.ts?.first, (w.tx ?? "").hasPrefix(deleteKeptMark) else { return .refused }
        let from = MTSamePair.root(pipe)
        if g.mine {
            guard g.members.contains(where: { m in MTSamePair.root(m.pipe) == from }) else { return .refused }   // a member's word alone
            if to != g.me {
                if let m = g.members.first(where: { m in m.seat == to }) { _ = carry(w, to: m.pipe, letter: "", own: false) }
                return .held
            }
        } else {
            guard MTSamePair.root(g.owner) == from, to == g.me else { return .refused }
        }
        let key = Self.key(of: g.id)
        MontanaNotify.presentKept(from: key, chat: key)
        return .held
    }

    /// A ROOM'S WORD INTO THE GROUP (MTGroupRoom, the author's word 07.10.2026 00:2x MSK: calls of up to 13 in a group): carried like
    /// an answer -- to everyone this phone carries to, the owner passing it on -- and never a row.
    /// An invitation (ask) goes to the seats chosen alone and rings there; the owner of a group it carries passes it on to them only.
    @MainActor
    func room(_ text: String, in key: String, ask seats: [String]? = nil, ring: Bool = true) {
        guard let g = state(key), g.left != true, text.utf8.count <= 4096 else { return }
        let me = Self.clean(E2E.myDisplayName(), Self.nameLimit)
        // the title and the kind ride the word: the receiver's notification extension holds no group, and names it by these
        var w = MTGroupWord(t: "room", g: g.id, k: g.kind.rawValue, ti: g.title, n: me.isEmpty ? nil : me, id: ChatStore.mintMid().mid,
                            s: g.me, tx: text)
        guard let seats, !seats.isEmpty else { spread(w, in: g, except: nil, own: false); return }
        w.ts = Array(seats.prefix(32))
        w.rg = ring ? 1 : 0   // said either way: a word naming no ring is read as an invitation of build 52-54
        if g.mine {
            for m in g.members where seats.contains(m.seat) { _ = carry(w, to: m.pipe, letter: "", own: false, ring: ring) }
        } else {
            _ = carry(w, to: g.owner, letter: "", own: false, ring: ring && seats.contains(Self.ownerSeat))
        }
    }

    // -- administrators (stage R.6, the reference folder: «Promote», «Dismiss Admin») ---------------------------------

    /// WHETHER THE OWNER'S NEXT POSTS GO WITHOUT SOUND (the bell in a channel's field): its owner's choice, kept on its state.
    func silent(_ key: String) -> Bool { state(key)?.silent == true }
    func setSilent(_ key: String, on: Bool) {
        guard var g = state(key), g.canManage, g.kind == .channel else { return }
        g.silent = on ? true : nil
        groups[g.id] = g
        saveGroups()
        MontanaTrace.mark("channel_silent", on ? "on" : "off")
    }
    /// [I-14]: the deeds a channel's owner keeps for its Recent Actions.
    static let actsKept = 100
    /// A DEED OF A CHANNEL, WRITTEN ON ITS OWNER'S PHONE (MTGroupState.acts): a group keeps its events in its feed instead.
    private func act(_ g: inout MTGroupState, _ e: String, p: [String]? = nil, t: String? = nil) {
        guard g.kind == .channel, g.mine else { return }
        var list = g.acts ?? []
        list.append(MTChannelAct(e: e, p: p, t: t, at: Date().timeIntervalSince1970))
        if Self.actsKept < list.count { list.removeFirst(list.count - Self.actsKept) }
        g.acts = list
    }
    /// The name a seat of a group is known by on this phone, as the page lists it.
    func seatName(_ g: MTGroupState, _ seat: String) -> String {
        if let m = g.members.first(where: { m in m.seat == seat }) { return shownName(pipe: m.pipe, said: g.names[seat]) }
        if let b = g.banned?.first(where: { b in b.seat == seat }) { return shownName(pipe: b.pipe, said: g.names[seat]) }
        return shownName(pipe: nil, said: g.names[seat])
    }
    /// THE OWNER LETS A REMOVED PERSON BACK BY THE LINK (the reference's «Removed Users», Delete): they are no longer kept out.
    @MainActor
    func unban(_ key: String, seat: String) {
        guard var g = state(key), g.canManage, g.banned?.contains(where: { b in b.seat == seat }) == true else { return }
        g.banned = g.banned?.filter { b in b.seat != seat }.nilIfEmpty
        groups[g.id] = g
        saveGroups()
        MontanaTrace.mark("channel_banned", "let back")
    }
    /// The people its owner took out of a channel, by the names they were known by, for its Removed Users.
    func bannedPeople(_ key: String) -> [(seat: String, name: String, pipe: String)] {
        guard let g = state(key) else { return [] }
        return (g.banned ?? []).map { b in (b.seat, seatName(g, b.seat), b.pipe) }
    }
    /// Whether a channel's posts take reactions (Allow Reactions): on unless its owner turned them off.
    func reactionsOn(_ key: String) -> Bool { state(key)?.reacts != false }
    /// THE OWNER ALLOWS REACTIONS OR TURNS THEM OFF: every subscriber hears it by the invitation's word again.
    @MainActor
    func setReactions(_ key: String, on: Bool) {
        guard var g = state(key), g.canManage, g.kind == .channel else { return }
        g.reacts = on ? nil : false
        act(&g, on ? "reactOn" : "reactOff")
        groups[g.id] = g
        saveGroups()
        tellAll(g, face: nil)
        MontanaTrace.mark("channel_reactions", on ? "on" : "off")
    }
    /// THE OWNER SIGNS THE CHANNEL'S POSTS OR STOPS (Sign Messages): every subscriber hears it by the invitation's word again.
    @MainActor
    func setSigns(_ key: String, on: Bool) {
        guard var g = state(key), g.canManage, g.kind == .channel else { return }
        g.signs = on ? true : nil
        act(&g, on ? "signOn" : "signOff")
        groups[g.id] = g
        saveGroups()
        tellAll(g, face: nil)
        MontanaTrace.mark("channel_signs", on ? "on" : "off")
    }
    /// The name a signed channel's post wears in its stamp: its writer's -- this phone's own name on its own posts, the speaker's
    /// on another's. Nil where the channel does not sign.
    func signature(_ m: Message, in key: String) -> String? {
        guard let g = state(key), g.kind == .channel, g.signs == true else { return nil }
        if m.isFromMe { let me = Self.clean(E2E.myDisplayName(), Self.nameLimit); return me.isEmpty ? nil : me }
        guard let ref = m.senderRef, Self.isSpeaker(ref) else { return nil }
        let name = speakerName(ref)
        return name == Self.memberWord ? nil : name
    }
    /// Whether a seat is an administrator the owner of a carried group named.
    func isAdmin(_ key: String, seat: String) -> Bool { state(key)?.admins?.contains(seat) == true }
    /// Whether this phone moderates the group: its owner, an administrator the owner named, or -- in an organisation's chat -- an
    /// administrator of the core. A moderator takes another's letter away for everyone.
    func moderates(_ key: String) -> Bool {
        guard let g = state(key), g.left != true else { return false }   // a channel's administrators delete posts too (the reference's)
        return g.mine || g.admins?.contains(g.me) == true
    }
    /// THE OWNER NAMES OR DISMISSES AN ADMINISTRATOR: every member hears the list by the invitation's word again.
    @MainActor
    func setAdmin(_ key: String, seat: String, on: Bool, store: ChatStore) {
        guard var g = state(key), g.canManage, g.members.contains(where: { $0.seat == seat }) else { return }   // a group's or a channel's
        var list = Set(g.admins ?? [])
        if on { list.insert(seat) } else { list.remove(seat) }
        g.admins = Array(list).sorted().nilIfEmpty
        act(&g, on ? "promoted" : "demoted", p: [seatName(g, seat)])
        groups[g.id] = g
        saveGroups()
        tellAll(g, face: nil)
        MontanaTrace.mark("group_admin", on ? "named" : "dismissed")
    }
    /// AN ADMINISTRATOR TAKES A PERSON OUT: the owner's phone carries the group, so the word goes to the owner, who takes the person
    /// out as by its own hand -- never the owner, never another administrator. The owner's phone is the one that can.
    @MainActor
    func kick(_ key: String, seat: String, store: ChatStore) {
        guard let g = state(key), g.left != true else { return }
        if g.mine { remove(key, seat: seat, store: store); return }
        guard g.admins?.contains(g.me) == true, seat != Self.ownerSeat, seat != g.me, g.admins?.contains(seat) != true else { return }
        _ = carry(MTGroupWord(t: "kk", g: g.id, id: ChatStore.mintMid().mid, s: seat), to: g.owner, letter: "", own: false)   // COMPAT-GATED
        MontanaTrace.mark("group_admin", "asked the owner to take a person out")
    }
    /// An administrator's «take out» at the owner's phone: the asker must be an administrator, the person a member who is none.
    @MainActor
    private func kicked(_ w: MTGroupWord, from pipe: String, store: ChatStore) -> Verdict {
        guard let g = groups[w.g], g.mine, g.left != true else { return .refused }
        let from = MTSamePair.root(pipe)
        guard let asker = g.members.first(where: { MTSamePair.root($0.pipe) == from }), g.admins?.contains(asker.seat) == true,
              let target = w.s, target != asker.seat, g.admins?.contains(target) != true,
              g.members.contains(where: { $0.seat == target }) else { return .refused }
        remove(Self.key(of: g.id), seat: target, by: asker.seat, store: store)
        return .held
    }

    // -- typing (the checklist's B.12, the reference's «X is typing» in a group's head) ------------------------------------------

    /// A GROUP'S TYPING WORD LIVES AN INSTANT: thrown at the pipes this phone holds for the group (E2E.signalNow) -- the owner's phone
    /// at every member, a member's at the owner -- never queued, never receipted, never written; the owner throws a member's word on.
    /// TYPING IS PRESENCE (the critic's K-G1): a phone that hides its presence says none, and none to a person it blocked
    /// (MontanaPresencePrivacy.saysTyping). A person blocks only whom they hold a pipe with -- in a group, the owner for a member and
    /// the members for the owner -- so the check at the sender covers every block there is; the owner, carrying another's word on,
    /// passes it to nobody it blocked itself and takes none from a person it blocked.
    @MainActor
    func typing(in key: String) {
        guard MontanaPresencePrivacy.sharing, let g = state(key), g.canWrite, g.kind == .group else { return }
        let me = Self.clean(E2E.myDisplayName(), Self.nameLimit)
        throwTyping(MTGroupWord(t: "ty", g: g.id, n: me.isEmpty ? nil : me, s: g.me), to: g.mine ? g.members.map(\.pipe) : [g.owner], own: true)
    }
    private func throwTyping(_ w: MTGroupWord, to pipes: [String], own: Bool) {
        guard let d = try? JSONEncoder().encode(w), let js = String(data: d, encoding: .utf8) else { return }
        let text = Self.mark + js
        for p in pipes where MontanaConv.holds(p) && !ChatStore.refusesCold(p) {
            guard own ? MontanaPresencePrivacy.saysTyping(to: p) : !E2E.shared.silenced(p) else { continue }
            Task { _ = await E2E.shared.signalNow(to: p, text) }
        }
    }
    /// The moment the owner last threw a member's typing on: once in two seconds a member, whatever it sends (I-15).
    private var typingThrown: [String: Date] = [:]
    /// A TYPING WORD LANDED: its seat proven by the pipe it came by, as every word's is; the head shows it for the store's typing life.
    @MainActor
    private func typed(_ w: MTGroupWord, from pipe: String) -> Verdict {
        guard var g = groups[w.g], g.left != true, g.kind == .group else { return .instant }
        let from = MTSamePair.root(pipe)
        let seat: String
        if g.mine {
            guard let m = g.members.first(where: { x in MTSamePair.root(x.pipe) == from }), !E2E.shared.silenced(m.pipe) else { return .instant }
            seat = m.seat
            let k = g.id + "/" + seat
            if Date().timeIntervalSince(typingThrown[k] ?? .distantPast) >= 2 {
                typingThrown[k] = Date()
                var on = w
                on.s = seat
                throwTyping(on, to: g.members.filter { x in x.seat != seat }.map(\.pipe), own: false)
            }
        } else {
            guard MTSamePair.root(g.owner) == from, let s = w.s, Self.isSeat(s), s != g.me else { return .instant }
            seat = s
        }
        let name = Self.clean(w.n ?? "", Self.nameLimit)
        if !name.isEmpty, g.names[seat] != name {   // a person's name reaches the group from that person, as their letters bring it
            g.names[seat] = name
            groups[w.g] = g
            saveGroups()
        }
        MTGroupTyping.shared.heard(Self.key(of: g.id), seat: seat)
        return .instant
    }

    // -- read (the checklist's B.13, the reference's small group: a letter read, and who saw it) -------------------------------

    /// How far this phone told each group it read: one word a moment, and only when the mark moved. Held in memory: a launch tells once.
    private var readTold: [String: Int64] = [:]
    /// How far the owner told the speakers each reader read, by group and reader: a speaker hears of a reader once for each new reach.
    private var readSpread: [String: Int64] = [:]
    /// THIS PHONE READ THE GROUP (ChatStore.sendReadMark, on the chat's own terms: on screen, the app active): the birth millisecond of
    /// the newest letter of another before its eyes, told as its own word «rd» to the owner, who tells each speaker it covers; the
    /// owner's own reading goes to them straight. Never with «Read receipts» off -- the switch is the person's word about themselves.
    @MainActor
    func tellRead(_ key: String, store: ChatStore) {
        guard ChatStore.readReceiptsOn, let g = state(key), g.kind == .group, g.left != true,
              let newest = (store.messages[key] ?? []).compactMap({ r in r.isFromMe ? nil : r.msgId.flatMap(ChatStore.birthMs(fromMid:)) }).max()
        else { return }
        let ms = Int64(newest * 1000)
        guard (readTold[g.id] ?? 0) < ms else { return }
        readTold[g.id] = ms
        if g.mine {
            spreadRead(g, reader: g.me, upTo: ms, store: store)
        } else {
            _ = carry(MTGroupWord(t: "rd", g: g.id, id: ChatStore.mintMid().mid, s: g.me, tx: String(ms)), to: g.owner, letter: "", own: false)
        }
        MontanaTrace.mark("read_tx", "group upto=\(MontanaTrace.shortMs(ms))")
    }
    /// THE OWNER TELLS EACH SPEAKER A READER REACHED: the seats of the letters born after the reader's last mark and up to the new one,
    /// each told by a word of its own (ts names it); the owner's own letters are read here.
    @MainActor
    private func spreadRead(_ g: MTGroupState, reader: String, upTo ms: Int64, store: ChatStore) {
        let key = Self.key(of: g.id)
        let head = Self.speaker(g.id, "")
        let k = g.id + "/" + reader
        let since = readSpread[k] ?? 0
        guard since < ms else { return }
        readSpread[k] = ms
        var speakers = Set<String>()
        for r in store.messages[key] ?? [] {
            guard let b = r.msgId.flatMap(ChatStore.birthMs(fromMid:)) else { continue }
            let born = Int64(b * 1000)
            guard since < born, born <= ms else { continue }
            let seat = r.isFromMe ? g.me : (r.senderRef.flatMap { x in x.hasPrefix(head) ? String(x.dropFirst(head.count)) : nil } ?? "")
            if !seat.isEmpty, seat != reader { speakers.insert(seat) }
        }
        if speakers.contains(g.me) { readMine(g.id, reader: reader, upTo: ms, store: store) }
        for m in g.members where speakers.contains(m.seat) {
            var w = MTGroupWord(t: "rd", g: g.id, id: ChatStore.mintMid().mid, s: reader, tx: String(ms))
            w.ts = [m.seat]
            _ = carry(w, to: m.pipe, letter: "", own: false)
        }
    }
    /// A READER REACHED MY LETTERS: their mark kept, and every letter of mine born up to it raised to read through the ladder's one
    /// door (delivered first, as everywhere).
    @MainActor
    private func readMine(_ gid: String, reader: String, upTo ms: Int64, store: ChatStore) {
        guard var g = groups[gid], reader != g.me else { return }
        var by = g.readBy ?? [:]
        guard (by[reader] ?? 0) < ms else { return }
        by[reader] = ms
        g.readBy = by
        groups[gid] = g
        saveGroups()
        let key = Self.key(of: gid)
        var raised = 0
        for (i, r) in (store.messages[key] ?? []).enumerated() where r.isFromMe {
            if let b = r.msgId.flatMap(ChatStore.birthMs(fromMid:)), Int64(b * 1000) <= ms, store.advance(key, i, to: .read) { raised += 1 }
        }
        MontanaTrace.mark("read_rx", "group upto=\(MontanaTrace.shortMs(ms)) raised=\(raised)")
    }
    /// A READER'S WORD: at the owner, the reader's seat is the pipe's own, never the word's; at a member, the owner's word for a
    /// reader of this phone's letters.
    @MainActor
    private func readWord(_ w: MTGroupWord, from pipe: String, store: ChatStore) -> Verdict {
        guard let g = groups[w.g] else { return .wait }
        guard g.left != true, g.kind == .group, let tx = w.tx, let ms = Int64(tx), 0 < ms else { return .refused }
        let from = MTSamePair.root(pipe)
        if g.mine {
            guard let m = g.members.first(where: { x in MTSamePair.root(x.pipe) == from }) else { return .refused }
            spreadRead(g, reader: m.seat, upTo: ms, store: store)
        } else {
            guard MTSamePair.root(g.owner) == from, let s = w.s, Self.isSeat(s), s != g.me, w.ts?.contains(g.me) != false else { return .refused }
            readMine(g.id, reader: s, upTo: ms, store: store)
        }
        return .held
    }
    /// THE READERS OF A LETTER OF MINE, BY NAME (the reference's «N Seen» over the letter's menu): the seats whose mark covers its birth.
    func seenBy(_ m: Message, in key: String) -> [String]? {
        guard let g = state(key), g.kind == .group, m.isFromMe, let b = m.msgId.flatMap(ChatStore.birthMs(fromMid:)) else { return nil }
        let ms = Int64(b * 1000)
        return (g.readBy ?? [:]).filter { e in ms <= e.value }.map { e in speakerName(Self.speaker(g.id, e.key)) }
            .sorted { a, b in a.localizedCaseInsensitiveCompare(b) == .orderedAscending }
    }

    // -- the pin for everyone (the checklist's B.11, the reference: «Pin this message and notify all members of the group?») ------

    /// WHO PINS FOR EVERYONE: the owner, the administrators it named, and every member while the group lets its members pin -- the
    /// reference's own default for a new group. The one gate of a pin, asked where it is sent and wherever it lands.
    static func pins(_ g: MTGroupState, seat: String) -> Bool {
        guard g.kind == .group else { return false }
        return seat == ownerSeat || g.admins?.contains(seat) == true || true
    }
    /// Whether this phone pins a letter of the group for everyone.
    func mayPin(_ key: String) -> Bool {
        guard let g = state(key), g.left != true else { return false }
        return Self.pins(g, seat: g.me)
    }
    /// A LETTER PINNED OR UNPINNED FOR EVERYONE: here at once, and its word carried to every phone of the group by the answers' road
    /// (signal); a pin lays the reference's service row. `notify` -- «Pin and notify all members» against «Only Pin».
    @MainActor
    func pin(_ m: Message, pin: Bool, notify: Bool, in key: String, store: ChatStore) {
        guard let g = state(key), g.left != true, Self.pins(g, seat: g.me), let sid = m.msgId, sid.hasPrefix("mid:") else { return }
        if pin { store.pin(key, m.id) } else { store.unpin(key, m.id) }
        var o: [String: Any] = ["sid": sid, "op": pin ? "pin" : "unpin"]
        if !notify { o["n"] = 0 }
        guard let d = try? JSONSerialization.data(withJSONObject: o), let body = String(data: d, encoding: .utf8) else { return }
        signal(pinMark + body, in: key, store: store)
        if pin { lay(MTGroupEvent(e: "pinned", g: g.id, a: g.me, t: Self.pinWords(m)), store: store) }
        MontanaTrace.mark("pin_tx", "group \(pin ? "pin" : "unpin") notify=\(notify ? 1 : 0)")
    }
    /// The pinned letter's words as its service row quotes them: the list's own preview, cut at forty letters.
    static func pinWords(_ m: Message) -> String {
        let w = ChatStore.listPreview(m)
        return w.count <= 40 ? w : String(w.prefix(40)) + "…"
    }

    // -- a channel's views (the reference's eye under a post; MTChannelViews) ---------------------------------

    /// A SUBSCRIBER'S SCREEN SHOWED POSTS: their names go to the owner alone, silent, for its count. False -- no pipe to the owner
    /// now, and the posts wait. It is a read receipt: with the person's «Read receipts» off the word never leaves (the critic's K-1).
    @MainActor
    func tellSeen(_ letters: [String], in key: String) -> Bool {
        guard ChatStore.readReceiptsOn, let g = state(key), g.kind == .channel, !g.mine, g.left != true, !letters.isEmpty else { return false }
        let w = MTGroupWord(t: "vw", g: g.id, id: ChatStore.mintMid().mid, s: g.me,
                            tx: letters.prefix(Self.viewsBatch).joined(separator: ","))   // COMPAT-GATED: an older owner refuses «vw»
        return carry(w, to: g.owner, letter: "", own: false) != nil
    }
    /// THE OWNER TELLS THE NUMBERS THAT MOVED: one silent word to every subscriber.
    @MainActor
    func tellCounts(_ numbers: [String: Int], in key: String) {
        guard let g = state(key), g.kind == .channel, g.canManage, !numbers.isEmpty,
              let d = try? JSONEncoder().encode(numbers), let js = String(data: d, encoding: .utf8) else { return }
        let w = MTGroupWord(t: "vc", g: g.id, id: ChatStore.mintMid().mid, s: Self.ownerSeat, tx: js)   // COMPAT-GATED: an older subscriber refuses «vc»
        for m in g.members { _ = carry(w, to: m.pipe, letter: "", own: false) }
    }
    /// A subscriber's posts shown, at the owner's phone: the seat is the pipe's, and only posts of this channel's feed count.
    @MainActor
    private func seen(_ w: MTGroupWord, from pipe: String, store: ChatStore) -> Verdict {
        guard let g = groups[w.g] else { return .wait }
        let from = MTSamePair.root(pipe)
        guard g.mine, g.kind == .channel, g.left != true, let tx = w.tx, tx.utf8.count <= 16_384,
              let m = g.members.first(where: { MTSamePair.root($0.pipe) == from }) else { return .refused }
        let key = Self.key(of: g.id)
        let posts = Set((store.messages[key] ?? []).filter { m in MTGroupEvent.of(m.text) == nil }.map(\.mid))   // the owner's posts and its administrators'
        let letters = tx.split(separator: ",").prefix(Self.viewsBatch).map(String.init)
            .filter { Self.isLetterName($0) && posts.contains("mid:" + $0) }
        MTChannelViews.shared.saw(letters, by: m.seat, in: key)
        return .held
    }
    /// The owner's numbers, at a subscriber's phone: from the owner's pipe alone.
    @MainActor
    private func counted(_ w: MTGroupWord, from pipe: String) -> Verdict {
        guard let g = groups[w.g] else { return .wait }
        guard !g.mine, g.kind == .channel, g.left != true, MTSamePair.root(g.owner) == MTSamePair.root(pipe),
              let tx = w.tx, tx.utf8.count <= 16_384, let d = tx.data(using: .utf8),
              let numbers = try? JSONDecoder().decode([String: Int].self, from: d) else { return .refused }
        MTChannelViews.shared.told(numbers.filter { Self.isLetterName($0.key) }, in: Self.key(of: g.id))
        return .held
    }

    // -- the group's invite link (stage R.6, the reference folder: «Invite Link», «joined the group via invite link») -------------

    /// The link the owner hands out: its own card (whoever opens it meets the owner, the one road of a link) and the group's mark
    /// after it (jg). Nil -- no card stands yet, or this phone is no owner of the group.
    @MainActor
    func inviteLink(_ key: String) -> URL? {
        guard var g = state(key), g.canManage,
              let card = MontanaCard.offerStanding(), var parts = URLComponents(string: card) else { return nil }
        if g.invite == nil {
            g.invite = Self.mintId()
            groups[g.id] = g
            saveGroups()
        }
        parts.queryItems = (parts.queryItems ?? []) + [URLQueryItem(name: Self.joinParam, value: g.id + "." + (g.invite ?? ""))]
        return parts.url
    }
    /// A new mark: every link given before opens nothing more (the reference's «Reset Link»).
    @MainActor
    func resetInvite(_ key: String) {
        guard var g = state(key), g.canManage else { return }
        g.invite = Self.mintId()
        groups[g.id] = g
        saveGroups()
        MontanaTrace.mark("group_link", "reset")
    }
    static let joinParam = "jg"
    /// A group's id or a link's mark: 32 lowercase hex, the form mintId makes.
    nonisolated static func isMark(_ s: String) -> Bool { s.count == 32 && s.allSatisfy { $0.isHexDigit && !$0.isUppercase } }
    /// A JOIN THAT WAITS FOR ITS MEETING: the link's group and mark, taken off the link before the card is met (MontanaMeeting.handleLink)
    /// and asked of the owner the moment the meeting opens its conversation; ten minutes at most.
    private var waitingJoin: (g: String, mark: String, at: Date)?
    /// The link's group mark, if it carries one, and the link without it -- which goes on to the meeting. Read on any thread.
    nonisolated static func joinMark(in url: URL) -> (url: URL, g: String, mark: String)? {
        guard var parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let v = parts.queryItems?.first(where: { $0.name == joinParam })?.value else { return nil }
        let bits = v.split(separator: ".", maxSplits: 1).map(String.init)
        guard bits.count == 2, isMark(bits[0]), isMark(bits[1]) else { return nil }
        parts.queryItems = parts.queryItems?.filter { $0.name != joinParam }
        if parts.queryItems?.isEmpty == true { parts.queryItems = nil }
        guard let bare = parts.url else { return nil }
        return (bare, bits[0], bits[1])
    }
    /// The join waits for its meeting.
    @MainActor
    func waitJoin(_ g: String, _ mark: String) {
        waitingJoin = (g, mark, Date())
        MontanaTrace.mark("group_link", "a join waits for its meeting")
    }
    /// The meeting opened its conversation: the waiting join is asked of the owner over it -- at once when the pipe holds, else
    /// a few seconds later, a minute at most.
    @MainActor
    func askJoin(through pipe: String) {
        guard let j = waitingJoin, Date().timeIntervalSince(j.at) < 600 else { return }
        waitingJoin = nil
        let me = Self.clean(E2E.myDisplayName(), Self.nameLimit)
        let w = MTGroupWord(t: "jn", g: j.g, n: me.isEmpty ? nil : me, id: ChatStore.mintMid().mid, tx: j.mark)   // COMPAT-GATED
        Task { @MainActor in
            for _ in 0..<30 {
                if MontanaConv.holds(pipe) { break }
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
            let sent = self.carry(w, to: pipe, letter: "", own: false) != nil
            MontanaTrace.mark("group_link", "join asked sent=\(sent ? 1 : 0)")
        }
    }
    /// One who opened the group's link asks at the owner's phone: the mark must be the group's standing one; then the person is
    /// added by the owner's own road, and everyone reads that they joined by the link.
    @MainActor
    private func joined(_ w: MTGroupWord, from pipe: String, store: ChatStore) -> Verdict {
        guard let g = groups[w.g], g.canManage,
              let mark = w.tx, let held = g.invite, mark == held else { return .refused }
        let from = MTSamePair.root(pipe)
        if g.members.contains(where: { MTSamePair.root($0.pipe) == from }) { return .held }   // already in: nothing more
        // ONE THE OWNER TOOK OUT DOES NOT COME BACK BY THE LINK (the reference's «Removed Users»): the owner lets them, or adds them
        if g.banned?.contains(where: { MTSamePair.root($0.pipe) == from }) == true {
            MontanaTrace.mark("group_link", "a removed person asked by the link -- refused")
            return .refused
        }
        add(Self.key(of: g.id), people: [Chat(name: from, lastMessage: "", time: "", unread: 0, status: "", convId: from)],
            viaLink: true, store: store)
        MontanaTrace.mark("group_link", "joined by the link")
        return .held
    }

    // -- mentions (stage R, the reference folder: «@» names a person of the group) ---------------------------------

    /// The people a «@» may name in a group: everyone this phone knows by name in it -- the owner's phone everyone it carries to,
    /// a member's phone the owner and whoever spoke -- this phone aside, and nobody known only as «Member».
    func mentionable(_ key: String) -> [MTRoomPerson] {
        guard let g = state(key), g.kind == .group else { return [] }
        var out = shownSeats(key)
        if !g.mine {
            for (seat, n) in g.names where seat != Self.ownerSeat && !out.contains(where: { $0.seat == seat }) { out.append((seat, n)) }
        }
        return out.filter { $0.seat != g.me && $0.name != Self.memberWord }.map { MTRoomPerson(seat: $0.seat, name: $0.name) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }
    /// A LETTER THAT NAMES ME (the reference folder's mention): «@» and the name I go by -- my own name or my nick. It rings even in
    /// a chat whose sound is off, as the reference rings a mention.
    static func mentionsMe(_ words: String) -> Bool {
        let low = words.lowercased()
        let names = [E2E.myDisplayName(), MontanaNames.heldName ?? ""]
            .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }.filter { !$0.isEmpty }
        // the name must end where a word ends: «@Al» is not a mention of a person named «Al» inside «@Alex»
        return names.contains { name in
            var from = low.startIndex
            while let r = low.range(of: "@" + name, range: from..<low.endIndex) {
                if r.upperBound == low.endIndex || !(low[r.upperBound].isLetter || low[r.upperBound].isNumber) { return true }
                from = r.upperBound
            }
            return false
        }
    }

    // -- the group's events: rows in the middle of its feed (stage R) ---------------------------------

    /// AN EVENT OF THE GROUP STANDS IN ITS FEED (MTGroupEvent): laid on this phone, in the middle of the feed as the day is. An
    /// organisation's chat takes its people from the core and lays only its calls.
    @MainActor
    func lay(_ e: MTGroupEvent, store: ChatStore? = nil) {
        guard let st = store ?? ChatStore.live, let g = groups[e.g], g.kind == .group, let row = e.row else { return }
        st.appendGroupEvent(Self.key(of: g.id), row)
    }
    /// The owner tells everyone else who came, who was taken out, who left -- by seat, never by a name of the owner's.
    @MainActor
    private func tellEvent(_ e: MTGroupEvent, in g: MTGroupState) {
        guard g.mine, g.kind == .group, let d = try? JSONEncoder().encode(e), let js = String(data: d, encoding: .utf8) else { return }
        let w = MTGroupWord(t: "ev", g: g.id, id: ChatStore.mintMid().mid, s: Self.ownerSeat, tx: js)   // COMPAT-GATED: an older build buries «ev»
        let about = Set(e.p ?? [])
        for m in g.members where !about.contains(m.seat) { _ = carry(w, to: m.pipe, letter: "", own: false) }
    }
    /// AN EVENT FROM THE OWNER (its «ev»): taken from the owner's pipe alone, about seats alone, once by its name.
    @MainActor
    private func evented(_ w: MTGroupWord, from pipe: String, store: ChatStore) -> Verdict {
        guard let g = groups[w.g] else { return .wait }
        guard g.left != true, !g.mine, MTSamePair.root(g.owner) == MTSamePair.root(pipe) else { return .refused }
        guard let id = w.id, Self.isLetterName(id), let tx = w.tx, tx.utf8.count <= 4096, let d = tx.data(using: .utf8),
              let e = try? JSONDecoder().decode(MTGroupEvent.self, from: d), ["added", "removed", "left", "joined"].contains(e.e),
              Self.isSeat(e.a) else { return .refused }
        guard !answersHeard.contains(id) else { return .held }
        answersHeard.append(id)
        if 512 < answersHeard.count { answersHeard.removeFirst(answersHeard.count - 512) }
        let about = Array((e.p ?? []).filter { Self.isSeat($0) }.prefix(Self.peopleLimit))
        lay(MTGroupEvent(e: e.e, g: g.id, a: e.a, p: about.isEmpty ? nil : about), store: store)
        return .held
    }
    /// The sentence an event's row says on this phone: the people by the names this group knows them by, «you» for this phone.
    func eventWords(_ text: String) -> String? {
        guard let e = MTGroupEvent.of(text) else { return nil }
        let me = groups[e.g]?.me
        let mine = e.a == me
        let who = mine ? "" : speakerName(Self.speaker(e.g, e.a))
        let about = e.p ?? []
        let you = me.map { about.contains($0) } ?? false
        let names = about.filter { $0 != me }.map { speakerName(Self.speaker(e.g, $0)) }.joined(separator: ", ")
        let title = e.t ?? ""
        let b = MTLanguage.bundle
        switch e.e {
        case "made":
            return mine ? String(localized: "You created the group \"\(title)\"", bundle: b) : String(localized: "\(who) created the group \"\(title)\"", bundle: b)
        case "added":
            if mine { return String(localized: "You added \(names)", bundle: b) }
            return you ? String(localized: "\(who) added you", bundle: b) : String(localized: "\(who) added \(names)", bundle: b)
        case "removed":
            if mine { return String(localized: "You removed \(names)", bundle: b) }
            return you ? String(localized: "\(who) removed you", bundle: b) : String(localized: "\(who) removed \(names)", bundle: b)
        case "left":
            return mine ? String(localized: "You left the group", bundle: b) : String(localized: "\(who) left the group", bundle: b)
        case "joined":
            return mine ? String(localized: "You joined the group via invite link", bundle: b)
                        : String(localized: "\(who) joined the group via invite link", bundle: b)
        case "named":
            return mine ? String(localized: "You changed the group name to \"\(title)\"", bundle: b)
                        : String(localized: "\(who) changed the group name to \"\(title)\"", bundle: b)
        case "face":
            return mine ? String(localized: "You changed the group photo", bundle: b) : String(localized: "\(who) changed the group photo", bundle: b)
        case "pinned":
            return mine ? String(localized: "You pinned \"\(title)\"", bundle: b) : String(localized: "\(who) pinned \"\(title)\"", bundle: b)
        case "call":
            if e.k == "live" { return mine ? String(localized: "You started a live stream", bundle: b) : String(localized: "\(who) started a live stream", bundle: b) }
            if e.v == 1 { return mine ? String(localized: "You started a video chat", bundle: b) : String(localized: "\(who) started a video chat", bundle: b) }
            return mine ? String(localized: "You started a voice chat", bundle: b) : String(localized: "\(who) started a voice chat", bundle: b)
        case "ended":
            let span = Self.span(e.d ?? 0)
            if e.k == "live" { return String(localized: "Live stream ended (\(span))", bundle: b) }
            if e.v == 1 { return String(localized: "Video chat ended (\(span))", bundle: b) }
            return String(localized: "Voice chat ended (\(span))", bundle: b)
        default:
            return nil
        }
    }
    /// A room's length as the reference's row says it: minutes and seconds, hours when it ran past one.
    static func span(_ seconds: Int) -> String {
        let s = max(0, seconds)
        return 3600 <= s ? String(format: "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }
    /// What the head of a group's chat says once this phone is out of it (the reference folder: «you were removed from the group»,
    /// «you have left the group»).
    func outWords(_ key: String) -> String? {
        guard let g = state(key), g.left == true else { return nil }
        let channel = g.kind == .channel
        if g.removed == true {
            return channel ? String(localized: "you were removed from the channel", bundle: MTLanguage.bundle)
                           : String(localized: "you were removed from the group", bundle: MTLanguage.bundle)
        }
        return channel ? String(localized: "you have left the channel", bundle: MTLanguage.bundle)
                       : String(localized: "you have left the group", bundle: MTLanguage.bundle)
    }

    /// DELETE AND EXIT (the reference folder: the button standing in the field's place once a person is out): the chat and its rows
    /// leave this phone; the group stays known as one this phone is out of, so a word still on its way finds its road over and brings
    /// nothing back -- only the owner adding this phone again stands it anew.
    @MainActor
    func erase(_ key: String, store: ChatStore) {
        guard let g = state(key), g.left == true else { return }
        let row = store.storedChats().first { $0.id == key } ?? store.storedArchived().first { $0.id == key }
            ?? Chat(name: key, lastMessage: "", time: nowHHMM(), unread: 0, status: "", photoURL: nil,
                    isGroup: true, isAdmin: g.mine, members: [], convId: key, displayName: g.title)
        store.deleteChat(row, forBoth: false)
        MontanaTrace.mark("group_erased", "out=\(g.removed == true ? "removed" : "left")")
    }
    /// LEAVE GROUP (the reference folder: «Are you sure you want to leave and delete %@?»): the owner is told, and the chat goes.
    @MainActor
    func leaveAndErase(_ key: String, store: ChatStore) {
        leave(key, store: store)
        erase(key, store: store)
    }
    /// DELETE FOR ALL (the reference folder: the owner's «Delete Group» -- «delete the group and all of its messages for all
    /// members»): every member is told by the owner's own «out», marked as the group's end -- a build that reads the mark erases the
    /// chat, an older one stands it read-only, as a taking out -- and the chat leaves this phone, the group known as ended here.
    @MainActor
    func dissolve(_ key: String, store: ChatStore) {
        guard var g = state(key), g.mine, g.left != true else { return }
        var w = MTGroupWord(t: "out", g: g.id)
        w.dl = 1
        for m in g.members { _ = carry(w, to: m.pipe, letter: "", own: false) }
        MTGroupRoom.shared.groupGone(g.id)
        g.left = true
        g.members = []
        groups[g.id] = g
        saveGroups()
        erase(key, store: store)
        MontanaTrace.mark("group_dissolved", "by its owner")
    }

    // -- the group's life: its name and face, its people, leaving ---------------------------------

    /// THE OWNER RENAMES THE GROUP OR GIVES IT A NEW FACE (its page): every member is told by the invitation's own word again --
    /// the same group, the same seat, the new title and face -- so every phone shows what the owner's shows.
    @MainActor
    func renew(_ key: String, title: String, faceFile: String?, about: String? = nil, store: ChatStore) {
        guard var g = state(key), g.canManage else { return }
        let name = Self.clean(title, Self.titleLimit)
        let face = faceFile.flatMap { try? Data(contentsOf: avatarsDirURL().appendingPathComponent($0)) }
        // the description, the owner's words about the group (stage R): carried in the invitation's ds, as at the group's birth
        let words = about.map { Self.clean($0, Self.aboutLimit) }
        let described = words.map { $0 != g.about } ?? false
        guard !name.isEmpty, name != g.title || face != nil || described else { return }
        let renamed = name != g.title
        if renamed { act(&g, "title", t: name) }
        if described { act(&g, "about") }
        if face != nil { act(&g, "photo") }
        g.title = name
        if let words { g.about = words }
        if face != nil, let faceFile { g.face = faceFile }   // the group owns its face (stand)
        groups[g.id] = g
        saveGroups()
        if face != nil { store.mirrorFaceToShared(key) }
        // The owner's own row is the page's to write (ChatsListView.updateChat): a second writer of the shelf here would lay the
        // shelf back over the page's fresh copy.
        tellAll(g, face: face.flatMap { Self.thumb($0) })
        if renamed { lay(MTGroupEvent(e: "named", g: g.id, a: Self.ownerSeat, t: name), store: store) }
        if face != nil { lay(MTGroupEvent(e: "face", g: g.id, a: Self.ownerSeat), store: store) }
        MontanaTrace.mark("group_renewed", "face=\(face == nil ? 0 : 1)")
    }

    /// THE OWNER ADDS PEOPLE (its page): each new one is invited with a seat of their own, and everyone learns the new count.
    @MainActor
    func add(_ key: String, people: [Chat], viaLink: Bool = false, store: ChatStore) {
        guard var g = state(key), g.canManage else { return }
        let held = Set(g.members.map { MTSamePair.root($0.pipe) })
        var taken = Set(g.members.map(\.seat) + [Self.ownerSeat])
        var fresh: [MTGroupMember] = []
        for c in people where !c.isGroup {
            let p = MTSamePair.root(c.convRef)
            guard MontanaConv.holds(p), !ChatStore.refusesCold(p), !held.contains(p), !fresh.contains(where: { $0.pipe == p }),
                  g.members.count + fresh.count < Self.peopleLimit else { continue }
            var seat = Self.mintSeat()
            while taken.contains(seat) { seat = Self.mintSeat() }
            taken.insert(seat)
            fresh.append(MTGroupMember(seat: seat, pipe: p))
        }
        guard !fresh.isEmpty else { return }
        g.members += fresh
        g.count = g.members.count + 1
        // one taken out and added again by the owner's own hand is let back (the reference's «Add To Channel»)
        g.banned = g.banned?.filter { b in !fresh.contains { f in f.pipe == MTSamePair.root(b.pipe) } }.nilIfEmpty
        act(&g, viaLink ? "joined" : "invited", p: fresh.map { f in seatName(g, f.seat) })
        groups[g.id] = g
        saveGroups()
        let face = store.storedChats().first(where: { $0.id == key })?.photoURL.flatMap { try? Data(contentsOf: avatarsDirURL().appendingPathComponent($0)) }
        tellAll(g, face: face.flatMap { Self.thumb($0) }, only: Set(fresh.map(\.seat)))
        // one who came by the group's link joined by their own hand (the reference: «%@ joined the group via invite link»)
        let came = viaLink ? MTGroupEvent(e: "joined", g: g.id, a: fresh[0].seat)
                           : MTGroupEvent(e: "added", g: g.id, a: Self.ownerSeat, p: fresh.map(\.seat))
        lay(came, store: store)
        tellEvent(came, in: g)
        // A SUBSCRIBER ADDED LATER READS THE CHANNEL'S NEWEST POSTS (the reference shows a channel's history to whoever comes in;
        // the author's words 17.10.2026 18:4x MSK: «channels ... 1 to 1»): the newest ten posts of words, each under its own one name
        // and so at its own moment, as every subscriber was carried it. A post with a file stays: its sealed pieces were laid for
        // the receivers of its day.
        if g.kind == .channel {
            let me = Self.clean(E2E.myDisplayName(), Self.nameLimit)
            let posts = (store.messages[key] ?? []).filter { m in
                m.isFromMe && m.imageFile == nil && m.videoFile == nil && m.audioFile == nil && m.docFile == nil
                    && MTGroupEvent.of(m.text) == nil && Self.carries(m.text)
            }
            for p in posts.suffix(10) {
                let letter = p.mid.hasPrefix("mid:") ? String(p.mid.dropFirst(4)) : p.mid
                guard Self.isLetterName(letter) else { continue }
                let w = MTGroupWord(t: "say", g: g.id, k: g.kind.rawValue, ti: g.title, n: me.isEmpty ? nil : me, id: letter, s: g.me, tx: p.text,
                                    qt: (p.replyText ?? "").isEmpty ? nil : p.replyText)
                for m in fresh { _ = carry(w, to: m.pipe, letter: "", own: false) }
            }
        }
        MontanaTrace.mark("group_added", "people=\(fresh.count) count=\(g.count)")
    }

    /// THE OWNER TAKES A PERSON OUT (its page): they are told by their own word and stop being carried to; everyone left learns
    /// the new count. Their letters already in the group stay where they stand.
    @MainActor
    func remove(_ key: String, seat: String, by actor: String = MTGroup.ownerSeat, store: ChatStore) {
        guard var g = state(key), g.canManage, let m = g.members.first(where: { $0.seat == seat }) else { return }
        if g.kind == .channel {
            g.banned = Array(((g.banned ?? []).filter { b in b.seat != seat } + [m]).suffix(Self.peopleLimit))
        }
        act(&g, "removed", p: [seatName(g, seat)])
        g.members.removeAll { $0.seat == seat }
        g.admins = g.admins?.filter { $0 != seat }.nilIfEmpty   // a person taken out is nobody's administrator
        g.count = g.members.count + 1
        groups[g.id] = g
        saveGroups()
        _ = carry(MTGroupWord(t: "out", g: g.id), to: m.pipe, letter: "", own: false)
        tellAll(g, face: nil)
        let out = MTGroupEvent(e: "removed", g: g.id, a: actor, p: [seat])   // an administrator's deed says its own name
        lay(out, store: store)
        tellEvent(out, in: g)
        MontanaTrace.mark("group_removed", "count=\(g.count)")
    }

    /// A MEMBER LEAVES (its page): the owner is told and carries nothing more to this phone; the group's rows stay readable here,
    /// and a word of the group still on its way is answered as one whose road is over.
    @MainActor
    func leave(_ key: String, store: ChatStore) {
        guard var g = state(key), !g.mine, g.left != true else { return }
        g.left = true
        groups[g.id] = g
        saveGroups()
        _ = carry(MTGroupWord(t: "bye", g: g.id), to: g.owner, letter: "", own: false)
        MTGroupRoom.shared.groupGone(g.id)   // its room goes with the group
        MontanaTrace.mark("group_left", "by this phone")
    }

    /// The invitation's word again to every member: the title, the words about the group, the count, and a new face when one is
    /// given. Each member keeps their seat.
    /// `only` -- the seats the face goes to (the people just added); everyone else learns the count without it.
    @MainActor
    private func tellAll(_ g: MTGroupState, face: String?, only: Set<String>? = nil) {
        let me = Self.clean(E2E.myDisplayName(), Self.nameLimit)
        for m in g.members {
            let fc = (only == nil || only?.contains(m.seat) == true) ? face : nil
            var w = MTGroupWord(t: "inv", g: g.id, k: g.kind.rawValue, ti: g.title, ds: g.about.isEmpty ? nil : g.about, fc: fc,
                                c: g.count, me: m.seat, n: me.isEmpty ? nil : me)
            w.ad = g.admins   // the administrators the owner named (stage R.6)
            w.sg = g.kind == .channel && g.signs == true ? 1 : nil   // a channel signs its posts (Sign Messages)
            w.rx = g.kind == .channel && g.reacts == false ? 0 : nil   // a channel takes no reactions (Allow Reactions off)
            _ = carry(w, to: m.pipe, letter: "", own: false)
        }
    }

    // -- the receipts of the copies ------------------------------------------------------------

    /// A copy was receipted (ChatStore.markDelivered, after the queue let it go). True when the name was a group's copy.
    @MainActor
    func copyDelivered(_ name: String, store: ChatStore) -> Bool {
        guard ready(), let c = copies.removeValue(forKey: name) else { return false }
        saveCopies()
        MontanaTrace.mark("group_rcpt", mid: name, "own=\(c.own ? 1 : 0)")
        if c.own { follow(c, store: store) }
        return true
    }
    /// The node holds a copy (ChatStore.markSentByNode): my row has left this phone.
    @MainActor
    func copyHeld(_ name: String, store: ChatStore) -> Bool {
        guard ready(), let c = copies[name] else { return false }
        if !c.held { copies[name]?.held = true; saveCopies() }
        if c.own { follow(c, store: store) }
        return true
    }
    /// The queue settled a copy (ChatStore.settleByMid): my row follows its copies.
    @MainActor
    func copySettled(_ name: String, store: ChatStore) -> Bool {
        guard ready(), let c = copies[name] else { return false }
        if c.own { follow(c, store: store) }
        return true
    }
    /// The queue refused a copy by a word (ChatStore.settleRefused, MTRefusal) -- my row says so.
    @MainActor
    func copyRefused(_ name: String, store: ChatStore) -> Bool {
        guard ready(), let c = copies[name] else { return false }
        if !c.failed { copies[name]?.failed = true; saveCopies() }
        if c.own { follow(c, store: store) }
        return true
    }
    /// MY ROW STANDS WHERE ITS RECEIVERS SAY (the groups checklist): delivered when every copy is receipted, red while a copy has
    /// failed, sent once the node holds one or one is receipted. Through the ladder's one door: forward only, red only from below.
    @MainActor
    private func follow(_ c: MTGroupCopy, store: ChatStore) {
        guard !c.letter.isEmpty else { return }
        let key = Self.key(of: c.group)
        guard let i = store.messages[key]?.firstIndex(where: { $0.isFromMe && $0.mid == "mid:" + c.letter }) else { return }
        let left = copies.values.filter { $0.own && $0.group == c.group && $0.letter == c.letter }
        if left.isEmpty {
            store.advance(key, i, to: .delivered)
        } else if left.contains(where: { $0.failed }) {
            store.putOut(key, i, because: .copyRefused)
        } else if left.count < c.of || left.contains(where: { $0.held }) {
            store.advance(key, i, to: .sent)
        }
    }
    /// Whether a row of mine still has a copy in the queue (ChatStore.paintOrphanSending): a clock with nothing behind it is a lie.
    func rides(_ letter: String, queued: Set<String>) -> Bool {
        guard ready() else { return true }   // an unread store says nothing is lost: the clock waits for the store
        return copies.contains { $0.value.own && $0.value.letter == letter && queued.contains($0.key) }
    }

    // -- arrival -------------------------------------------------------------------------------

    /// `instant` -- a word that lived an instant (a typing word): it rode no queue, so nothing is receipted and nothing knocks again.
    enum Verdict { case held, refused, wait, instant }

    /// A group's word arrived on a pipe (ChatStore.append). True when it was ours to read: it is never a row of the pipe. Every
    /// copy is answered by the one receipt door on the pipe it came by -- held, or with its road over here; a letter of a group
    /// whose invitation has not landed yet is not answered, and its copy knocks again.
    @MainActor
    static func handle(_ text: String, from pipe: String, isFromMe: Bool, sid: String?, store: ChatStore) -> Bool {
        guard text.hasPrefix(mark) else { return false }
        guard !isFromMe else { return true }
        var verdict = Verdict.refused
        if let d = String(text.dropFirst(mark.count)).data(using: .utf8),
           let w = try? JSONDecoder().decode(MTGroupWord.self, from: d), MontanaArchive.isLabel(w.g) {
            verdict = shared.take(w, from: pipe, copy: sid ?? "", store: store)
        } else {
            MontanaTrace.mark("group_refused", "unreadable from=\(String(pipe.prefix(10)))")
        }
        if verdict != .wait, verdict != .instant {
            store.sendDeliveryReceipt(pipe, msgId: sid, isFromMe: false, text: text, buried: verdict == .refused)
        }
        return true
    }

    @MainActor
    private func take(_ w: MTGroupWord, from pipe: String, copy: String, store: ChatStore) -> Verdict {
        guard ready() else { return .wait }   // the copy knocks again once the store opens
        switch w.t {
        case "inv": return invited(w, by: pipe, store: store)
        case "say": return heard(w, from: pipe, copy: copy, store: store)
        case "sig": return answered(w, from: pipe, store: store)
        case "bye": return parted(w, from: pipe, store: store)
        case "out": return dropped(w, by: pipe, store: store)
        case "wl": return wallWord(w, from: pipe, store: store)   // COMPAT-GATED: a channel's wall word (06.10)
        case "room": return roomed(w, from: pipe, copy: copy)   // COMPAT-GATED: a group room's word (07.10)
        case "ev": return evented(w, from: pipe, store: store)
        case "kk": return kicked(w, from: pipe, store: store)   // COMPAT-GATED: an administrator's «take out», to the owner
        case "jn": return joined(w, from: pipe, store: store)   // COMPAT-GATED: a person who opened the group's invite link
        case "kp": return keptHeard(w, from: pipe)   // COMPAT-GATED: a keeper's word to a deleted letter's speaker (16.10)
        case "ty": return typed(w, from: pipe)   // COMPAT-GATED: a member typing, an instant word an older build buries (B.12)
        case "rd": return readWord(w, from: pipe, store: store)   // COMPAT-GATED: a reader's mark, an older build buries it (B.13)
        case "vw": return seen(w, from: pipe, store: store)   // COMPAT-GATED: a subscriber's posts shown, to a channel's owner (17.10)
        case "vc": return counted(w, from: pipe)   // COMPAT-GATED: a channel owner's view counts, to its subscribers (17.10)
        default:
            MontanaTrace.mark("group_refused", "kind=\(String(w.t.prefix(8)))")   // a newer build's word: its road ends here
            return .refused
        }
    }

    /// AN INVITATION: the group stands on this phone, owned by the pipe the word came by. A second invitation of the same owner
    /// renews the title and the count; another pipe naming a group this phone already holds is refused.
    @MainActor
    private func invited(_ w: MTGroupWord, by pipe: String, store: ChatStore) -> Verdict {
        let owner = MTSamePair.root(pipe)
        let title = Self.clean(w.ti ?? "", Self.titleLimit)
        guard let kind = MTGroupKind(rawValue: w.k ?? ""), !title.isEmpty,
              let seat = w.me, Self.isSeat(seat), seat != Self.ownerSeat else { return .refused }
        let count = max(2, min(w.c ?? 2, Self.peopleLimit + 1))
        let ownerName = Self.clean(w.n ?? "", Self.nameLimit)
        if var g = groups[w.g] {
            guard !g.mine, MTSamePair.root(g.owner) == owner else {
                MontanaTrace.mark("group_refused", "invitation of a group held by another owner")
                return .refused
            }
            // Out of the group, a word of the old seat is one that was on its way; the owner adding this phone again gives a new seat.
            let back = g.left == true
            if back {
                guard seat != g.me else { return .refused }
                g.left = nil
                g.removed = nil
            }
            let renamed = !back && g.title != title
            let hadFace = g.faceTag != nil
            g.title = title
            g.about = Self.clean(w.ds ?? "", Self.aboutLimit)
            g.count = count
            g.me = seat
            if !ownerName.isEmpty { g.names[Self.ownerSeat] = ownerName }
            g.admins = w.ad.map { $0.filter(Self.isSeat) }.flatMap { $0.isEmpty ? nil : $0 }   // the owner's word alone names them
            g.signs = w.sg == 1 ? true : nil
            g.reacts = w.rx == 0 ? false : nil
            let tag = w.fc.map { String($0.count) + "-" + String($0.suffix(16)) }
            let newFace = tag != nil && tag != g.faceTag
            if newFace { g.faceTag = tag }
            groups[w.g] = g
            saveGroups()
            stand(g, face: newFace ? Self.face(w.fc) : nil, store: store)
            // the reference folder's rows: added again, a new name, a new face -- each the owner's deed
            if back { lay(MTGroupEvent(e: "added", g: g.id, a: Self.ownerSeat, p: [seat]), store: store) }
            if renamed { lay(MTGroupEvent(e: "named", g: g.id, a: Self.ownerSeat, t: title), store: store) }
            if newFace, hadFace, !back { lay(MTGroupEvent(e: "face", g: g.id, a: Self.ownerSeat), store: store) }
            return .held
        }
        var g = MTGroupState(id: w.g, kind: kind, title: title, about: Self.clean(w.ds ?? "", Self.aboutLimit), owner: owner, me: seat,
                             members: [], count: count, names: ownerName.isEmpty ? [:] : [Self.ownerSeat: ownerName],
                             at: Date().timeIntervalSince1970)
        g.admins = w.ad.map { $0.filter(Self.isSeat) }.flatMap { $0.isEmpty ? nil : $0 }
        g.signs = w.sg == 1 ? true : nil
        g.reacts = w.rx == 0 ? false : nil
        g.faceTag = w.fc.map { String($0.count) + "-" + String($0.suffix(16)) }
        groups[w.g] = g
        saveGroups()
        stand(g, face: Self.face(w.fc), store: store)
        lay(MTGroupEvent(e: "added", g: g.id, a: Self.ownerSeat, p: [seat]), store: store)   // «X added you»
        MontanaTrace.mark("group_rx", "invitation kind=\(kind.rawValue) people=\(count) from=\(String(pipe.prefix(10)))")
        return .held
    }

    /// A LETTER: it lands in the group's feed by the one landing road (ChatStore.append). The owner takes a letter only from a
    /// member it carries to -- in a group, never in a channel -- and carries it on to every other member; a member takes letters
    /// from the owner's pipe alone.
    @MainActor
    private func heard(_ w: MTGroupWord, from pipe: String, copy: String, store: ChatStore) -> Verdict {
        guard var g = groups[w.g] else { return .wait }
        guard g.left != true else { return .refused }   // out of the group: the word's road ends here
        guard let id = w.id, Self.isLetterName(id), let words = w.tx, Self.carries(words) else { return .refused }
        let post = MTBoard.isChannelPost(words)
        if post, g.kind != .channel || g.mine { return .refused }   // a wall's post rides a channel alone, from its owner
        let from = MTSamePair.root(pipe)
        let seat: String
        if g.mine {
            guard let m = g.members.first(where: { MTSamePair.root($0.pipe) == from }) else { return .refused }
            // A CHANNEL CARRIES ITS ADMINISTRATORS' POSTS (the reference's «Post Messages» right): the owner named them; anyone else
            // only reads.
            guard g.kind == .group || g.admins?.contains(m.seat) == true else {
                MontanaTrace.mark("group_refused", "a member's letter in a channel is not carried")
                return .refused
            }
            seat = m.seat
        } else {
            guard MTSamePair.root(g.owner) == from, let s = w.s, Self.isSeat(s), s != g.me else { return .refused }
            seat = s
        }
        let name = Self.clean(w.n ?? "", Self.nameLimit)
        if !name.isEmpty, g.names[seat] != name {
            g.names[seat] = name
            groups[w.g] = g
            saveGroups()
        }
        let key = Self.key(of: g.id)
        let sid = "mid:" + id
        guard store.messages[key]?.contains(where: { $0.mid == sid }) != true else { return .held }   // a repeat: the row stands
        stand(g, face: nil, store: store)
        let born = MontanaDeliveryEngine.letterMoment(mid: sid, sentAt: nil)
        let quote = (w.qt ?? "").isEmpty ? nil : w.qt
        let row = Message(text: words, isFromMe: false, time: hhmm(at: born), replyText: quote, deliveryStatus: .delivered,
                          msgId: sid, senderRef: Self.speaker(g.id, seat), createdAt: born,
                          replyToId: store.localId(forMid: w.qm, chat: key))
        let landed = store.append(key, row)   // a channel's post lands on its wall, never as a row (MTBoard.handle)
        MTGroupTyping.shared.stopped(key, seat: seat)   // the letter came: its speaker types no more (the reference's head)
        MontanaTrace.mark("group_rx", mid: id, "letter landed=\(landed ? 1 : 0) owner=\(g.mine ? 1 : 0) post=\(post ? 1 : 0)")
        if post {
            if w.sl != 1 { MontanaNotify.presentGroup(title: g.title, body: String(localized: "New message", bundle: MTLanguage.bundle), chat: key, copy: copy) }
            return .held
        }
        if landed, w.sl != 1 {   // a channel's post sent without sound shows no banner (Silent Broadcast)
            // The banner a running app owes its person: the group's title over who wrote and what (a channel speaks as itself).
            let shown = MontanaNotify.body(for: words) ?? String(localized: "New message", bundle: MTLanguage.bundle)
            let who = g.kind == .group ? speakerName(Self.speaker(g.id, seat)) : ""
            MontanaNotify.presentGroup(title: g.title, body: who.isEmpty ? shown : who + ": " + shown, chat: key, copy: copy,
                                       mentioned: g.kind == .group && Self.mentionsMe(words))   // a mention rings through the mute
        }
        if landed, g.mine {
            let on = MTGroupWord(t: "say", g: g.id, k: g.kind.rawValue, ti: g.title, n: name.isEmpty ? nil : name, id: id, s: seat,
                                 tx: words, qt: quote, qm: w.qm)
            spread(on, in: g, except: seat, own: false)
        }
        return .held
    }

    /// AN ANSWER: applied in the group's feed by the reaction's own road (ChatStore.append), once by its name, and carried on by
    /// the owner to every other member.
    @MainActor
    private func answered(_ w: MTGroupWord, from pipe: String, store: ChatStore) -> Verdict {
        guard let g = groups[w.g] else { return .wait }
        guard g.left != true else { return .refused }
        guard let id = w.id, Self.isLetterName(id), let words = w.tx, Self.answers(words) else { return .refused }
        let from = MTSamePair.root(pipe)
        let seat: String
        if g.mine {
            guard let m = g.members.first(where: { MTSamePair.root($0.pipe) == from }) else { return .refused }
            seat = m.seat
        } else {
            guard MTSamePair.root(g.owner) == from, let s = w.s, Self.isSeat(s), s != g.me else { return .refused }
            seat = s
        }
        guard !answersHeard.contains(id) else { return .held }
        // A CHANNEL WHOSE OWNER TURNED REACTIONS OFF TAKES NONE (the reference's «Allow Reactions»): a subscriber's reaction ends here
        if words.hasPrefix(reactionMark), g.kind == .channel, g.reacts == false, seat != Self.ownerSeat, g.admins?.contains(seat) != true {
            return .refused
        }
        let key = Self.key(of: g.id)
        if words.hasPrefix(pinMark), !Self.pins(g, seat: seat) {
            MontanaTrace.mark("group_refused", "a pin by a seat without the right seat=\(seat)")
            return .refused
        }
        // AN EDIT OR A DELETION IS THE SPEAKER'S OWN (applyEditFromPeer and deleteEverywhere ask only whose phone wrote a row):
        // the owner answers for every member, so it takes one only for a letter standing here under that member's seat; a member
        // takes the owner's word, and a deletion of a letter not landed yet buries it before it comes.
        if let target = Self.target(words) {
            if let row = store.messages[key]?.first(where: { $0.mid == target }) {
                // The owner and the administrators it named take another's letter away (stage R.6, as the reference's admins
                // delete messages); the owner's word alone names them. An edit stays the speaker's own.
                let boss = words.hasPrefix(deleteMark) && (seat == Self.ownerSeat || g.admins?.contains(seat) == true)
                guard Self.author(row, in: g) == seat || boss else {
                    MontanaTrace.mark("group_refused", "an answer to another's letter seat=\(seat)")
                    return .refused
                }
            } else if store.buried(target, in: key) {
                return .held   // the letter is gone here already
            } else if g.mine {
                return .wait   // the letter has not landed at the owner yet: the copy knocks again
            } else if words.hasPrefix(deleteMark) {
                store.bury([target], in: key)   // in this group alone: a speaker's notice reaches no other conversation
                answersHeard.append(id)
                return .held
            }
        }
        answersHeard.append(id)
        if 512 < answersHeard.count { answersHeard.removeFirst(answersHeard.count - 512) }
        let born = MontanaDeliveryEngine.letterMoment(mid: "mid:" + id, sentAt: nil)
        store.append(key, Message(text: words, isFromMe: false, time: "", msgId: "mid:" + id, senderRef: Self.speaker(g.id, seat), createdAt: born))
        // the reference's service row of a pin: «X pinned "..."», laid on this phone under the pinned letter's own words
        if words.hasPrefix(pinMark), let o = Self.json(String(words.dropFirst(pinMark.count))), (o["op"] as? String) == "pin",
           let sid = o["sid"] as? String, let row = store.messages[key]?.first(where: { r in r.msgId == sid }) {
            lay(MTGroupEvent(e: "pinned", g: g.id, a: seat, t: Self.pinWords(row)), store: store)
        }
        if g.mine { spread(MTGroupWord(t: "sig", g: g.id, n: w.n, id: id, s: seat, tx: words), in: g, except: seat, own: false) }
        return .held
    }

    /// A WORD OF A CHANNEL'S WALL (the author's words 06.10.2026 14:2x-14:4x MSK: «in channels posts as on the Wall of Thoughts»): a subscriber's mark -- a like, the posts
    /// seen, a comment -- reaches the owner, who applies it by the subscriber's seat (MTBoard.applyChannel); the owner's counts and
    /// deletions reach a subscriber, who lays them on the channel's page (MTBoard.handle).
    @MainActor
    private func wallWord(_ w: MTGroupWord, from pipe: String, store: ChatStore) -> Verdict {
        guard let g = groups[w.g] else { return .wait }
        guard g.left != true, g.kind == .channel, let words = w.tx, words.hasPrefix(MTBoard.mark),
              words.utf8.count <= Self.wallPostLimit,
              let bw = try? JSONDecoder().decode(MTBoardWord.self, from: Data(words.dropFirst(MTBoard.mark.count).utf8)) else { return .refused }
        let from = MTSamePair.root(pipe)
        let key = Self.key(of: g.id)
        if g.mine {
            guard let m = g.members.first(where: { MTSamePair.root($0.pipe) == from }) else { return .refused }
            MTBoard.shared.applyChannel(bw, from: Self.speaker(g.id, m.seat), in: key)
        } else {
            guard MTSamePair.root(g.owner) == from else { return .refused }
            MTBoard.handle(words, from: key, isFromMe: false)
        }
        return .held
    }

    /// A WORD OF A CHANNEL'S WALL LEAVES (MTBoard.send, tellChannel): the owner's to every subscriber, a subscriber's to the owner.
    @MainActor @discardableResult
    func carryWallWord(_ bw: MTBoardWord, in key: String) -> Bool {
        guard let g = state(key), g.kind == .channel, g.left != true,
              let d = try? JSONEncoder().encode(bw), let js = String(data: d, encoding: .utf8) else { return false }
        let w = MTGroupWord(t: "wl", g: g.id, id: ChatStore.mintMid().mid, s: g.me, tx: MTBoard.mark + js)   // COMPAT-GATED: a channel's wall word
        return spread(w, in: g, except: nil, own: false) > 0
    }

    /// A ROOM'S WORD (MTGroupRoom): its seat is proven by the pipe it came by, as an answer's is; the room reads it once by its name,
    /// and the owner of the group passes it on to everyone else -- a targeted word to the seats it names alone.
    @MainActor
    private func roomed(_ w: MTGroupWord, from pipe: String, copy: String) -> Verdict {
        guard let g = groups[w.g] else { return .wait }
        guard g.left != true else { return .refused }
        guard let id = w.id, Self.isLetterName(id), let words = w.tx, words.utf8.count <= 4096 else { return .refused }
        let from = MTSamePair.root(pipe)
        let seat: String
        if g.mine {
            guard let m = g.members.first(where: { MTSamePair.root($0.pipe) == from }) else { return .refused }
            seat = m.seat
        } else {
            guard MTSamePair.root(g.owner) == from, let s = w.s, Self.isSeat(s), s != g.me else { return .refused }
            seat = s
        }
        guard !answersHeard.contains(id) else { return .held }
        guard paced(g, seat: seat) else { return .wait }
        answersHeard.append(id)
        if 512 < answersHeard.count { answersHeard.removeFirst(answersHeard.count - 512) }
        MTGroupRoom.shared.heard(words, group: g.id, chat: Self.key(of: g.id), seat: seat, name: w.n.map { Self.clean($0, Self.nameLimit) },
                                 copy: copy)
        if g.mine {
            var on = MTGroupWord(t: "room", g: g.id, k: g.kind.rawValue, ti: g.title, n: w.n, id: id, s: seat, tx: words)
            if let ts = w.ts, ts.count <= 32 {
                // a targeted word: to the seats it names alone -- ringing there if it is an invitation (a word naming no ring is one)
                let ring = w.rg == 1 || w.rg == nil
                on.ts = ts
                on.rg = ring ? 1 : 0
                for m in g.members where ts.contains(m.seat) && m.seat != seat { _ = carry(on, to: m.pipe, letter: "", own: false, ring: ring) }
            } else {
                spread(on, in: g, except: seat, own: false)
            }
        }
        return .held
    }

    /// The pace of one member's room words the owner takes and carries on (carriedPerMinute). A member hears its owner alone, who
    /// paced them already.
    private func paced(_ g: MTGroupState, seat: String) -> Bool {
        guard g.mine else { return true }
        let now = Date().timeIntervalSince1970
        let key = g.id + "/" + seat
        var moments = (carriedAt[key] ?? []).filter { now - $0 < 60 }
        guard moments.count < Self.carriedPerMinute else {
            carriedAt[key] = moments
            MontanaTrace.markFolded("group_paced", "a member past thirty room words a minute -- the copy knocks again", window: 60, key: key)
            return false
        }
        moments.append(now)
        carriedAt[key] = moments
        return true
    }

    /// A MEMBER LEFT (their «bye»): the owner carries nothing more to them, and everyone left learns the new count.
    @MainActor
    private func parted(_ w: MTGroupWord, from pipe: String, store: ChatStore) -> Verdict {
        guard var g = groups[w.g] else { return .refused }
        let from = MTSamePair.root(pipe)
        guard g.mine, let m = g.members.first(where: { MTSamePair.root($0.pipe) == from }) else { return .refused }
        act(&g, "left", p: [seatName(g, m.seat)])
        g.members.removeAll { $0.seat == m.seat }
        g.admins = g.admins?.filter { $0 != m.seat }.nilIfEmpty
        g.count = g.members.count + 1
        groups[w.g] = g
        saveGroups()
        tellAll(g, face: nil)
        let gone = MTGroupEvent(e: "left", g: g.id, a: m.seat)
        lay(gone, store: store)
        tellEvent(gone, in: g)
        MontanaTrace.mark("group_rx", "a member left count=\(g.count)")
        return .held
    }
    /// THE OWNER TOOK THIS PHONE OUT (its «out»): the rows stay readable, and the chat says so instead of a field.
    @MainActor
    private func dropped(_ w: MTGroupWord, by pipe: String, store: ChatStore) -> Verdict {
        guard var g = groups[w.g], !g.mine, MTSamePair.root(g.owner) == MTSamePair.root(pipe) else { return .refused }
        let ended = w.dl == 1   // the owner deleted the group for everyone
        if g.left != true {
            g.left = true
            g.removed = true
            groups[w.g] = g
            saveGroups()
            store.objectWillChange.send()   // the open chat trades its field for the button at once
            MTGroupRoom.shared.groupGone(g.id)   // its room goes with the group
            if !ended { lay(MTGroupEvent(e: "removed", g: g.id, a: Self.ownerSeat, p: [g.me]), store: store) }
            MontanaTrace.mark("group_rx", "taken out by the owner")
        }
        if ended {
            erase(Self.key(of: g.id), store: store)
            MontanaTrace.mark("group_rx", "the owner deleted the group for everyone")
        }
        return .held
    }

    // -- measures ------------------------------------------------------------------------------

    static func answers(_ text: String) -> Bool {
        // A PIN FOR EVERYONE (the checklist's B.11): the letter by its one name, pinned or unpinned; an older build refuses it here
        // and buries it (COMPAT-GATED: the word «sig» is known, its pin is not).
        if text.hasPrefix(pinMark) {
            guard text.utf8.count <= 1024, let o = json(String(text.dropFirst(pinMark.count))), let op = o["op"] as? String,
                  op == "pin" || op == "unpin", let sid = o["sid"] as? String, sid.hasPrefix("mid:") else { return false }
            return isLetterName(String(sid.dropFirst(4)))
        }
        if text.hasPrefix(reactionMark) {
            guard text.count <= 2048, let o = json(String(text.dropFirst(reactionMark.count))), let op = o["op"] as? String else { return false }
            return op == "add" || op == "del"
        }
        if text.hasPrefix(editMark) {
            guard let o = json(String(text.dropFirst(editMark.count))), let tx = o["tx"] as? String else { return false }
            return target(text) != nil && carries(tx)
        }
        return text.hasPrefix(deleteMark) && target(text) != nil
    }
    /// The letter an edit or a deletion speaks of, by its one name; nil for a reaction.
    static func target(_ text: String) -> String? {
        var sid = ""
        if text.hasPrefix(editMark) { sid = (json(String(text.dropFirst(editMark.count)))?["sid"] as? String) ?? "" }
        else if text.hasPrefix(deleteMark) { sid = String(text.dropFirst(deleteMark.count)) }
        let bare = sid.hasPrefix("mid:") ? String(sid.dropFirst(4)) : sid
        return isLetterName(bare) ? "mid:" + bare : nil
    }
    /// The seat whose letter a row is: mine is this phone's seat, another's is named by its speaker's reference.
    static func author(_ row: Message, in g: MTGroupState) -> String? {
        if row.isFromMe { return g.me }
        guard let ref = row.senderRef, ref.hasPrefix(speaker(g.id, "")) else { return nil }
        return String(ref.dropFirst(speaker(g.id, "").count))
    }
    private static func json(_ s: String) -> [String: Any]? {
        s.data(using: .utf8).flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
    }

    /// WHAT A GROUP CARRIES IN THIS BUILD: a person's words, a sticker, and a media letter -- the manifest of a picture, a video, a
    /// round note, a voice or a file, whose sealed pieces lie on the nodes for every receiver (the author's words 06.10.2026 14:2x-14:3x MSK: «groups and channels with the whole of a chat, every kind of data»). Never another service word, a long letter's reference, a coin, a game's letter or a row of a
    /// phone's own -- a coin landing in a group would be credited from nobody.
    static func carries(_ text: String) -> Bool {
        // A CHANNEL'S POST (MTBoard.isChannelPost): the wall's own word, its pictures' posters within the wall's own bounds.
        if MTBoard.isChannelPost(text) { return text.utf8.count <= Self.wallPostLimit }
        guard !text.isEmpty, text.count <= ChatStore.sendPieceChars else { return false }
        if MontanaNotify.isService(text), !text.hasPrefix(stickerMark), !text.hasPrefix(mediaMark) { return false }
        return MTCoinLetter.parse(text) == nil && MTChessLetter.parse(text) == nil && !MTRowLetter.ownRow(text)
    }
    static func clean(_ s: String, _ limit: Int) -> String {
        String(MTCrown.plain(s.trimmingCharacters(in: .whitespacesAndNewlines)).prefix(limit))
    }
    static func isSeat(_ s: String) -> Bool { !s.isEmpty && s.count <= 16 && s.allSatisfy { $0.isHexDigit } }
    static func isLetterName(_ s: String) -> Bool {
        !s.isEmpty && s.count <= 80 && !s.hasPrefix("mid:") && s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }
    }
    private static func mintId() -> String { UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased() }
    private static func mintSeat() -> String { String(mintId().prefix(8)) }
    /// The face an invitation carries: small, so the word stays a letter and not a file.
    private static func thumb(_ face: Data) -> String? {
        guard !face.isEmpty, let ui = UIImage(data: face),
              let d = ui.avatarResized(faceSide).jpegData(compressionQuality: 0.7) else { return nil }
        let b64 = d.base64EncodedString()
        return b64.count <= faceLimit ? b64 : nil
    }
    private static func face(_ b64: String?) -> String? {
        guard let b64, b64.count <= faceLimit, let d = Data(base64Encoded: b64), UIImage(data: d) != nil else { return nil }
        return saveChatAvatar(d)
    }
}


/// THE PLATE OF «@» ABOVE A GROUP'S FIELD (stage R, the reference folder's mention list): it follows the word typed after «@»,
/// a holder of its own, as the link's plate is -- typing redraws the plate, never the feed.
final class MTMentionCompose: ObservableObject {
    static let shared = MTMentionCompose()
    @Published private(set) var query: String?
    @Published private(set) var chat = ""
    func look(at text: String, in key: String) {
        let held = MTGroup.isKey(key) && MTGroup.shared.kind(key) == .group ? Self.tail(text) : nil
        if held != query { query = held }
        if chat != key { chat = key }
    }
    /// The word after the last «@» at the text's end: «@» at the start or after a space, then no space, 32 letters at most.
    static func tail(_ text: String) -> String? {
        guard let at = text.lastIndex(of: "@") else { return nil }
        if at != text.startIndex, !text[text.index(before: at)].isWhitespace { return nil }
        let rest = text[text.index(after: at)...]
        guard rest.count <= 32, !rest.contains(where: { $0.isWhitespace }) else { return nil }
        return String(rest)
    }
    /// The people whose name, by any of its words, begins with what is typed -- five at most.
    func people() -> [MTRoomPerson] {
        guard let q = query?.lowercased() else { return [] }
        let all = MTGroup.shared.mentionable(chat)
        let hit = q.isEmpty ? all : all.filter { p in p.name.lowercased().split(separator: " ").contains { $0.hasPrefix(q) } }
        return Array(hit.prefix(5))
    }
}

extension Array {
    /// Nil for an empty list: a field that names nobody is not written at all.
    var nilIfEmpty: [Element]? { isEmpty ? nil : self }
}

/// WHO TYPES IN A GROUP NOW (the checklist's B.12): seats by the moment their word landed, each alive the store's typing life, and
/// the head's line in the reference's words -- one name, two names, a name and how many others.
final class MTGroupTyping: ObservableObject {
    static let shared = MTGroupTyping()
    @Published private(set) var seats: [String: [String: Date]] = [:]
    @MainActor
    func heard(_ key: String, seat: String) {
        seats[key, default: [:]][seat] = Date()
        DispatchQueue.main.asyncAfter(deadline: .now() + ChatStore.typingLife + 0.1) { [weak self] in self?.sweep(key) }
    }
    @MainActor
    func stopped(_ key: String, seat: String) {
        if seats[key]?[seat] != nil { seats[key]?[seat] = nil }
    }
    @MainActor
    private func sweep(_ key: String) {
        let now = Date()
        let alive = (seats[key] ?? [:]).filter { e in now.timeIntervalSince(e.value) < ChatStore.typingLife }
        if alive.count != seats[key]?.count { seats[key] = alive.isEmpty ? nil : alive }
    }
    /// The head's line while someone types, nil when nobody does.
    @MainActor
    func line(_ key: String) -> String? {
        guard let gid = MTGroup.id(of: key) else { return nil }
        let now = Date()
        let names = (seats[key] ?? [:]).filter { e in now.timeIntervalSince(e.value) < ChatStore.typingLife }
            .sorted { a, b in a.value < b.value }
            .map { e in MTGroup.shared.speakerName(MTGroup.speaker(gid, e.key)) }
        let b = MTLanguage.bundle
        switch names.count {
        case 0: return nil
        case 1: return String(localized: "\(names[0]) is typing…", bundle: b)
        case 2: return String(localized: "\(names[0]) and \(names[1]) are typing…", bundle: b)
        default: return String(localized: "\(names[0]) and \(String(names.count - 1)) others are typing…", bundle: b)
        }
    }
}

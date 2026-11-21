import SwiftUI

extension Color {
    init(hex: String, alpha: Double = 1) {
        var h = hex.trimmingCharacters(in: .whitespacesAndNewlines); if h.hasPrefix("#") { h.removeFirst() }
        var v: UInt64 = 0; Scanner(string: h).scanHexInt64(&v)
        self.init(.sRGB, red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255, opacity: alpha)
    }
}

struct Palette {
    let id: String
    let dark: Bool
    let bg, p1, p2, p3, line, tx, tx2, mu, dim: Color
    let acc, acc2, acctx, pos, neg: Color
    let hi, hitx, hi2: Color
    let hdr, hdrtx, hdrmu: Color
    let rlo, rloBg, rmid, rmidBg, rok, rokBg, rhi, rhiBg: Color
    let aiBg, aiLine, aiTx: Color

    func ratingColors(_ r: String) -> (fg: Color, bg: Color) {
        guard let n = Int(r) else { return (dim, p2) }
        switch n { case ...2: return (rlo, rloBg); case 3: return (rmid, rmidBg); case 4...5: return (rok, rokBg); default: return (rhi, rhiBg) }
    }

    /// 11K Deck — Dr. Sample top-deck black, silver text, strip red actions, pad blue/pink accents.
    static let deck = Palette(id: "k11", dark: true,
        bg: Color(hex: "1F2023"), p1: Color(hex: "26282C"), p2: Color(hex: "2C2E32"), p3: Color(hex: "36383D"), line: Color(hex: "3A3D42"),
        tx: Color(hex: "DEDFE1"), tx2: Color(hex: "C9CBCF"), mu: Color(hex: "A3A6AC"), dim: Color(hex: "80838A"),
        acc: Color(hex: "9FBCD6"), acc2: Color(hex: "B9CFE3"), acctx: Color(hex: "16181B"), pos: Color(hex: "9FBCD6"), neg: Color(hex: "FF6B62"),
        hi: Color(hex: "D8262E"), hitx: .white, hi2: Color(hex: "EFB3B5"),
        hdr: Color(hex: "17181B"), hdrtx: Color(hex: "DEDFE1"), hdrmu: Color(hex: "A3A6AC"),
        rlo: Color(hex: "FF9A93"), rloBg: Color(hex: "D8262E", alpha: 0.30), rmid: Color(hex: "F2C18D"), rmidBg: Color(hex: "F0A050", alpha: 0.18),
        rok: Color(hex: "DEDFE1"), rokBg: Color(hex: "36383D"), rhi: Color(hex: "C3D7EA"), rhiBg: Color(hex: "9FBCD6", alpha: 0.24),
        aiBg: Color(hex: "2C3A47"), aiLine: Color(hex: "4A6378"), aiTx: Color(hex: "DCE8F3"))

    /// 11K Silver — the silver body with the black deck header.
    static let silver = Palette(id: "k11s", dark: false,
        bg: Color(hex: "D6D7DA"), p1: Color(hex: "ECECEE"), p2: Color(hex: "E1E2E5"), p3: Color(hex: "CFD1D5"), line: Color(hex: "BEC0C5"),
        tx: Color(hex: "1F2023"), tx2: Color(hex: "2C2E32"), mu: Color(hex: "4F5258"), dim: Color(hex: "676A71"),
        acc: Color(hex: "3F6F99"), acc2: Color(hex: "3F6F99"), acctx: .white, pos: Color(hex: "2F5F8A"), neg: Color(hex: "B81F27"),
        hi: Color(hex: "D8262E"), hitx: .white, hi2: Color(hex: "B81F27"),
        hdr: Color(hex: "1F2023"), hdrtx: Color(hex: "DEDFE1"), hdrmu: Color(hex: "A3A6AC"),
        rlo: Color(hex: "B81F27"), rloBg: Color(hex: "D8262E", alpha: 0.13), rmid: Color(hex: "8A5300"), rmidBg: Color(hex: "C87800", alpha: 0.14),
        rok: Color(hex: "1F2023"), rokBg: Color(hex: "DCDDE0"), rhi: Color(hex: "2F5F8A"), rhiBg: Color(hex: "3F6F99", alpha: 0.15),
        aiBg: Color(hex: "E2EAF2"), aiLine: Color(hex: "A9BFD3"), aiTx: Color(hex: "2C5378"))

    /// Slate — the 26K / SP-12 look.
    static let slate = Palette(id: "slate", dark: true,
        bg: Color(hex: "4E5C6A"), p1: Color(hex: "3C4753"), p2: Color(hex: "2F3943"), p3: Color(hex: "46525F"), line: Color(hex: "5B6876"),
        tx: Color(hex: "E7E7E1"), tx2: Color(hex: "D7DCE1"), mu: Color(hex: "CDD5DC"), dim: Color(hex: "A7B1BC"),
        acc: Color(hex: "39B4F2"), acc2: Color(hex: "8FD6F8"), acctx: Color(hex: "0E1D28"), pos: Color(hex: "8EE3A8"), neg: Color(hex: "FF9EA2"),
        hi: Color(hex: "D42A31"), hitx: .white, hi2: Color(hex: "8FD6F8"),
        hdr: Color(hex: "2F3943"), hdrtx: Color(hex: "E7E7E1"), hdrmu: Color(hex: "C3CCD4"),
        rlo: Color(hex: "FFB4B7"), rloBg: Color(hex: "E8343B", alpha: 0.32), rmid: Color(hex: "FFD08A"), rmidBg: Color(hex: "FFAA3C", alpha: 0.22),
        rok: Color(hex: "E7E7E1"), rokBg: Color(hex: "4A5664"), rhi: Color(hex: "A9F0BF"), rhiBg: Color(hex: "50C878", alpha: 0.24),
        aiBg: Color(hex: "33658C"), aiLine: Color(hex: "4C82AD"), aiTx: Color(hex: "EAF6FF"))

    static func named(_ mode: String, systemDark: Bool) -> Palette {
        switch mode { case "k11s": return silver; case "slate": return slate; case "auto": return systemDark ? deck : silver; default: return deck }
    }
}

private struct PaletteKey: EnvironmentKey { static let defaultValue = Palette.deck }
extension EnvironmentValues { var pal: Palette { get { self[PaletteKey.self] } set { self[PaletteKey.self] = newValue } } }

enum Fonts {
    static func display(_ size: CGFloat) -> Font { .custom("ArchivoBlack-Regular", size: size) }
    static func mono(_ size: CGFloat, _ w: Font.Weight = .semibold) -> Font { .system(size: size, weight: w, design: .monospaced) }
}

// MARK: - Building blocks

struct Card<Content: View>: View {
    @Environment(\.pal) var pal
    var title: String? = nil
    var badge: AnyView? = nil
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let title {
                HStack(spacing: 6) {
                    Text(title.uppercased()).font(.system(size: 12.5, weight: .bold)).kerning(0.8).foregroundStyle(pal.mu)
                    if let badge { badge }
                    Spacer(minLength: 0)
                }
            }
            content
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(pal.p1, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(pal.line, lineWidth: 1))
    }
}

struct Tag: View {
    @Environment(\.pal) var pal
    let text: String
    var style: Style = .accent
    enum Style { case accent, danger, info, quiet }
    var body: some View {
        let (fg, bg): (Color, Color) = {
            switch style {
            case .accent: return (pal.hitx, pal.hi)
            case .danger: return (.white, pal.neg)
            case .info: return (pal.acctx, pal.acc)
            case .quiet: return (pal.acc2, pal.p3)
            }
        }()
        Text(text).font(.system(size: 10.5, weight: .bold)).padding(.horizontal, 5).padding(.vertical, 1.5)
            .foregroundStyle(fg).background(bg, in: RoundedRectangle(cornerRadius: 5))
    }
}

struct RatingPill: View {
    @Environment(\.pal) var pal
    let r: String
    var body: some View {
        let c = pal.ratingColors(r)
        Text(r).font(Fonts.mono(Rating.isNum(r) ? 15 : 12, .bold)).foregroundStyle(c.fg)
            .frame(minWidth: 40, minHeight: 32).padding(.horizontal, 4)
            .background(c.bg, in: RoundedRectangle(cornerRadius: 8))
    }
}

struct Chip: View {
    @Environment(\.pal) var pal
    let text: String
    var on = false
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(text).font(.system(size: 13, weight: on ? .semibold : .medium)).lineLimit(1)
                .padding(.horizontal, 11).padding(.vertical, 7)
                .foregroundStyle(on ? pal.hi2 : pal.mu)
                .background(on ? pal.hi.opacity(0.12) : pal.p2, in: Capsule())
                .overlay(Capsule().stroke(on ? pal.hi : pal.line, lineWidth: 1))
        }.buttonStyle(.plain)
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.pal) var pal
    @Environment(\.isEnabled) var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 16, weight: .bold)).frame(maxWidth: .infinity).padding(.vertical, 13)
            .foregroundStyle(pal.hitx).background(pal.hi.opacity(configuration.isPressed ? 0.8 : 1), in: RoundedRectangle(cornerRadius: 12))
            .opacity(enabled ? 1 : 0.45)
    }
}

struct SecondaryButtonStyle: ButtonStyle {
    @Environment(\.pal) var pal
    var danger = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 15, weight: .semibold)).frame(maxWidth: .infinity).padding(.vertical, 12)
            .foregroundStyle(danger ? pal.neg : pal.tx).background(pal.p3.opacity(configuration.isPressed ? 0.7 : 1), in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(pal.line, lineWidth: 1))
    }
}

struct AIButtonStyle: ButtonStyle {
    @Environment(\.pal) var pal
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 15, weight: .semibold)).frame(maxWidth: .infinity).padding(.vertical, 12)
            .foregroundStyle(pal.aiTx).background(pal.aiBg.opacity(configuration.isPressed ? 0.8 : 1), in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(pal.aiLine, lineWidth: 1))
    }
}

/// Text field / editor styling.
struct FieldBox: ViewModifier {
    @Environment(\.pal) var pal
    func body(content: Content) -> some View {
        content.padding(.horizontal, 12).padding(.vertical, 10)
            .background(pal.p2, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(pal.line, lineWidth: 1))
            .foregroundStyle(pal.tx)
    }
}
extension View { func fieldBox() -> some View { modifier(FieldBox()) } }

struct Labeled<Content: View>: View {
    @Environment(\.pal) var pal
    let label: String
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(label).font(.system(size: 12.5, weight: .semibold)).foregroundStyle(pal.mu)
            content
        }
    }
}

/// Multi-line editor with a placeholder.
struct NoteEditor: View {
    @Environment(\.pal) var pal
    @Binding var text: String
    var placeholder = ""
    var minHeight: CGFloat = 96
    var disabled = false
    var body: some View {
        ZStack(alignment: .topLeading) {
            if text.isEmpty {
                Text(placeholder).foregroundStyle(pal.dim).padding(.horizontal, 17).padding(.vertical, 18).allowsHitTesting(false)
            }
            TextEditor(text: $text).scrollContentBackground(.hidden).padding(6).frame(minHeight: minHeight).disabled(disabled)
        }
        .background(pal.p2, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(pal.line, lineWidth: 1))
        .foregroundStyle(pal.tx)
    }
}

struct InfoBox: View {
    @Environment(\.pal) var pal
    let title: String
    let lines: [String]
    var danger = true
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.system(size: 14, weight: .bold))
            ForEach(lines, id: \.self) { Text("• " + $0).font(.system(size: 13.5)) }
        }
        .foregroundStyle(danger ? pal.tx : pal.mu)
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .background(danger ? pal.neg.opacity(0.12) : pal.p2, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(danger ? pal.neg : pal.line, style: StrokeStyle(lineWidth: 1, dash: danger ? [] : [4])))
    }
}

/// The 11K-style app logo: silver FTO, LED step meter, four-colour stripe.
struct AppLogo: View {
    var size: CGFloat = 36
    var body: some View {
        ZStack {
            Color(hex: "1F2023")
            VStack(spacing: 0) {
                Spacer(minLength: 0)
                Text("FTO").font(Fonts.display(size * 0.31)).foregroundStyle(Color(hex: "DEDFE1")).padding(.top, size * 0.14)
                HStack(spacing: size * 0.02) {
                    ForEach(0..<11, id: \.self) { k in
                        RoundedRectangle(cornerRadius: size * 0.01).fill(Color(hex: k < 6 ? "FF3B2F" : "36383D")).frame(width: size * 0.046, height: size * 0.07)
                    }
                }.padding(.top, size * 0.06)
                Spacer(minLength: 0)
                HStack(spacing: 0) {
                    ForEach(["D8262E", "EFB3B5", "DEDFE1", "9FBCD6"], id: \.self) { Color(hex: $0) }
                }.frame(height: size * 0.12)
            }
        }
        .frame(width: size, height: size).clipShape(RoundedRectangle(cornerRadius: size * 0.225))
        .accessibilityLabel("FTO FieldBook")
    }
}

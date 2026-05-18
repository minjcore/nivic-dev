#if os(iOS)
import SwiftUI
import AVFoundation
import CoreImage.CIFilterBuiltins
import UserNotifications

// ══════════════════════════════════════════════════════════════════════════════
//  MARK: - Root App
// ══════════════════════════════════════════════════════════════════════════════

public struct SavingApp: View {
    @StateObject private var client: SavingClient
    @State private var session: SessionState = .gate
    private let merchantsClient: MerchantsClient
    private let cardsClient:     CardsClient
    private let tomcatsClient:   TomcatsClient

    public init(host: String = "127.0.0.1", port: UInt16 = 7474,
                merchantsURL: String = "http://localhost:8090",
                cardsURL:     String = "http://localhost:8091",
                tomcatsURL:   String = "http://localhost:8093") {
        _client         = StateObject(wrappedValue: SavingClient(host: host, port: port))
        merchantsClient = MerchantsClient(baseURL: merchantsURL)
        cardsClient     = CardsClient(baseURL: cardsURL)
        tomcatsClient   = TomcatsClient(baseURL: tomcatsURL)
    }

    public var body: some View {
        Group {
            switch session {
            case .gate:
                GateView(client: client) { id in session = .home(id: id) }
            case .home(let id):
                HomeView(client: client, accountID: id,
                         onLogout: { session = .gate },
                         merchantsClient: merchantsClient,
                         cardsClient: cardsClient,
                         tomcatsClient: tomcatsClient)
            }
        }
        .preferredColorScheme(.dark)
        .task {
            let center = UNUserNotificationCenter.current()
            let granted = (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
            center.delegate = ForegroundNotificationDelegate.shared
            if granted {
                await MainActor.run { UIApplication.shared.registerForRemoteNotifications() }
            }
            try? await client.connect()
        }
    }

    private enum SessionState {
        case gate
        case home(id: UInt32)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  MARK: - Gate (Login / Register)
// ══════════════════════════════════════════════════════════════════════════════

struct GateView: View {
    let client: SavingClient
    let onLogin: (UInt32) -> Void

    @State private var idText   = ""
    @State private var password = ""
    @State private var error: String?
    @State private var loading  = false

    private var canSubmit: Bool { !idText.isEmpty && !password.isEmpty && !loading }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 24) {
                Spacer()
                Text("SAVING")
                    .font(.system(size: 42, weight: .black, design: .monospaced))
                    .foregroundStyle(.white)

                WireField("ID tài khoản", text: $idText)
                    .keyboardType(.numberPad)
                WireSecureField("Mật khẩu", text: $password)

                if let error {
                    Text(error).font(.caption).foregroundStyle(.red)
                }

                HStack(spacing: 12) {
                    WirePrimaryButton(title: "VÀO VÍ", loading: loading, disabled: !canSubmit) {
                        Task { await submit(isNew: false) }
                    }
                    WirePrimaryButton(title: "TẠO VÍ", loading: loading, disabled: !canSubmit) {
                        Task { await submit(isNew: true) }
                    }
                }

                Spacer()
            }
            .padding(.horizontal, 32)
        }
    }

    private func submit(isNew: Bool) async {
        guard let id = UInt32(idText), AccountID.isValid(id) else {
            error = "ID phải từ 16.777.216 đến 4.294.967.295"; return
        }
        loading = true; error = nil
        defer { loading = false }
        do {
            if isNew { try await client.createAccount(id: id, password: password) }
            try await client.login(id: id, password: password)
            onLogin(id)
        } catch WireError.serverError(let code) {
            switch code {
            case .errIdTaken:     error = "ID này đã có chủ."
            case .errIdReserved:  error = "ID này nằm trong kho VIP."
            case .errBadPassword: error = "Sai mật khẩu."
            default:              error = "Lỗi: \(code)"
            }
        } catch {
            self.error = error.localizedDescription
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  MARK: - Home (SuperApp shell)
// ══════════════════════════════════════════════════════════════════════════════

struct HomeView: View {
    let client:          SavingClient
    let accountID:       UInt32
    let onLogout:        () -> Void
    let merchantsClient: MerchantsClient
    let cardsClient:     CardsClient
    let tomcatsClient:   TomcatsClient

    @State private var balance: UInt64 = 0
    @State private var showTransfer = false
    @State private var showHistory  = false
    @State private var showQR       = false
    @State private var showSearch   = false
    @State private var showGuardian = false
    @State private var showQRScan   = false
    @State private var showCards    = false
    @State private var showMerchant     = false
    @State private var showLoyalty      = false
    @State private var showPaymentToken = false
    @State private var toast: String? = nil

    var body: some View {
        ZStack(alignment: .bottomTrailing) {

            Color.black.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {

                    // ── Balance card ──────────────────────────────────────
                    balanceCard

                    // ── Quick actions ─────────────────────────────────────
                    HStack(spacing: 10) {
                        QuickAction(icon: "arrow.up.circle.fill", label: "GỬI") {
                            showTransfer = true
                        }
                        QuickAction(icon: "qrcode.viewfinder", label: "QUÉT QR") {
                            showQRScan = true
                        }
                        QuickAction(icon: "qrcode", label: "QR NHẬN") {
                            showQR = true
                        }
                        QuickAction(icon: "creditcard.fill", label: "THẺ") {
                            showCards = true
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 20)

                    // ── Mini-app grid ─────────────────────────────────────
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Ứng dụng")
                            .font(.caption)
                            .foregroundStyle(.gray)
                            .padding(.horizontal, 20)

                        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()),
                                            GridItem(.flexible()), GridItem(.flexible())],
                                  spacing: 16) {
                            MiniAppTile(icon: "list.bullet.rectangle.portrait", label: "Lịch sử") {
                                showHistory = true
                            }
                            MiniAppTile(icon: "qrcode.viewfinder", label: "QR nhận") {
                                showQR = true
                            }
                            MiniAppTile(icon: "person.2.fill", label: "Bảo hộ") {
                                showGuardian = true
                            }
                            MiniAppTile(icon: "storefront.fill", label: "Bán hàng") {
                                showMerchant = true
                            }
                            MiniAppTile(icon: "star.circle.fill", label: "Tích điểm") {
                                showLoyalty = true
                            }
                            MiniAppTile(icon: "key.fill", label: "Mã TT") {
                                showPaymentToken = true
                            }
                            MiniAppTile(icon: "arrow.counterclockwise.circle", label: "Phục hồi") {
                                // coming soon
                            }
                        }
                        .padding(.horizontal, 20)
                    }
                    .padding(.top, 28)

                    Spacer(minLength: 120) // room for FAB
                }
            }
            .refreshable { await refreshBalance() }

            // ── Search FAB ────────────────────────────────────────────────
            Button { showSearch = true } label: {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(.black)
                    .frame(width: 58, height: 58)
                    .background(Color.white)
                    .clipShape(Circle())
                    .shadow(color: .white.opacity(0.25), radius: 12, y: 4)
            }
            .padding(24)
        }
        .sheet(isPresented: $showTransfer) {
            TransferSheet(client: client) { await refreshBalance() }
        }
        .sheet(isPresented: $showHistory) {
            HistorySheet(client: client)
        }
        .sheet(isPresented: $showQR) {
            QRSheet(accountID: accountID)
        }
        .sheet(isPresented: $showSearch) {
            SearchSheet(client: client, accountID: accountID)
        }
        .sheet(isPresented: $showGuardian) {
            GuardianSheet(client: client)
        }
        .sheet(isPresented: $showQRScan) {
            QRScanSheet(client: client, merchantsClient: merchantsClient) { await refreshBalance() }
        }
        .sheet(isPresented: $showCards) {
            CardManagementSheet(cardsClient: cardsClient, uid: accountID) { await refreshBalance() }
        }
        .sheet(isPresented: $showMerchant) {
            MerchantSheet(merchantsClient: merchantsClient, savingClient: client, uid: accountID)
        }
        .sheet(isPresented: $showLoyalty) {
            MyLoyaltySheet(merchantsClient: merchantsClient, uid: accountID)
        }
        .sheet(isPresented: $showPaymentToken) {
            PaymentTokenSheet(uid: accountID)
        }
        .task { await refreshBalance() }
        .onReceive(NotificationCenter.default.publisher(for: .savingDeviceToken)) { note in
            guard let token = note.userInfo?["token"] as? String else { return }
            Task {
                try? await cardsClient.registerDeviceToken(uid: accountID, token: token)
                try? await tomcatsClient.registerToken(uid: accountID, token: token)
            }
        }
        .onChange(of: client.lastTransferIn) { transfer in
            guard let t = transfer else { return }
            Task { await refreshBalance() }
            let fmt = NumberFormatter()
            fmt.numberStyle = .decimal; fmt.groupingSeparator = "."
            let amt = (fmt.string(from: NSNumber(value: t.amount)) ?? "\(t.amount)") + " ₫"
            showToast("+\(amt) từ #\(t.fromID) 🎉")
        }
        .overlay(alignment: .top) {
            if let msg = toast {
                Text(msg)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                    .background(Color.white)
                    .clipShape(Capsule())
                    .shadow(radius: 8)
                    .padding(.top, 12)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.spring(duration: 0.3), value: toast)
    }

    private func showToast(_ msg: String) {
        toast = msg
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { toast = nil }
    }

    private var balanceCard: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text("#\(accountID)")
                    .font(.system(size: 13, weight: .medium, design: .monospaced))
                    .foregroundStyle(.gray)
                Text(balance.vndFormatted)
                    .font(.system(size: 38, weight: .black))
                    .foregroundStyle(.white)
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
            }
            Spacer()
            Button {
                Task { try? await client.logout(); onLogout() }
            } label: {
                Image(systemName: "rectangle.portrait.and.arrow.right")
                    .foregroundStyle(.gray)
                    .font(.title3)
            }
        }
        .padding(24)
        .background(Color.white.opacity(0.05))
    }

    private func refreshBalance() async {
        balance = (try? await client.balance()) ?? balance
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  MARK: - Transfer sheet
// ══════════════════════════════════════════════════════════════════════════════

struct TransferSheet: View {
    let client: SavingClient
    let onDone: () async -> Void

    @State private var toID   = ""
    @State private var amount = ""
    @State private var error: String?
    @State private var loading = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 20) {
                    WireField("ID người nhận", text: $toID).keyboardType(.numberPad)
                    WireField("Số tiền (VND)", text: $amount).keyboardType(.numberPad)
                    if let error { Text(error).foregroundStyle(.red).font(.caption) }
                    WirePrimaryButton(title: "GỬI NGAY", loading: loading, disabled: false) {
                        Task { await send() }
                    }
                }
                .padding(24)
            }
            .navigationTitle("Chuyển tiền")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark)
        }
    }

    private func send() async {
        guard let to = UInt32(toID), let amt = UInt64(amount) else {
            error = "Nhập ID và số tiền hợp lệ"; return
        }
        loading = true; error = nil
        defer { loading = false }
        do {
            try await client.transfer(to: to, amount: amt)
            await onDone()
            dismiss()
        } catch WireError.serverError(let code) {
            error = code == .errLowBalance ? "Không đủ số dư." : "Lỗi: \(code)"
        } catch {
            self.error = error.localizedDescription
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  MARK: - History sheet
// ══════════════════════════════════════════════════════════════════════════════

struct HistorySheet: View {
    let client: SavingClient

    @State private var txs: [Transaction] = []
    @State private var loading = true
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                Group {
                    if loading {
                        ProgressView().tint(.white)
                    } else if txs.isEmpty {
                        Text("Chưa có giao dịch nào")
                            .foregroundStyle(.gray)
                    } else {
                        List(txs) { tx in
                            TxRow(tx: tx)
                                .listRowBackground(Color.white.opacity(0.04))
                        }
                        .listStyle(.plain)
                        .scrollContentBackground(.hidden)
                    }
                }
            }
            .navigationTitle("Lịch sử")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Đóng") { dismiss() }.foregroundStyle(.white)
                }
            }
        }
        .task {
            txs = (try? await client.history()) ?? []
            loading = false
        }
    }
}

struct TxRow: View {
    let tx: Transaction
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: tx.direction == .received ? "arrow.down.left.circle.fill"
                                                        : "arrow.up.right.circle.fill")
                .font(.title2)
                .foregroundStyle(tx.direction == .received ? .green : .orange)

            VStack(alignment: .leading, spacing: 2) {
                Text(tx.direction == .received ? "Nhận từ" : "Gửi đến")
                    .font(.caption).foregroundStyle(.gray)
                Text("#\(tx.counterpartID)")
                    .font(.system(.body, design: .monospaced))
                    .foregroundStyle(.white)
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                Text((tx.direction == .received ? "+" : "−") + tx.amount.vndFormatted)
                    .font(.system(.callout, weight: .semibold))
                    .foregroundStyle(tx.direction == .received ? .green : .white)
                if tx.direction == .sent {
                    let pts = tx.amount / 10_000
                    if pts > 0 {
                        Text("+\(pts) điểm")
                            .font(.caption2)
                            .foregroundStyle(Color.yellow)
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  MARK: - QR sheet
// ══════════════════════════════════════════════════════════════════════════════

struct QRSheet: View {
    let accountID: UInt32
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 28) {
                    Text("Quét để gửi tiền cho tôi")
                        .foregroundStyle(.gray)
                        .font(.subheadline)

                    if let qr = qrImage(for: "saving://\(accountID)") {
                        qr
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 220, height: 220)
                            .padding(16)
                            .background(Color.white)
                            .clipShape(RoundedRectangle(cornerRadius: 16))
                    }

                    Text("#\(accountID)")
                        .font(.system(size: 22, weight: .bold, design: .monospaced))
                        .foregroundStyle(.white)
                }
            }
            .navigationTitle("Nhận tiền")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Đóng") { dismiss() }.foregroundStyle(.white)
                }
            }
        }
    }

    private func qrImage(for string: String) -> Image? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        guard let cgImage = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return Image(uiImage: UIImage(cgImage: cgImage))
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  MARK: - Search sheet  ← MoMo không làm, mình làm
// ══════════════════════════════════════════════════════════════════════════════

struct SearchSheet: View {
    let client:    SavingClient
    let accountID: UInt32

    @State private var query     = ""
    @State private var allTxs:   [Transaction] = []
    @State private var loading   = true
    @State private var showTransferTo: UInt32? = nil
    @Environment(\.dismiss) private var dismiss

    private var results: [Transaction] {
        guard !query.isEmpty else { return allTxs }
        return allTxs.filter { "\($0.counterpartID)".contains(query) }
    }

    private var lookupID: UInt32? {
        guard let n = UInt32(query), AccountID.isValid(n) else { return nil }
        return n
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 0) {

                    // Search bar
                    HStack(spacing: 10) {
                        Image(systemName: "magnifyingglass").foregroundStyle(.gray)
                        TextField("Tìm ID hoặc giao dịch…", text: $query)
                            .foregroundStyle(.white)
                            .keyboardType(.default)
                            .autocorrectionDisabled()
                        if !query.isEmpty {
                            Button { query = "" } label: {
                                Image(systemName: "xmark.circle.fill").foregroundStyle(.gray)
                            }
                        }
                    }
                    .padding(12)
                    .background(Color.white.opacity(0.08))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .padding(.horizontal, 16)
                    .padding(.top, 8)

                    // Quick action: transfer to looked-up ID
                    if let id = lookupID {
                        Button {
                            showTransferTo = id
                        } label: {
                            HStack {
                                Image(systemName: "arrow.up.circle.fill")
                                    .foregroundStyle(.orange)
                                Text("Gửi tiền đến #\(id)")
                                    .foregroundStyle(.white)
                                Spacer()
                                Image(systemName: "chevron.right").foregroundStyle(.gray)
                            }
                            .padding(16)
                            .background(Color.white.opacity(0.06))
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                        }
                        .padding(.horizontal, 16)
                        .padding(.top, 12)
                    }

                    // Results list
                    if loading {
                        Spacer()
                        ProgressView().tint(.white)
                        Spacer()
                    } else if results.isEmpty && !query.isEmpty {
                        Spacer()
                        Text("Không tìm thấy giao dịch nào").foregroundStyle(.gray)
                        Spacer()
                    } else {
                        List(results) { tx in
                            TxRow(tx: tx).listRowBackground(Color.clear)
                        }
                        .listStyle(.plain)
                        .scrollContentBackground(.hidden)
                    }
                }
            }
            .navigationTitle("Tìm kiếm")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Đóng") { dismiss() }.foregroundStyle(.white)
                }
            }
        }
        .sheet(item: $showTransferTo) { id in
            QuickTransferSheet(client: client, toID: id)
        }
        .task {
            allTxs = (try? await client.history()) ?? []
            loading = false
        }
    }
}

// Quick transfer with pre-filled ID
struct QuickTransferSheet: View {
    let client: SavingClient
    let toID: UInt32

    @State private var amount  = ""
    @State private var error: String?
    @State private var loading = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 20) {
                    Text("Gửi đến #\(toID)")
                        .font(.system(.title3, design: .monospaced, weight: .bold))
                        .foregroundStyle(.white)

                    WireField("Số tiền (VND)", text: $amount).keyboardType(.numberPad)

                    if let error { Text(error).foregroundStyle(.red).font(.caption) }

                    WirePrimaryButton(title: "GỬI NGAY", loading: loading, disabled: amount.isEmpty) {
                        Task { await send() }
                    }
                }
                .padding(32)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark)
        }
    }

    private func send() async {
        guard let amt = UInt64(amount) else { error = "Số tiền không hợp lệ"; return }
        loading = true; error = nil
        defer { loading = false }
        do {
            try await client.transfer(to: toID, amount: amt)
            dismiss()
        } catch WireError.serverError(let code) {
            error = code == .errLowBalance ? "Không đủ số dư." : "Lỗi: \(code)"
        } catch {
            self.error = error.localizedDescription
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  MARK: - Guardian sheet
// ══════════════════════════════════════════════════════════════════════════════

struct GuardianSheet: View {
    let client: SavingClient
    @State private var guardianID = ""
    @State private var error: String?
    @State private var success: String?
    @State private var loading = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 20) {
                    Text("Thêm người bảo hộ\nkhi bị mất thiết bị, 2/3 bảo hộ duyệt\nlà bạn lấy lại được tài khoản")
                        .font(.caption)
                        .foregroundStyle(.gray)
                        .multilineTextAlignment(.center)

                    WireField("ID người bảo hộ", text: $guardianID).keyboardType(.numberPad)

                    if let error   { Text(error).foregroundStyle(.red).font(.caption) }
                    if let success { Text(success).foregroundStyle(.green).font(.caption) }

                    WirePrimaryButton(title: "THÊM", loading: loading,
                                      disabled: guardianID.isEmpty) {
                        Task { await add() }
                    }
                }
                .padding(32)
            }
            .navigationTitle("Bảo hộ tài khoản")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Đóng") { dismiss() }.foregroundStyle(.white)
                }
            }
        }
    }

    private func add() async {
        guard let id = UInt32(guardianID) else { error = "ID không hợp lệ"; return }
        loading = true; error = nil; success = nil
        defer { loading = false }
        do {
            try await client.addGuardian(id: id)
            success = "Đã thêm #\(id) làm người bảo hộ ✓"
            guardianID = ""
        } catch WireError.serverError(let code) {
            error = code == .errGuardianFull ? "Đã đủ 3 người bảo hộ." : "Lỗi: \(code)"
        } catch {
            self.error = error.localizedDescription
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  MARK: - Shared components
// ══════════════════════════════════════════════════════════════════════════════

struct WireField: View {
    let placeholder: String
    @Binding var text: String
    init(_ placeholder: String, text: Binding<String>) {
        self.placeholder = placeholder; self._text = text
    }
    var body: some View {
        TextField(placeholder, text: $text)
            .padding(14)
            .background(Color.white.opacity(0.08))
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .foregroundStyle(.white)
    }
}

struct WireSecureField: View {
    let placeholder: String
    @Binding var text: String
    init(_ placeholder: String, text: Binding<String>) {
        self.placeholder = placeholder; self._text = text
    }
    var body: some View {
        SecureField(placeholder, text: $text)
            .padding(14)
            .background(Color.white.opacity(0.08))
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .foregroundStyle(.white)
    }
}

struct WirePrimaryButton: View {
    let title: String
    let loading: Bool
    let disabled: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Group {
                if loading { ProgressView().tint(.black) }
                else { Text(title).font(.system(size: 16, weight: .bold)) }
            }
            .frame(maxWidth: .infinity)
            .padding(16)
            .background(Color.white)
            .foregroundStyle(.black)
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .disabled(loading || disabled)
    }
}

struct QuickAction: View {
    let icon: String
    let label: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack {
                Image(systemName: icon).font(.title3)
                Text(label).font(.system(size: 13, weight: .bold, design: .monospaced))
            }
            .frame(maxWidth: .infinity)
            .padding(14)
            .background(Color.white.opacity(0.08))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .foregroundStyle(.white)
        }
    }
}

struct MiniAppTile: View {
    let icon: String
    let label: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 26))
                    .foregroundStyle(.white)
                Text(label)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(.gray)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Color.white.opacity(0.05))
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  MARK: - Payment Token Sheet (offline TOTP, 32-char)
// ══════════════════════════════════════════════════════════════════════════════

struct PaymentTokenSheet: View {
    let uid: UInt32

    @ObservedObject private var totp: TOTPManager = .shared
    @State private var showEnroll = false
    @Environment(\.dismiss) private var dismiss

    private var formattedCode: String {
        // 32 chars → 8 groups of 4 separated by spaces
        stride(from: 0, to: totp.code.count, by: 4)
            .map { totp.code.dropFirst($0).prefix(4) }
            .joined(separator: " ")
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 0) {
                // top bar
                HStack {
                    Spacer()
                    Button { dismiss() } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title2).foregroundStyle(.gray)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, 20)

                Spacer()

                // countdown ring + code
                ZStack {
                    Circle()
                        .stroke(Color.white.opacity(0.08), lineWidth: 6)
                        .frame(width: 200, height: 200)
                    Circle()
                        .trim(from: 0, to: CGFloat(totp.remaining) / 30.0)
                        .stroke(Color.white, style: StrokeStyle(lineWidth: 6, lineCap: .round))
                        .frame(width: 200, height: 200)
                        .rotationEffect(.degrees(-90))
                        .animation(.linear(duration: 1), value: totp.remaining)
                    VStack(spacing: 4) {
                        Text("\(totp.remaining)s")
                            .font(.system(size: 13, weight: .medium, design: .monospaced))
                            .foregroundStyle(.gray)
                    }
                }
                .padding(.bottom, 28)

                Text("MÃ THANH TOÁN")
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .foregroundStyle(.gray)
                    .tracking(2)
                    .padding(.bottom, 12)

                // 32-char code (8 groups of 4)
                Text(formattedCode)
                    .font(.system(size: 18, weight: .bold, design: .monospaced))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
                    .padding(.bottom, 32)

                // QR of payment token
                if let qrImg = generateQR(totp.paymentURL(uid: uid)) {
                    Image(uiImage: qrImg)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 200, height: 200)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .padding(.bottom, 8)
                }

                Text("Merchant quét QR này để xác nhận")
                    .font(.caption)
                    .foregroundStyle(.gray)
                    .padding(.bottom, 28)

                // enrollment QR toggle
                Button {
                    showEnroll.toggle()
                } label: {
                    Label(showEnroll ? "Ẩn QR đăng ký" : "Đăng ký với cửa hàng mới",
                          systemImage: "qrcode")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(14)
                        .background(Color.white.opacity(0.08))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .padding(.horizontal, 24)

                if showEnroll {
                    VStack(spacing: 8) {
                        Text("Merchant quét QR này một lần để đăng ký")
                            .font(.caption)
                            .foregroundStyle(.gray)
                        if let img = generateQR(totp.enrollmentURL(uid: uid)) {
                            Image(uiImage: img)
                                .interpolation(.none)
                                .resizable()
                                .frame(width: 180, height: 180)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                        }
                    }
                    .padding(.top, 16)
                }

                Spacer()
            }
        }
    }

    private func generateQR(_ content: String) -> UIImage? {
        let data = content.data(using: .utf8)
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let ci = filter.outputImage else { return nil }
        let scaled = ci.transformed(by: CGAffineTransform(scaleX: 6, y: 6))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  MARK: - Extensions
// ══════════════════════════════════════════════════════════════════════════════

// Show banner even when app is in foreground
final class ForegroundNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    static let shared = ForegroundNotificationDelegate()
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler handler: @escaping (UNNotificationPresentationOptions) -> Void) {
        handler([.banner, .sound])
    }
}

extension UInt64 {
    var vndFormatted: String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.groupingSeparator = "."
        return (f.string(from: NSNumber(value: self)) ?? "\(self)") + " ₫"
    }
}

extension UInt32: @retroactive Identifiable {
    public var id: UInt32 { self }
}

// ══════════════════════════════════════════════════════════════════════════════
//  MARK: - Merchant QR payload
// ══════════════════════════════════════════════════════════════════════════════
//  Signed QR:  saving://pay?pr=BASE64URL(PaymentRequest JSON)
//  Simple QR:  saving://pay?mid=12345&amount=50000&ref=ORDER_REF

struct MerchantPayload: Identifiable {
    var id: UInt32 { mid }
    let mid:          UInt32
    let amount:       UInt64?
    let ref:          String?
    let orderID:      String?          // from signed payment_request
    let paymentReq:   PaymentRequest?  // non-nil when QR is signed
    let verified:     Bool             // true after Merchants Host confirms sig
    let merchantName: String?          // set after verification

    static func simple(mid: UInt32, amount: UInt64?, ref: String?) -> MerchantPayload {
        MerchantPayload(mid: mid, amount: amount, ref: ref,
                        orderID: nil, paymentReq: nil, verified: false, merchantName: nil)
    }

    static func signed(pr: PaymentRequest) -> MerchantPayload {
        MerchantPayload(mid: pr.mid, amount: pr.amount, ref: nil,
                        orderID: pr.orderID, paymentReq: pr, verified: false, merchantName: nil)
    }

    func withVerification(name: String) -> MerchantPayload {
        MerchantPayload(mid: mid, amount: amount, ref: ref,
                        orderID: orderID, paymentReq: paymentReq, verified: true, merchantName: name)
    }

    static func parse(_ raw: String) -> MerchantPayload? {
        guard let url   = URL(string: raw),
              url.scheme == "saving", url.host == "pay",
              let items  = URLComponents(url: url,
                                        resolvingAgainstBaseURL: false)?.queryItems
        else { return nil }

        // Signed payment_request: ?pr=BASE64URL
        if let prParam = items.first(where: { $0.name == "pr" })?.value,
           let pr = try? PaymentRequest.decode(base64url: prParam) {
            return .signed(pr: pr)
        }

        // Simple: ?mid=X&amount=Y&ref=Z
        guard let midStr = items.first(where: { $0.name == "mid" })?.value,
              let mid    = UInt32(midStr) else { return nil }
        let amount = items.first(where: { $0.name == "amount" })?.value.flatMap { UInt64($0) }
        let ref    = items.first(where: { $0.name == "ref" })?.value
        return .simple(mid: mid, amount: amount, ref: ref)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  MARK: - QR scan sheet
// ══════════════════════════════════════════════════════════════════════════════

struct QRScanSheet: View {
    let client:          SavingClient
    let merchantsClient: MerchantsClient
    let onDone: () async -> Void

    @State private var state: ScanState = .scanning
    @Environment(\.dismiss) private var dismiss

    private enum ScanState {
        case scanning
        case verifying(MerchantPayload)
        case ready(MerchantPayload)
        case totpEnroll(uid: UInt32, secretB32: String)
        case totpPay(uid: UInt32, token: String, isValid: Bool)
        case failed(String)
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                switch state {
                case .scanning:
                    scanningView
                case .verifying:
                    verifyingView
                case .ready(let payload):
                    MerchantPaySheet(client: client, merchantsClient: merchantsClient,
                                     payload: payload) {
                        Task { await onDone() }
                        dismiss()
                    }
                case .totpEnroll(let uid, _):
                    totpEnrollSuccessView(uid: uid)
                case .totpPay(let uid, let token, let isValid):
                    totpPayResultView(uid: uid, token: token, isValid: isValid)
                case .failed(let msg):
                    failedView(msg)
                }
            }
            .navigationTitle(navTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(leadingLabel) {
                        switch state {
                        case .scanning: dismiss()
                        default:        state = .scanning
                        }
                    }
                    .foregroundStyle(.white)
                }
            }
        }
    }

    // ── Sub-views ────────────────────────────────────────────────────────────

    private var scanningView: some View {
        VStack(spacing: 24) {
            QRCameraPreview { raw in
                if let comps = URLComponents(string: raw),
                   comps.scheme == "saving", comps.host == "totp-enroll",
                   let uidStr = comps.queryItems?.first(where: { $0.name == "uid" })?.value,
                   let uid    = UInt32(uidStr),
                   let secret = comps.queryItems?.first(where: { $0.name == "secret" })?.value {
                    TOTPEnrollmentStore.save(uid: uid, secretB32: secret)
                    state = .totpEnroll(uid: uid, secretB32: secret)
                } else if let comps = URLComponents(string: raw),
                          comps.scheme == "saving", comps.host == "totp-pay",
                          let uidStr = comps.queryItems?.first(where: { $0.name == "uid" })?.value,
                          let uid    = UInt32(uidStr),
                          let token  = comps.queryItems?.first(where: { $0.name == "token" })?.value {
                    let secret  = TOTPEnrollmentStore.secret(for: uid)
                    let isValid = secret.map { TOTP.verify(secret: $0, token: token) } ?? false
                    state = .totpPay(uid: uid, token: token, isValid: isValid)
                } else if let payload = MerchantPayload.parse(raw) {
                    if payload.paymentReq != nil {
                        state = .verifying(payload)
                        Task { await verify(payload) }
                    } else {
                        state = .ready(payload)
                    }
                } else {
                    state = .failed("QR không hợp lệ")
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 20))
            .frame(height: 320)
            .padding(.horizontal, 24)

            Text("Quét QR của người bán để thanh toán")
                .foregroundStyle(.gray).font(.subheadline)
        }
        .padding(.top, 16)
    }

    private var verifyingView: some View {
        VStack(spacing: 16) {
            ProgressView().tint(.white).scaleEffect(1.4)
            Text("Đang xác thực chữ ký người bán…")
                .foregroundStyle(.gray).font(.subheadline)
        }
    }

    private func failedView(_ msg: String) -> some View {
        VStack(spacing: 20) {
            Image(systemName: "xmark.circle.fill")
                .font(.system(size: 52)).foregroundStyle(.red)
            Text(msg)
                .foregroundStyle(.white).font(.body)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Button("Quét lại") { state = .scanning }
                .foregroundStyle(.gray)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private var navTitle: String {
        switch state {
        case .scanning:    return "Quét QR"
        case .verifying:   return "Đang xác thực..."
        case .ready:       return "Xác nhận thanh toán"
        case .totpEnroll:  return "Đăng ký TOTP"
        case .totpPay(_, _, let ok): return ok ? "Xác thực thành công" : "Xác thực thất bại"
        case .failed:      return "Lỗi"
        }
    }

    private var leadingLabel: String {
        if case .scanning = state { return "Đóng" }
        return "Quét lại"
    }

    private func totpEnrollSuccessView(uid: UInt32) -> some View {
        VStack(spacing: 20) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 64)).foregroundStyle(.green)
            Text("Đã đăng ký thành công!")
                .font(.title3.bold()).foregroundStyle(.white)
            Text("UID #\(uid) đã được lưu.\nLần sau chỉ cần quét mã thanh toán.")
                .font(.subheadline).foregroundStyle(.gray)
                .multilineTextAlignment(.center)
            Button("Xong") { dismiss() }
                .frame(maxWidth: .infinity).padding(14)
                .background(Color.white).foregroundStyle(.black)
                .fontWeight(.semibold).cornerRadius(14)
                .padding(.horizontal, 32)
        }
    }

    private func totpPayResultView(uid: UInt32, token: String, isValid: Bool) -> some View {
        VStack(spacing: 20) {
            Image(systemName: isValid ? "checkmark.circle.fill" : "xmark.circle.fill")
                .font(.system(size: 64))
                .foregroundStyle(isValid ? Color.green : Color.red)
            Text(isValid ? "Xác thực thành công" : "Xác thực thất bại")
                .font(.title3.bold()).foregroundStyle(.white)
            if isValid {
                Text("UID #\(uid) đã xác nhận.\nTiến hành tạo đơn hàng.")
                    .font(.subheadline).foregroundStyle(.gray)
                    .multilineTextAlignment(.center)
                Text(stride(from: 0, to: token.count, by: 8)
                        .map { token.dropFirst($0).prefix(8) }
                        .joined(separator: " "))
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.green)
            } else {
                Text(TOTPEnrollmentStore.secret(for: uid) == nil
                     ? "Chưa đăng ký người dùng này.\nYêu cầu quét QR đăng ký trước."
                     : "Mã hết hạn hoặc không đúng.\nYêu cầu làm mới mã.")
                    .font(.subheadline).foregroundStyle(.gray)
                    .multilineTextAlignment(.center)
            }
            Button("Đóng") { dismiss() }
                .frame(maxWidth: .infinity).padding(14)
                .background(Color.white).foregroundStyle(.black)
                .fontWeight(.semibold).cornerRadius(14)
                .padding(.horizontal, 32)
        }
    }

    private func verify(_ payload: MerchantPayload) async {
        guard let pr = payload.paymentReq else { return }
        do {
            let result = try await merchantsClient.verify(pr)
            state = .ready(payload.withVerification(name: result.merchant.name))
        } catch {
            state = .failed(error.localizedDescription)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  MARK: - Merchant pay confirmation
// ══════════════════════════════════════════════════════════════════════════════

struct MerchantPaySheet: View {
    let client:          SavingClient
    let merchantsClient: MerchantsClient
    let payload:         MerchantPayload
    let onDone:          () -> Void

    @State private var amountText: String
    @State private var error:   String?
    @State private var loading  = false
    @State private var success  = false

    init(client: SavingClient, merchantsClient: MerchantsClient,
         payload: MerchantPayload, onDone: @escaping () -> Void) {
        self.client          = client
        self.merchantsClient = merchantsClient
        self.payload         = payload
        self.onDone          = onDone
        _amountText          = State(initialValue: payload.amount.map { "\($0)" } ?? "")
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 28) {
                VStack(spacing: 8) {
                    Image(systemName: "storefront.fill")
                        .font(.system(size: 44))
                        .foregroundStyle(.white.opacity(0.85))
                        .frame(width: 88, height: 88)
                        .background(Color.white.opacity(0.08))
                        .clipShape(Circle())

                    if let name = payload.merchantName {
                        Text(name)
                            .font(.system(size: 20, weight: .bold))
                            .foregroundStyle(.white)
                    }

                    Text("#\(payload.mid)")
                        .font(.system(.subheadline, design: .monospaced))
                        .foregroundStyle(.gray)

                    if payload.verified {
                        Label("Đã xác thực", systemImage: "checkmark.seal.fill")
                            .font(.caption).fontWeight(.semibold)
                            .foregroundStyle(.green)
                            .padding(.horizontal, 12).padding(.vertical, 4)
                            .background(Color.green.opacity(0.12))
                            .clipShape(Capsule())
                    }

                    if let orderID = payload.orderID {
                        Text("Đơn: \(orderID)")
                            .font(.caption2).foregroundStyle(.gray)
                            .padding(.horizontal, 12).padding(.vertical, 4)
                            .background(Color.white.opacity(0.06))
                            .clipShape(Capsule())
                    } else if let ref = payload.ref {
                        Text("Ref: \(ref)")
                            .font(.caption2).foregroundStyle(.gray)
                            .padding(.horizontal, 12).padding(.vertical, 4)
                            .background(Color.white.opacity(0.06))
                            .clipShape(Capsule())
                    }
                }
                .padding(.top, 16)

                if let fixed = payload.amount {
                    VStack(spacing: 4) {
                        Text("Số tiền").font(.caption).foregroundStyle(.gray)
                        Text(fixed.vndFormatted)
                            .font(.system(size: 34, weight: .black)).foregroundStyle(.white)
                    }
                } else {
                    WireField("Số tiền (VND)", text: $amountText)
                        .keyboardType(.numberPad)
                        .padding(.horizontal, 24)
                }

                if let error { Text(error).foregroundStyle(.red).font(.caption) }

                if success {
                    Label("Thanh toán thành công!", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                        .font(.system(.body, weight: .semibold))
                } else {
                    WirePrimaryButton(
                        title: "THANH TOÁN", loading: loading,
                        disabled: payload.amount == nil && amountText.isEmpty
                    ) { Task { await pay() } }
                    .padding(.horizontal, 24)
                }
            }
        }
    }

    private func pay() async {
        let amount = payload.amount ?? UInt64(amountText) ?? 0
        guard amount > 0 else { error = "Nhập số tiền hợp lệ"; return }
        loading = true; error = nil
        defer { loading = false }
        do {
            try await client.payMerchant(mid: payload.mid, amount: amount)
            // Notify Merchants Host order is paid (best-effort, after Wire ACK)
            if let orderID = payload.orderID, let uid = client.uid {
                await merchantsClient.confirmPaid(orderID: orderID, paidBy: uid)
            }
            success = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { onDone() }
        } catch WireError.serverError(let code) {
            error = code == .errLowBalance ? "Không đủ số dư." : "Lỗi: \(code)"
        } catch {
            self.error = error.localizedDescription
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  MARK: - Camera QR scanner
// ══════════════════════════════════════════════════════════════════════════════

private struct QRCameraPreview: UIViewRepresentable {
    let onCode: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }
    func makeUIView(context: Context) -> QRPreviewView {
        let v = QRPreviewView()
        v.delegate = context.coordinator
        v.setup()
        return v
    }
    func updateUIView(_ uiView: QRPreviewView, context: Context) {}

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        let onCode: (String) -> Void
        private var fired = false
        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func metadataOutput(_ output: AVCaptureMetadataOutput,
                            didOutput objects: [AVMetadataObject],
                            from connection: AVCaptureConnection) {
            guard !fired,
                  let obj = objects.first as? AVMetadataMachineReadableCodeObject,
                  let str = obj.stringValue else { return }
            fired = true
            DispatchQueue.main.async { self.onCode(str) }
        }
    }
}

final class QRPreviewView: UIView {
    weak var delegate: AVCaptureMetadataOutputObjectsDelegate?
    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?

    override func layoutSubviews() {
        super.layoutSubviews()
        previewLayer?.frame = bounds
    }

    func setup() {
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            guard granted, let self else { return }
            self.startSession()
        }
    }

    private func startSession() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input  = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { return }
        session.beginConfiguration()
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(delegate, queue: .main)
            output.metadataObjectTypes = [.qr]
        }
        session.commitConfiguration()
        DispatchQueue.main.async {
            let layer = AVCaptureVideoPreviewLayer(session: self.session)
            layer.videoGravity = .resizeAspectFill
            layer.frame = self.bounds
            self.layer.addSublayer(layer)
            self.previewLayer = layer
        }
        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }

    deinit { session.stopRunning() }
}

// ══════════════════════════════════════════════════════════════════════════════
//  MARK: - Card management
// ══════════════════════════════════════════════════════════════════════════════

struct CardManagementSheet: View {
    let cardsClient: CardsClient
    let uid:         UInt32
    let onTopUp:     () async -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var cards:       [CardInfo] = []
    @State private var showAddCard  = false
    @State private var loading      = false
    @State private var error:       String?

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                Group {
                    if cards.isEmpty && !loading {
                        VStack(spacing: 16) {
                            Image(systemName: "creditcard.fill")
                                .font(.system(size: 48)).foregroundStyle(.gray)
                            Text("Chưa có thẻ liên kết")
                                .foregroundStyle(.gray)
                            Button("Thêm thẻ") { showAddCard = true }
                                .foregroundStyle(.white)
                        }
                    } else {
                        List {
                            ForEach(cards) { card in
                                CardRow(card: card) {
                                    await topUp(card: card)
                                } onRemove: {
                                    await remove(card: card)
                                }
                                .listRowBackground(Color.white.opacity(0.05))
                            }
                        }
                        .listStyle(.insetGrouped)
                        .scrollContentBackground(.hidden)
                    }
                }
            }
            .navigationTitle("Thẻ ngân hàng")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Đóng") { dismiss() }.foregroundStyle(.white)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showAddCard = true } label: {
                        Image(systemName: "plus").foregroundStyle(.white)
                    }
                }
            }
            .sheet(isPresented: $showAddCard) {
                AddCardSheet(cardsClient: cardsClient, uid: uid) {
                    await loadCards()
                }
            }
            .task { await loadCards() }
        }
    }

    private func loadCards() async {
        loading = true
        defer { loading = false }
        cards = (try? await cardsClient.listCards(uid: uid)) ?? []
    }

    private func topUp(card: CardInfo) async {
        guard let result = try? await cardsClient.topUp(uid: uid, cardID: card.id, amount: 100_000) else { return }
        _ = result
        await onTopUp()
    }

    private func remove(card: CardInfo) async {
        try? await cardsClient.removeCard(uid: uid, cardID: card.id)
        await loadCards()
    }
}

private struct CardRow: View {
    let card:     CardInfo
    let onTopUp:  () async -> Void
    let onRemove: () async -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(card.label?.isEmpty == false ? card.label! : card.bank)
                        .font(.system(.body, weight: .semibold)).foregroundStyle(.white)
                    Text("\(card.bank)  ···· \(card.last4)  \(card.expiry)")
                        .font(.caption).foregroundStyle(.gray)
                }
                Spacer()
                Image(systemName: "creditcard.fill")
                    .foregroundStyle(.gray)
            }
            HStack(spacing: 12) {
                Button {
                    Task { await onTopUp() }
                } label: {
                    Label("Nạp tiền", systemImage: "arrow.down.circle")
                        .font(.caption).fontWeight(.semibold)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(Color.white.opacity(0.1))
                        .clipShape(Capsule())
                }
                Button(role: .destructive) {
                    Task { await onRemove() }
                } label: {
                    Label("Gỡ", systemImage: "trash")
                        .font(.caption).fontWeight(.semibold)
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(Color.red.opacity(0.12))
                        .clipShape(Capsule())
                }
            }
        }
        .padding(.vertical, 4)
    }
}

private struct AddCardSheet: View {
    let cardsClient: CardsClient
    let uid:         UInt32
    let onAdded:     () async -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var last4   = ""
    @State private var bank    = ""
    @State private var expiry  = ""
    @State private var label   = ""
    @State private var loading = false
    @State private var error:  String?

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 16) {
                    WireField("4 số cuối thẻ", text: $last4)
                        .keyboardType(.numberPad)
                    WireField("Ngân hàng (VD: VCB)", text: $bank)
                    WireField("Hạn thẻ (MM/YY)", text: $expiry)
                    WireField("Tên thẻ (tuỳ chọn)", text: $label)

                    if let error { Text(error).foregroundStyle(.red).font(.caption) }

                    WirePrimaryButton(title: "THÊM THẺ", loading: loading,
                                      disabled: last4.count != 4 || bank.isEmpty || expiry.isEmpty) {
                        Task { await add() }
                    }
                }
                .padding(24)
            }
            .navigationTitle("Thêm thẻ")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Huỷ") { dismiss() }.foregroundStyle(.white)
                }
            }
        }
    }

    private func add() async {
        loading = true; error = nil
        defer { loading = false }
        do {
            _ = try await cardsClient.addCard(uid: uid, last4: last4,
                                              bank: bank, expiry: expiry, label: label)
            await onAdded()
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// MARK: - Merchant Sheet
// ══════════════════════════════════════════════════════════════════════════════

struct MerchantSheet: View {
    let merchantsClient: MerchantsClient
    let savingClient: SavingClient
    let uid: UInt32

    @AppStorage("merchant_token")  private var savedToken = ""
    @AppStorage("merchant_name")   private var savedName  = ""

    var body: some View {
        if savedToken.isEmpty {
            MerchantOnboardingView(merchantsClient: merchantsClient, uid: uid,
                                   onDone: { name, token in
                savedName  = name
                savedToken = token
            })
        } else {
            MerchantDashboardView(merchantsClient: merchantsClient, savingClient: savingClient,
                                  uid: uid, name: savedName, token: savedToken)
        }
    }
}

// ── Onboarding ────────────────────────────────────────────────────────────────

struct MerchantOnboardingView: View {
    let merchantsClient: MerchantsClient
    let uid: UInt32
    let onDone: (String, String) -> Void

    @State private var shopName   = ""
    @State private var loading    = false
    @State private var error: String?
    @State private var step       = 0  // 0=form, 1=success, 2=enter existing token
    @State private var newToken   = ""
    @State private var existToken = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if step == 0 {
                    onboardForm
                } else if step == 2 {
                    enterTokenView
                } else {
                    successView
                }
            }
            .navigationTitle("Đăng ký bán hàng")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Đóng") { dismiss() }
                        .foregroundStyle(.white)
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private var onboardForm: some View {
        VStack(spacing: 32) {
            Spacer()
            Image(systemName: "storefront.fill")
                .font(.system(size: 64))
                .foregroundStyle(.white)

            VStack(spacing: 8) {
                Text("Mở gian hàng của bạn")
                    .font(.title2.bold())
                    .foregroundStyle(.white)
                Text("Nhận thanh toán qua QR code\ntrực tiếp vào tài khoản Saving")
                    .font(.subheadline)
                    .foregroundStyle(.gray)
                    .multilineTextAlignment(.center)
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Tên cửa hàng")
                    .font(.caption)
                    .foregroundStyle(.gray)
                TextField("VD: Quán Cà Phê ABC", text: $shopName)
                    .padding(14)
                    .background(Color.white.opacity(0.08))
                    .cornerRadius(12)
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, 24)

            if let e = error {
                Text(e).font(.caption).foregroundStyle(.red).padding(.horizontal, 24)
            }

            Button {
                Task { await register() }
            } label: {
                Group {
                    if loading {
                        ProgressView().tint(.black)
                    } else {
                        Text("Bắt đầu bán hàng")
                            .fontWeight(.semibold)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(16)
                .background(shopName.trimmingCharacters(in: .whitespaces).isEmpty ? Color.gray : Color.white)
                .foregroundStyle(.black)
                .cornerRadius(14)
            }
            .disabled(shopName.trimmingCharacters(in: .whitespaces).isEmpty || loading)
            .padding(.horizontal, 24)

            Spacer()
        }
    }

    private var successView: some View {
        VStack(spacing: 28) {
            Spacer()
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 72))
                .foregroundStyle(.green)

            Text("Chào mừng, \(savedName)! 🎉")
                .font(.title2.bold())
                .foregroundStyle(.white)

            VStack(spacing: 6) {
                Text("Merchant ID: \(uid)")
                    .font(.headline)
                    .foregroundStyle(.white)
                Text("Lưu token bên dưới — chỉ hiện một lần")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }

            HStack {
                Text(newToken)
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.8))
                    .lineLimit(2)
                Spacer()
                Button {
                    UIPasteboard.general.string = newToken
                } label: {
                    Image(systemName: "doc.on.doc")
                        .foregroundStyle(.white)
                }
            }
            .padding(14)
            .background(Color.white.opacity(0.08))
            .cornerRadius(12)
            .padding(.horizontal, 24)

            Button("Vào Dashboard") {
                onDone(shopName, newToken)
                dismiss()
            }
            .frame(maxWidth: .infinity)
            .padding(16)
            .background(Color.white)
            .foregroundStyle(.black)
            .fontWeight(.semibold)
            .cornerRadius(14)
            .padding(.horizontal, 24)

            Spacer()
        }
        .preferredColorScheme(.dark)
    }

    @AppStorage("merchant_name") private var savedName = ""

    private var enterTokenView: some View {
        VStack(spacing: 24) {
            Spacer()
            Image(systemName: "key.fill")
                .font(.system(size: 52)).foregroundStyle(.white)
            Text("Nhập token cũ")
                .font(.title2.bold()).foregroundStyle(.white)
            Text("Bạn đã đăng ký trước đó.\nNhập token để vào dashboard.")
                .font(.subheadline).foregroundStyle(.gray)
                .multilineTextAlignment(.center)

            TextField("Token", text: $existToken)
                .padding(14)
                .background(Color.white.opacity(0.08))
                .cornerRadius(12)
                .foregroundStyle(.white)
                .font(.system(.caption, design: .monospaced))
                .padding(.horizontal, 24)

            if let e = error { Text(e).font(.caption).foregroundStyle(.red) }

            Button("Xác nhận") {
                let t = existToken.trimmingCharacters(in: .whitespaces)
                guard !t.isEmpty else { error = "Nhập token"; return }
                onDone(shopName.isEmpty ? "Merchant" : shopName, t)
                dismiss()
            }
            .frame(maxWidth: .infinity).padding(16)
            .background(existToken.isEmpty ? Color.gray : Color.white)
            .foregroundStyle(.black).fontWeight(.semibold).cornerRadius(14)
            .disabled(existToken.isEmpty)
            .padding(.horizontal, 24)

            Button("Quay lại") { step = 0; error = nil }
                .foregroundStyle(.gray).font(.caption)
            Spacer()
        }
    }

    private func register() async {
        loading = true; error = nil
        defer { loading = false }
        let name = shopName.trimmingCharacters(in: .whitespaces)
        do {
            newToken = try await merchantsClient.onboard(uid: uid, name: name)
            savedName = name
            step = 1
        } catch VerifyError.rejected(let msg) where msg.contains("đã là merchant") || msg.contains("already") {
            error = "Bạn đã đăng ký. Nhập token cũ để tiếp tục."
            step = 2
        } catch let e as VerifyError {
            error = e.localizedDescription
        } catch {
            self.error = error.localizedDescription
        }
    }
}

// ── Dashboard ─────────────────────────────────────────────────────────────────

struct MerchantDashboardView: View {
    let merchantsClient: MerchantsClient
    let savingClient: SavingClient
    let uid: UInt32
    let name: String
    let token: String

    @State private var stats: MerchantStats?
    @State private var orders: [OrderInfo] = []
    @State private var members: [LoyaltyMember] = []
    @State private var showCreateOrder = false
    @State private var showLoyalty = false
    @State private var loading = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 20) {
                        // ── Stats card ──────────────────────────────────────
                        HStack(spacing: 12) {
                            VStack(spacing: 4) {
                                Text("Doanh thu")
                                    .font(.caption).foregroundStyle(.gray)
                                Text(fmtVND(stats?.totalEarned ?? 0) + " ₫")
                                    .font(.system(size: 28, weight: .bold)).foregroundStyle(.white)
                                Text("\(stats?.orderCount ?? 0) đơn")
                                    .font(.caption).foregroundStyle(.gray)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(18)
                            .background(Color.white.opacity(0.06))
                            .cornerRadius(16)

                            Button { showLoyalty = true } label: {
                                VStack(spacing: 4) {
                                    Image(systemName: "star.fill")
                                        .font(.title2).foregroundStyle(.yellow)
                                    Text("\(members.count)")
                                        .font(.title2.bold()).foregroundStyle(.white)
                                    Text("Thành viên")
                                        .font(.caption).foregroundStyle(.gray)
                                }
                                .frame(maxWidth: .infinity)
                                .padding(18)
                                .background(Color.white.opacity(0.06))
                                .cornerRadius(16)
                            }
                        }
                        .padding(.horizontal, 20)

                        // ── Create order button ─────────────────────────────
                        Button { showCreateOrder = true } label: {
                            Label("Tạo đơn hàng", systemImage: "plus.circle.fill")
                                .fontWeight(.semibold)
                                .frame(maxWidth: .infinity)
                                .padding(16)
                                .background(Color.white)
                                .foregroundStyle(.black)
                                .cornerRadius(14)
                        }
                        .padding(.horizontal, 20)

                        // ── Orders list ─────────────────────────────────────
                        if orders.isEmpty && !loading {
                            Text("Chưa có đơn hàng nào")
                                .font(.caption)
                                .foregroundStyle(.gray)
                                .padding(.top, 40)
                        } else {
                            VStack(spacing: 1) {
                                ForEach(orders, id: \.id) { order in
                                    OrderRow(order: order)
                                }
                            }
                            .background(Color.white.opacity(0.05))
                            .cornerRadius(14)
                            .padding(.horizontal, 20)
                        }
                    }
                    .padding(.top, 16)
                    .padding(.bottom, 40)
                }
                .refreshable { await load() }
            }
            .navigationTitle(name)
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $showCreateOrder, onDismiss: { Task { await load() } }) {
                CreateOrderSheet(merchantsClient: merchantsClient, savingClient: savingClient,
                                 mid: uid, token: token)
            }
            .sheet(isPresented: $showLoyalty) {
                LoyaltyMembersSheet(members: members)
            }
            .task { await load() }
        }
        .preferredColorScheme(.dark)
    }

    private func load() async {
        loading = true
        defer { loading = false }
        async let s = try? merchantsClient.stats(mid: uid, token: token)
        async let o = (try? merchantsClient.listOrders(mid: uid, token: token)) ?? []
        async let m = (try? merchantsClient.loyaltyMembers(mid: uid, token: token)) ?? []
        stats   = await s
        orders  = await o
        members = await m
    }

    private func fmtVND(_ n: UInt64) -> String {
        let s = "\(n)"
        var out = ""; var i = 0
        for c in s.reversed() {
            if i > 0 && i % 3 == 0 { out = "." + out }
            out = String(c) + out; i += 1
        }
        return out
    }
}

struct OrderRow: View {
    let order: OrderInfo
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(order.note?.isEmpty == false ? order.note! : order.id)
                    .font(.subheadline)
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Text(tsLabel(order.createdAt))
                    .font(.caption2)
                    .foregroundStyle(.gray)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(fmtVND(order.amount) + " ₫")
                    .font(.subheadline.bold())
                    .foregroundStyle(.white)
                Text(statusLabel(order.status))
                    .font(.caption2)
                    .foregroundStyle(order.status == "paid" ? .green : .orange)
                if order.status == "paid" {
                    let pts = order.amount / 10_000
                    if pts > 0 {
                        Text("+\(pts) điểm KH")
                            .font(.caption2)
                            .foregroundStyle(Color.yellow)
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private func fmtVND(_ n: UInt64) -> String {
        let s = "\(n)"; var out = ""; var i = 0
        for c in s.reversed() {
            if i > 0 && i % 3 == 0 { out = "." + out }
            out = String(c) + out; i += 1
        }
        return out
    }
    private func statusLabel(_ s: String) -> String {
        switch s { case "paid": return "✓ Đã thanh toán"
                   case "expired": return "Hết hạn"
                   default: return "⏳ Chờ" }
    }
    private func tsLabel(_ ms: Int64) -> String {
        let d = Date(timeIntervalSince1970: Double(ms) / 1000)
        let f = DateFormatter(); f.dateStyle = .short; f.timeStyle = .short
        return f.string(from: d)
    }
}

// ── Create Order Sheet ────────────────────────────────────────────────────────

struct CreateOrderSheet: View {
    let merchantsClient: MerchantsClient
    let savingClient: SavingClient
    let mid: UInt32
    let token: String

    @State private var amountText     = ""
    @State private var note           = ""
    @State private var discountPoints = ""
    @State private var loading        = false
    @State private var error: String?
    @State private var qrPayload: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if let pr = qrPayload {
                    qrView(pr)
                } else {
                    form
                }
            }
            .navigationTitle("Tạo đơn hàng")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Đóng") { dismiss() }.foregroundStyle(.white)
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private var form: some View {
        VStack(spacing: 24) {
            Spacer()
            VStack(alignment: .leading, spacing: 8) {
                Text("Số tiền (₫)")
                    .font(.caption).foregroundStyle(.gray)
                TextField("50000", text: $amountText)
                    .keyboardType(.numberPad)
                    .padding(14)
                    .background(Color.white.opacity(0.08))
                    .cornerRadius(12)
                    .foregroundStyle(.white)
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("Ghi chú")
                    .font(.caption).foregroundStyle(.gray)
                TextField("Cà phê x2", text: $note)
                    .padding(14)
                    .background(Color.white.opacity(0.08))
                    .cornerRadius(12)
                    .foregroundStyle(.white)
            }
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("Điểm thưởng dùng")
                        .font(.caption).foregroundStyle(.gray)
                    Spacer()
                    if let pts = Int64(discountPoints), pts > 0 {
                        Text("- \(pts * 100) ₫")
                            .font(.caption).foregroundStyle(.yellow)
                    }
                }
                TextField("0", text: $discountPoints)
                    .keyboardType(.numberPad)
                    .padding(14)
                    .background(Color.white.opacity(0.08))
                    .cornerRadius(12)
                    .foregroundStyle(.white)
            }
            if let e = error {
                Text(e).font(.caption).foregroundStyle(.red)
            }
            Button {
                Task { await create() }
            } label: {
                Group {
                    if loading { ProgressView().tint(.black) }
                    else { Text("Tạo QR thanh toán").fontWeight(.semibold) }
                }
                .frame(maxWidth: .infinity).padding(16)
                .background(UInt64(amountText) != nil ? Color.white : Color.gray)
                .foregroundStyle(.black).cornerRadius(14)
            }
            .disabled(UInt64(amountText) == nil || loading)
            Spacer()
        }
        .padding(.horizontal, 24)
    }

    private func qrView(_ pr: String) -> some View {
        VStack(spacing: 24) {
            Spacer()
            Text("Quét để thanh toán")
                .font(.headline).foregroundStyle(.white)
            if let img = generateQR(pr) {
                Image(uiImage: img)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 240, height: 240)
                    .padding(16)
                    .background(Color.white)
                    .cornerRadius(16)
            }
            Text(note.isEmpty ? "Đang chờ thanh toán..." : note)
                .font(.subheadline).foregroundStyle(.gray)
            Button("Tạo đơn mới") { qrPayload = nil; amountText = ""; note = "" }
                .foregroundStyle(.white)
            Spacer()
        }
    }

    private func create() async {
        guard let amount = UInt64(amountText) else { return }
        let pts = Int64(discountPoints) ?? 0
        loading = true; error = nil
        defer { loading = false }
        do {
            /* Step 1: merchant-gateway signs the order and returns an orderID */
            let resp = try await merchantsClient.createOrder(
                mid: mid, token: token, amount: amount, note: note, discountPoints: pts)

            /* Step 2: register the intent with Wire server using that orderID */
            let requestID = UInt64(Date().timeIntervalSince1970 * 1000)
            let orderIDNum = UInt64(resp.orderID) ?? requestID
            let intent = try await savingClient.createIntent(
                requestID: requestID, orderID: orderIDNum, amount: amount)

            /* QR for customer to scan and call PAY_INTENT */
            qrPayload = "saving://intent?mid=\(intent.mid)&rid=\(intent.requestID)&amount=\(intent.amount)"
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func generateQR(_ payload: String) -> UIImage? {
        let data = payload.data(using: .utf8)   /* payload is already a full saving:// URL */
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let ci = filter.outputImage else { return nil }
        let scaled = ci.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// MARK: - Loyalty Members Sheet (merchant view)
// ══════════════════════════════════════════════════════════════════════════════

struct LoyaltyMembersSheet: View {
    let members: [LoyaltyMember]
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if members.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "star.slash").font(.system(size: 48)).foregroundStyle(.gray)
                        Text("Chưa có thành viên loyalty").foregroundStyle(.gray)
                    }
                } else {
                    List {
                        ForEach(members, id: \.uid) { m in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("#\(m.uid)")
                                        .font(.subheadline.monospaced()).foregroundStyle(.white)
                                }
                                Spacer()
                                VStack(alignment: .trailing, spacing: 2) {
                                    HStack(spacing: 4) {
                                        Image(systemName: "star.fill").font(.caption).foregroundStyle(.yellow)
                                        Text("\(m.points) điểm").font(.subheadline.bold()).foregroundStyle(.white)
                                    }
                                    Text("= \(m.points * 100) ₫").font(.caption2).foregroundStyle(.gray)
                                }
                            }
                            .listRowBackground(Color.white.opacity(0.05))
                        }
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                }
            }
            .navigationTitle("Thành viên Loyalty")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Đóng") { dismiss() }.foregroundStyle(.white)
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// MARK: - My Loyalty Cards (customer view)
// ══════════════════════════════════════════════════════════════════════════════

struct MyLoyaltySheet: View {
    let merchantsClient: MerchantsClient
    let uid: UInt32
    @State private var entries: [UserLoyaltyEntry] = []
    @State private var loading = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if loading {
                    ProgressView().tint(.white)
                } else if entries.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "star.slash").font(.system(size: 48)).foregroundStyle(.gray)
                        Text("Chưa có điểm thưởng nào").foregroundStyle(.gray)
                        Text("Thanh toán tại merchant để tích điểm").font(.caption).foregroundStyle(.gray)
                    }
                } else {
                    List {
                        ForEach(entries, id: \.mid) { e in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(e.merchantName).font(.subheadline.bold()).foregroundStyle(.white)
                                    Text("#\(e.mid)").font(.caption.monospaced()).foregroundStyle(.gray)
                                }
                                Spacer()
                                VStack(alignment: .trailing, spacing: 2) {
                                    HStack(spacing: 4) {
                                        Image(systemName: "star.fill").font(.caption).foregroundStyle(.yellow)
                                        Text("\(e.points) điểm").font(.subheadline.bold()).foregroundStyle(.white)
                                    }
                                    Text("= \(e.points * 100) ₫").font(.caption2).foregroundStyle(.gray)
                                }
                            }
                            .listRowBackground(Color.white.opacity(0.05))
                        }
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                }
            }
            .navigationTitle("Thẻ tích điểm")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Đóng") { dismiss() }.foregroundStyle(.white)
                }
            }
            .task { await load() }
            .refreshable { await load() }
        }
        .preferredColorScheme(.dark)
    }

    private func load() async {
        loading = true; defer { loading = false }
        entries = (try? await merchantsClient.userLoyalty(uid: uid)) ?? []
    }
}
#endif

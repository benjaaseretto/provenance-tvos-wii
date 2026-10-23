import SwiftUI

@main
struct Acceso: App {
    var body: some Scene {
        WindowGroup { Lanzador() }
    }
}

struct Lanzador: View {
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var phase

    var body: some View {
        Color.black
            .ignoresSafeArea()
            .onAppear(perform: abrirJuego)
            .onChange(of: phase) { _, nueva in
                if nueva == .active { abrirJuego() }
            }
    }

    private func abrirJuego() {
        guard let md5 = Bundle.main.object(forInfoDictionaryKey: "PVGameMD5") as? String,
              let url = URL(string: "provenance://open?md5=\(md5)") else { return }
        openURL(url)
    }
}

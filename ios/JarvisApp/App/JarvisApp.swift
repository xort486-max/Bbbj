import SwiftUI

@main
struct JarvisApp: App {
    @State private var model = MainViewModel()

    var body: some Scene {
        WindowGroup {
            MainView(model: model)
                .preferredColorScheme(.dark)
        }
    }
}

// swift-tools-version: 6.0
// JarvisCore — UI와 무관한 JARVIS 핵심 로직 (상태 머신, 모델, 백엔드 클라이언트).
// SwiftUI/UIKit에 의존하지 않으므로 macOS·Linux에서도 `swift test`로 검증할 수 있다.
import PackageDescription

let package = Package(
    name: "JarvisCore",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "JarvisCore", targets: ["JarvisCore"]),
    ],
    targets: [
        .target(name: "JarvisCore"),
        .testTarget(name: "JarvisCoreTests", dependencies: ["JarvisCore"]),
    ]
)

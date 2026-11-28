import CoreImage.CIFilterBuiltins
import SwiftUI
import VisionKit

/// A QR code of `text`, drawn black on white whatever the theme (a camera reads it best), with a quiet margin.
struct QrCode: View {
    let text: String
    var size: CGFloat = 240

    var body: some View {
        SwiftUI.Group {
            if let image = Self.image(text) {
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
            } else {
                Color.white
            }
        }
        .frame(width: size, height: size)
        .padding(14)
        .background(Color.white, in: RoundedRectangle(cornerRadius: Corner.card))
    }

    private static let context = CIContext()

    static func image(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage, let cg = context.createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// The camera, reading one QR code (VisionKit's scanner, on the phone: nothing leaves it). `onCode` gets the first
/// code read; `onUnavailable` is called when this phone can't scan (a simulator, or the camera refused).
struct QrScanner: View {
    let onCode: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            SwiftUI.Group {
                if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
                    ScannerView { code in
                        dismiss()
                        onCode(code)
                    }
                    .ignoresSafeArea()
                } else {
                    VStack {
                        Spacer()
                        EmptyState(icon: "qrcode.viewfinder", text: L("enroll_scan_failed"))
                        Spacer()
                    }
                    .background(Palette.background.ignoresSafeArea())
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("enroll_cancel")) { dismiss() }
                }
            }
        }
    }
}

private struct ScannerView: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        try? scanner.startScanning()
        return scanner
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {}

    static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: Coordinator) {
        controller.stopScanning()
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        private let onCode: (String) -> Void
        private var done = false

        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !done else { return }
            for item in addedItems {
                if case .barcode(let code) = item, let text = code.payloadStringValue {
                    done = true
                    dataScanner.stopScanning()
                    onCode(text)
                    return
                }
            }
        }
    }
}

import CoreGraphics
import Foundation
import ImageIO
import Photos
import UniformTypeIdentifiers

enum CaptureError: LocalizedError {
    case encodeFailed
    case photosDenied

    var errorDescription: String? {
        switch self {
        case .encodeFailed: return "PNG encode failed"
        case .photosDenied: return "Photos access is off. Allow adding photos in Settings."
        }
    }
}

enum CaptureSaver {
    /// Lossless PNG with the filter description in its metadata.
    static func pngData(_ image: CGImage, description: String) throws -> Data {
        let data = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(data as CFMutableData, UTType.png.identifier as CFString, 1, nil) else {
            throw CaptureError.encodeFailed
        }
        let properties: [CFString: Any] = [
            kCGImagePropertyPNGDictionary: [kCGImagePropertyPNGDescription: description],
        ]
        CGImageDestinationAddImage(dest, image, properties as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { throw CaptureError.encodeFailed }
        return data as Data
    }

    /// Adds the image to the photo library. Needs add-only access, not read access.
    static func save(_ image: CGImage, description: String) async throws {
        let png = try pngData(image, description: description)
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { throw CaptureError.photosDenied }

        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd_HHmmss_SSS"
        let name = "FFC_\(formatter.string(from: Date())).png"

        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCreationRequest.forAsset()
            let options = PHAssetResourceCreationOptions()
            options.originalFilename = name
            request.addResource(with: .photo, data: png, options: options)
        }
    }
}

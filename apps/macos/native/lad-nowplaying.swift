// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import Darwin
import Foundation
import MediaPlayer

final class NowPlayingBridge {
    private let center = MPNowPlayingInfoCenter.default()
    private let commands = MPRemoteCommandCenter.shared()
    private var artworkCache: [String: NSImage] = [:]

    init() {
        commands.playCommand.isEnabled = true
        commands.pauseCommand.isEnabled = true
        commands.togglePlayPauseCommand.isEnabled = true
        commands.nextTrackCommand.isEnabled = true
        commands.previousTrackCommand.isEnabled = true
        commands.changePlaybackPositionCommand.isEnabled = true

        commands.playCommand.addTarget { [weak self] _ in self?.emit(action: "play"); return .success }
        commands.pauseCommand.addTarget { [weak self] _ in self?.emit(action: "pause"); return .success }
        commands.togglePlayPauseCommand.addTarget { [weak self] _ in self?.emit(action: "toggle"); return .success }
        commands.nextTrackCommand.addTarget { [weak self] _ in self?.emit(action: "next"); return .success }
        commands.previousTrackCommand.addTarget { [weak self] _ in self?.emit(action: "previous"); return .success }
        commands.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.emit(action: "seek", value: event.positionTime)
            return .success
        }
    }

    func set(_ payload: [String: Any]) {
        guard let title = payload["title"] as? String, !title.isEmpty else {
            clear()
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: title,
            MPMediaItemPropertyArtist: payload["artist"] as? String ?? "",
            MPMediaItemPropertyAlbumTitle: payload["album"] as? String ?? "",
            MPMediaItemPropertyPlaybackDuration: payload["duration"] as? Double ?? 0,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: payload["elapsed"] as? Double ?? 0,
            MPNowPlayingInfoPropertyPlaybackRate: payload["playing"] as? Bool == true ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]

        if let artworkURL = payload["artwork"] as? String, let image = image(for: artworkURL) {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
        }

        center.nowPlayingInfo = info
        center.playbackState = (payload["playing"] as? Bool == true) ? .playing : .paused
    }

    func clear() {
        center.nowPlayingInfo = nil
        center.playbackState = .stopped
    }

    private func image(for value: String) -> NSImage? {
        if let cached = artworkCache[value] { return cached }
        let url: URL
        if value.hasPrefix("/") { url = URL(fileURLWithPath: value) }
        else if let parsed = URL(string: value) { url = parsed }
        else { return nil }
        guard let data = try? Data(contentsOf: url), let image = NSImage(data: data) else { return nil }
        artworkCache[value] = image
        return image
    }

    private func emit(action: String, value: Double? = nil) {
        var payload: [String: Any] = ["event": "command", "action": action]
        if let value { payload["value"] = value }
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let line = String(data: data, encoding: .utf8) else { return }
        print(line)
        fflush(stdout)
    }
}

let bridge = NowPlayingBridge()
DispatchQueue.global(qos: .userInitiated).async {
    while let line = readLine(),
          let data = line.data(using: .utf8),
          let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let method = payload["method"] as? String {
        DispatchQueue.main.async {
            switch method {
            case "set":
                bridge.set(payload["params"] as? [String: Any] ?? [:])
            case "clear":
                bridge.clear()
            default:
                break
            }
        }
    }
    DispatchQueue.main.async {
        bridge.clear()
        exit(0)
    }
}
RunLoop.main.run()

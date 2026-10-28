// SPDX-License-Identifier: AGPL-3.0-only
export interface Track {
  id: string;
  title: string;
  artist: string;
  artistId?: string;
  artists?: { id: string; name: string }[];
  albumId?: string;
  album?: string;
  duration: number;
  cover?: string | null;
  available?: boolean;
  liked?: boolean;
  explicit?: boolean;
  art?: number;
}
export interface Card {
  id: string;
  type: "album" | "playlist" | "artist";
  title: string;
  subtitle?: string;
  owner?: string;
  ownerLogin?: string;
  cover?: string | null;
  art?: number;
}
export interface Playback {
  track: Track | null;
  playing: boolean;
  position: number;
  duration: number;
  positionAt: number;
  volume: number;
  transitioning: boolean;
}
export interface QueueState {
  tracks: Track[];
  index: number;
  source: string;
  owned: boolean;
  repeat: "off" | "one" | "all";
  shuffled: boolean;
}
export interface Device {
  id: string;
  name: string;
  host?: string;
  platform?: string;
  port?: number;
}
export interface State {
  blockExplicit: boolean;
  syncVolume?: boolean;
  profile: { name: string; uid: string; plus: boolean } | null;
  demo: boolean;
  output: "local" | "station";
  devices: Device[];
  deviceId: string | null;
  connection: string;
  stationMessage?: string | null;
  playback: Playback;
  queue: QueueState;
  hasStationSession: boolean;
  hasMusicSession: boolean;
  feedbackError?: string | null;
}
export interface Lyrics {
  synced: boolean;
  lines: { time: number | null; text: string }[];
  writers: string[];
  provider?: string | null;
}
export interface SearchResult {
  tracks: Track[];
  albums: Card[];
  playlists: Card[];
  artists: Card[];
  page: number;
  total: number;
}
export interface Collection {
  title: string;
  tracks: Track[];
  cover?: string;
  page?: number;
}
export interface LadEvent {
  event: string;
  data: any;
}
export interface Bridge {
  setScale?(scale: number): Promise<number>;
  rpc<T = any>(method: string, params?: Record<string, unknown>): Promise<T>;
  login(): Promise<any>;
  open(url: string): Promise<void>;
  copy(text: string): Promise<void>;
  platform: string;
  onEvent(fn: (event: LadEvent) => void): () => void;
}
declare global {
  interface Window {
    lad?: Bridge;
  }
}
export const initialState: State = {
  blockExplicit: false,
  profile: null,
  demo: false,
  output: "local",
  devices: [],
  deviceId: null,
  connection: "disconnected",
  hasStationSession: false,
  hasMusicSession: false,
  playback: {
    track: null,
    playing: false,
    position: 0,
    duration: 0,
    positionAt: Date.now(),
    volume: 0.4,
    transitioning: false,
  },
  queue: {
    tracks: [],
    index: -1,
    source: "Очередь Лад",
    owned: false,
    repeat: "off",
    shuffled: false,
  },
};

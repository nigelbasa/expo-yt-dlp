import { NativeModule, requireOptionalNativeModule } from 'expo';

import {
  DownloadItem,
  ExecResult,
  ExpoYtDlpModuleEvents,
  RuntimeInfo,
  UpdateCheck,
  UpdateMode,
  UpdateResult,
  UpdateState,
} from './ExpoYtDlp.types';

/** Options as the Kotlin records take them (the public API translates to these). */
export type NativeInfoOptions = {
  flatPlaylist?: boolean;
  noPlaylist?: boolean;
  cookiesFile?: string;
  extraArgs?: string[];
  networkRetries?: number;
};

export type NativeDownloadOptions = {
  outputDir?: string;
  format?: string;
  maxHeight?: number;
  audioOnly?: boolean;
  noPlaylist?: boolean;
  cookiesFile?: string;
  extraArgs?: string[];
  networkRetries?: number;
  simulate?: boolean;
  skipMedia?: boolean;
};

export type NativeUpdateConfig = {
  mode: UpdateMode;
  checkIntervalHours: number;
  feedUrl?: string;
  feedPublicKey?: string;
};

export type NativeDownloadResult = { id: string; files: string[]; items: DownloadItem[] };

declare class ExpoYtDlpModule extends NativeModule<ExpoYtDlpModuleEvents> {
  isSupported(): boolean;
  prepare(): Promise<RuntimeInfo>;
  getInfo(id: string, url: string, options: NativeInfoOptions): Promise<string>;
  download(id: string, url: string, options: NativeDownloadOptions): Promise<NativeDownloadResult>;
  exec(id: string, args: string[]): Promise<ExecResult>;
  cancel(id: string): boolean;
  setUpdates(config: NativeUpdateConfig): UpdateState;
  getUpdateState(): UpdateState;
  checkForUpdate(): Promise<UpdateCheck>;
  updateYtDlp(): Promise<UpdateResult>;
  resetToBundled(): Promise<UpdateState>;
}

// Android only for now; null on iOS and web.
export default requireOptionalNativeModule<ExpoYtDlpModule>('ExpoYtDlp');

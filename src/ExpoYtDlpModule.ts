import { NativeModule, requireOptionalNativeModule } from 'expo';

import {
  DownloadOptions,
  DownloadResult,
  ExecResult,
  ExpoYtDlpModuleEvents,
  InfoOptions,
  RuntimeInfo,
} from './ExpoYtDlp.types';

export type NativeInfoOptions = Omit<InfoOptions, 'signal'>;
export type NativeDownloadOptions = Omit<DownloadOptions, 'onProgress'>;

declare class ExpoYtDlpModule extends NativeModule<ExpoYtDlpModuleEvents> {
  isSupported(): boolean;
  prepare(): Promise<RuntimeInfo>;
  getInfo(id: string, url: string, options: NativeInfoOptions): Promise<string>;
  download(id: string, url: string, options: NativeDownloadOptions): Promise<DownloadResult>;
  exec(id: string, args: string[]): Promise<ExecResult>;
  cancel(id: string): boolean;
}

// Android only for now; null on iOS and web.
export default requireOptionalNativeModule<ExpoYtDlpModule>('ExpoYtDlp');

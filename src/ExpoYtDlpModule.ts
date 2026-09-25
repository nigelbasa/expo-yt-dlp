import { NativeModule, requireNativeModule } from 'expo';

import { ExpoYtDlpModuleEvents } from './ExpoYtDlp.types';

declare class ExpoYtDlpModule extends NativeModule<ExpoYtDlpModuleEvents> {
  setValueAsync(value: string): Promise<void>;
}

export default requireNativeModule<ExpoYtDlpModule>('ExpoYtDlp');

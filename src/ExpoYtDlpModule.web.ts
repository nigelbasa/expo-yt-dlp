import { registerWebModule, NativeModule } from 'expo';

import { ExpoYtDlpModuleEvents } from './ExpoYtDlp.types';

// ExpoYtDlpModule is not available on the web platform.
class ExpoYtDlpModule extends NativeModule<ExpoYtDlpModuleEvents> {}

export default registerWebModule(ExpoYtDlpModule, 'ExpoYtDlpModule');

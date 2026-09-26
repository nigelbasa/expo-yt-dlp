const { createRunOncePlugin, withGradleProperties } = require('expo/config-plugins');

const pkg = require('./package.json');

function setGradleProperty(properties, key, value) {
  const existing = properties.find((item) => item.type === 'property' && item.key === key);
  if (existing) {
    existing.value = value;
  } else {
    properties.push({ type: 'property', key, value });
  }
}

/**
 * expo-yt-dlp runs its bundled binaries (python launcher, QuickJS, ffmpeg) from
 * nativeLibraryDir. Android only extracts native libraries there with legacy packaging;
 * otherwise they stay inside the APK and cannot be executed.
 *
 * Options:
 *   abiFilters: e.g. ["arm64-v8a", "x86_64"] - only build these ABIs. The runtime exists for
 *   arm64-v8a and x86_64 only, so this also drops dead weight from other native libraries.
 */
const withYtDlp = (config, { abiFilters } = {}) =>
  withGradleProperties(config, (config) => {
    setGradleProperty(config.modResults, 'expo.useLegacyPackaging', 'true');
    if (abiFilters?.length) {
      setGradleProperty(config.modResults, 'reactNativeArchitectures', abiFilters.join(','));
    }
    return config;
  });

module.exports = createRunOncePlugin(withYtDlp, pkg.name, pkg.version);

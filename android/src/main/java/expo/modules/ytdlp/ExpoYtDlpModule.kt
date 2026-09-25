package expo.modules.ytdlp

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class ExpoYtDlpModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("ExpoYtDlp")

    Events("onChange")

    AsyncFunction("setValueAsync") { value: String ->
      sendEvent("onChange", mapOf(
        "value" to value
      ))
    }
  }
}

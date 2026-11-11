// Minimal stand-ins for the Expo classes Records.kt uses, so the update logic can be compiled
// and tested with plain kotlinc (see scripts/test_update_logic.py). Never part of the app.
package expo.modules.kotlin.exception

open class CodedException(val code: String, message: String?, cause: Throwable?) : Exception(message, cause)

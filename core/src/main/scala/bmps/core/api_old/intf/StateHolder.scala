
/* DISABLED api_old
package bmps.core.api.intf

import java.util.concurrent.atomic.AtomicReference
import bmps.core.models.SystemState

/** Minimal trait to provide atomic snapshot access to SystemState.
  * Implementations may wrap the existing AtomicReference used in the monolith.
  */
trait StateHolder {
  def get(): SystemState
  def set(s: SystemState): Unit
  def getAtomicRef(): AtomicReference[SystemState]
}

*/



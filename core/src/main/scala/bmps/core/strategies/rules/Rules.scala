package bmps.core.strategies.rules

sealed trait ExitAction
object ExitAction {
    case object NoAction extends ExitAction //don't do anything
    case object ExitNow extends ExitAction //sell at the current price
    case object Cancel extends ExitAction  //cancel if order is not yet filled
    case object ResetBrackets extends ExitAction //reset bracket exit points
}

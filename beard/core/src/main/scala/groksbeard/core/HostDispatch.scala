package groksbeard.core

object HostDispatch:
  def apply(runtime: ChatRuntime, msg: WebviewMsg, extra: HostMsg => Unit): Unit =
    msg match
      case WebviewMsg.Ready            => runtime.ready()
      case WebviewMsg.Send(text)       => runtime.send(text)
      case WebviewMsg.Queue(text)      => runtime.queue(text)
      case WebviewMsg.Cancel           => runtime.cancel()
      case WebviewMsg.SetMode(id)      => runtime.setMode(id)
      case WebviewMsg.MentionQuery(q)  => extra(HostMsg.MentionResults(q, Nil))
      case WebviewMsg.OpenSettings     => extra(HostMsg.settings(SettingsState.defaults))
      case WebviewMsg.OpenDiff(id)     => runtime.openDiff(id)
      case WebviewMsg.OpenChanges      => runtime.openChanges()
      case WebviewMsg.KeepChange(path) => runtime.keep(path)
      case WebviewMsg.UndoChange(path) => runtime.undo(path)
      case WebviewMsg.CloseDiff        => runtime.closeDiff()
      case _                           => ()
end HostDispatch

package groksbeard.ui

import ascent.*
import groksbeard.facade.VsCodeApi
import zio.*

object Main extends ZIOAppDefault:

  def run =
    val livePreview = VsCodeApi.current.isEmpty && !PreviewBridge.hasSceneQuery
    val bridge      = VsCodeApi.current match
      case Some(api) => VsCodeBridge(api)
      case None      => if livePreview then LivePreviewBridge() else PreviewBridge()
    val logoAttr = ascent.dom.document.body.getAttribute("data-logo")
    val logo     = Option(logoAttr).filter(s => s != null && s.nonEmpty)
    val scene    =
      if VsCodeApi.current.isEmpty && !livePreview then Scene.from(PreviewBridge.sceneFromLocation) else Scene.Empty
    for
      chat <- ChatApp.component(bridge, logo, scene)
      root = ascent.dom.document.getElementById("root")
      _ <-
        if root == null then ZIO.fail(new RuntimeException("chat root missing"))
        else AscentApp.mount(chat, root)
      _ <- ZIO.succeed {
        if VsCodeApi.current.isEmpty then DevReload.install()
      }
      _ <- ZIO.never
    yield ()
    end for
  end run
end Main

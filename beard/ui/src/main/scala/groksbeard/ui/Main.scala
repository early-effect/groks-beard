package groksbeard.ui

import ascent.*
import groksbeard.facade.VsCodeApi
import org.scalajs.dom as jsdom
import zio.*

object Main extends ZIOAppDefault:

  def run =
    for
      _    <- whenDomReady
      hist <- if VsCodeApi.current.isDefined then History.memory() else History.browser
      loc  <- hist.location.get
      livePreview = VsCodeApi.current.isEmpty && BeardPath.sceneName(loc).isEmpty
      bridge      = VsCodeApi.current match
        case Some(api) => VsCodeBridge(api)
        case None      => if livePreview then LivePreviewBridge() else PreviewBridge()
      logo  = readLogo
      scene = BeardPath.sceneName(loc).map(Scene.from).getOrElse(Scene.Empty)
      chat <- ChatApp.component(bridge, logo, hist, scene)
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

  private def readLogo: Option[String] =
    def attr(el: jsdom.Element | Null): Option[String] =
      Option(el).flatMap(e => Option(e.getAttribute("data-logo"))).filter(s => s != null && s.nonEmpty)
    attr(jsdom.document.documentElement).orElse(attr(jsdom.document.body)).orElse(Some("/logo.png"))

  private def whenDomReady: UIO[Unit] =
    if jsdom.document.readyState != "loading" then ZIO.unit
    else
      ZIO.async[Any, Nothing, Unit] { cb =>
        jsdom.document.addEventListener("DOMContentLoaded", (_: jsdom.Event) => cb(ZIO.unit))
      }
end Main

package groksbeard.ui

import ascent.*
import groksbeard.core.WebviewMsg
import groksbeard.facade.VsCodeApi
import org.scalajs.dom as jsdom
import zio.*
import zio.json.*

import scala.scalajs.js

object Main extends ZIOAppDefault:

  def run =
    boot.tapError { e =>
      ZIO.succeed {
        val msg = Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.toString)
        js.Dynamic.global.console.error("Grok's Beard UI failed:", msg)
        VsCodeApi.current.foreach { api =>
          val payload: WebviewMsg = WebviewMsg.Log(msg)
          api.postMessage(js.JSON.parse(payload.toJson))
        }
      }
    }

  private def boot =
    val api = VsCodeApi.current
    for
      _    <- whenDomReady
      hist <- if api.isDefined then History.memory() else History.browser
      loc  <- hist.location.get
      livePreview = api.isEmpty && BeardPath.sceneName(loc).isEmpty
      bridge      = api match
        case Some(vs) => VsCodeBridge(vs)
        case None     => if livePreview then LivePreviewBridge() else PreviewBridge()
      logo  = readLogo
      scene = BeardPath.sceneName(loc).map(Scene.from).getOrElse(Scene.Empty)
      chat <- ChatApp.component(bridge, logo, hist, scene)
      root = ascent.dom.document.getElementById("root")
      _ <-
        if root == null then ZIO.fail(new RuntimeException("chat root missing"))
        else AscentApp.mount(chat, root)
      _ <- ZIO.succeed {
        if api.isEmpty then DevReload.install()
      }
      _ <- ZIO.never
    yield ()
    end for
  end boot

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

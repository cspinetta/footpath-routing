package api

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import conf.ApiEnvConfig
import service.DirectionService

import scala.io.StdIn

object WebServer extends App with DirectionService with ApiEnvConfig {
  override implicit val system = ActorSystem()
  override implicit val executor = system.dispatcher
  override implicit val materializer = ActorMaterializer()

  override val logger = Logging(system, getClass)

  private val interface: String = configuration.HTTP.interface
  private val port: Int = configuration.HTTP.port

  val bindingFuture = Http().bindAndHandle(routes, interface, port)

  bindingFuture foreach { binder ⇒

    println(s"Server online at http://$interface:$port/\nPress RETURN to stop...")

    //    StdIn.readLine() // let it run until user presses return
    //
    //    binder.unbind() // trigger unbinding from the port
    //      .onComplete(_ ⇒ system.terminate()) // and shutdown when done
  }

}

package searching.walk

import base.LazyLoggerSupport
import base.conf.ApiEnvConfig
import cats.data.{ Xor, XorT }
import mapdomain.graph._
import mapdomain.sidewalk._
import mapdomain.utils.GraphUtils
import model._
import pathgenerator.core.AStar
import pathgenerator.graph.{ GeoGCost, GeoHeuristic }
import provider.{ GraphSupport, StreetEdgeSupport, StreetInfoSupport }
import searching.SearchRoutingErrors._
import searching.{ PedestrianIncident, RampIncidentType, SidewalkIncidentType }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Try }

trait WalkRouteSearcherSupport {
  protected val walkRouteSearcher = WalkRouteSearcher
  protected val walkRadius: Double = WalkRouteSearcher.walkRadius
}

object WalkRouteSearcher extends WalkRouteSearcher {
  val walkRadius: Double = configuration.Routing.maximumWalkRadius
}

sealed trait WalkRouteSearcher extends GraphSupport with LazyLoggerSupport with ApiEnvConfig with StreetEdgeSupport with StreetInfoSupport {

  def search(coordinateFrom: Coordinate, coordinateTo: Coordinate, heuristicType: HeuristicType = AccessibilityHeuristicType)(implicit ec: ExecutionContext): XorT[Future, SearchRoutingError, Path] = XorT {
    Future(searchPathOnGraph(graphs.sidewalk, coordinateFrom, coordinateTo, heuristicType).get)
      .map(edges ⇒ Xor.Right(createWalkPath(edges))) recover {
        case exc: Throwable ⇒
          logger.error(s"Failed trying to find a path between $coordinateFrom and $coordinateTo walking.", exc)
          Xor.Left(NoPath)
      }
  }

  protected def searchPathOnGraph(graphContainer: SidewalkGraphContainer, coordinateFrom: Coordinate,
    coordinateTo: Coordinate, heuristicType: HeuristicType): Try[List[EdgeReference[PedestrianEdge, SidewalkVertex]]] = {
    (graphContainer.findNearest(coordinateFrom), graphContainer.findNearest(coordinateTo)) match {
      case (Some(fromVertex), Some(toVertex)) ⇒
        logger.info(s"Vertex From: ${fromVertex.id}. Vertex To: ${toVertex.id}")
        val aStartFactory = heuristicType match {
          case AccessibilityHeuristicType ⇒
            AStar[PedestrianEdge, SidewalkVertex, GeoHeuristic[PedestrianEdge, SidewalkVertex], WalkGCost.type](GeoHeuristic[PedestrianEdge, SidewalkVertex](fromVertex), WalkGCost) _
          case GeoHeuristicType ⇒
            AStar[PedestrianEdge, SidewalkVertex, GeoHeuristic[PedestrianEdge, SidewalkVertex], GeoGCost[PedestrianEdge, SidewalkVertex]](GeoHeuristic[PedestrianEdge, SidewalkVertex](fromVertex), GeoGCost()) _
        }
        aStartFactory(graphContainer, fromVertex, Seq(toVertex))
          .search
      case otherResult ⇒ Failure(new RuntimeException(s"It could not get a near vertex. $otherResult"))
    }
  }

  protected def createWalkPath(edges: List[EdgeReference[PedestrianEdge, SidewalkVertex]]): Path = {

    val incidents: List[PedestrianIncident] = extractIncidents(edges.map(_.edge))

    val vertices: List[SidewalkVertex] = GraphUtils.edgeReferencesToIds(edges) map (vertexId ⇒ graphs.sidewalk.findVertex(vertexId) match {
      case Some(vertex) ⇒ vertex
      case None         ⇒ throw new RuntimeException(s"Vertex not found $vertexId while trying to create the path from the edge list.")
    })

    val path: List[Coordinate] = vertices.map(_.coordinate)

    vertices match {
      case firstVertex :: secondVertex :: xs ⇒
        val from = getAddress(firstVertex, secondVertex) // FIXME calculate altitude
        val beforeLast :: last :: _ = vertices.takeRight(2)
        val to = getAddress(beforeLast, last)
        Path(path, PathDescription(WalkPath, from, to), incidents)
      case _ ⇒
        Path(path, PathDescription(WalkPath, "-", "-"), incidents)
    }
  }

  protected def extractIncidents(edges: List[PedestrianEdge]): List[PedestrianIncident] = edges flatMap {
    case SidewalkEdge(vertexStartId, vertexEndId, _, _, _, id, false) ⇒
      List(PedestrianIncident(SidewalkIncidentType,
        from = Some(graphs.sidewalk.findVertex(vertexStartId).get.coordinate),
        to = Some(graphs.sidewalk.findVertex(vertexEndId).get.coordinate)))
    case StreetCrossingEdge(vertexStartId, vertexEndId, _, _, rampStartIdOpt, rampEndIdOpt) ⇒
      def aggregateIncident(ramp: Option[Long], vertexId: Long): List[PedestrianIncident] = ramp
        .map(_ ⇒ List.empty)
        .getOrElse(List(PedestrianIncident(
          RampIncidentType,
          position = Some(graphs.sidewalk.findVertex(vertexId).get.coordinate))))
      aggregateIncident(rampStartIdOpt, vertexStartId) ::: aggregateIncident(rampEndIdOpt, vertexEndId)
    case _ ⇒ List.empty
  }

  private def getAddress(vertexFrom: SidewalkVertex, vertexTo: SidewalkVertex): String = {
    vertexFrom.getSidewalkEdgeFor(vertexTo.id)
      .map(_.streetEdgeBelongToId.get)
      .flatMap(streetEdgeId ⇒
        streetInfoProvider.findByStreetEdgeId(streetEdgeId).address)
      .getOrElse {
        logger.warn(s"Street Info without address [Vertex From = ${vertexFrom.id}, Vertex To = ${vertexTo.id}]")
        "-"
      }
  }
}
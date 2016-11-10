package searching

import base.LazyLoggerSupport
import cats.data.{ Xor, XorT }
import mapdomain.graph.Coordinate
import mapdomain.publictransport.Stop
import model.{ BusPath, Path, PathDescription }
import provider.PublicTransportProviderSupport
import searching.SearchRoutingErrors.{ NoPathBetweenStops, SearchRoutingError }
import utils.JsonUtils

import scala.concurrent.{ ExecutionContext, Future }

private[searching] object PathBuilders {

  val costs = Costs

  object Costs {
    val eachStop: Int = 1
    val distanceByKm: Int = 10
    val combination: Int = 100
  }

  trait PathBuilder {
    def build(implicit ec: ExecutionContext): XorT[Future, SearchRoutingError, Path]
    def cost: Double
  }

  case class TransportPathBuilder(travelInfoId: Long, stopFrom: Stop, stopTo: Stop) extends PathBuilder with LazyLoggerSupport with PublicTransportProviderSupport {

    def build(implicit ec: ExecutionContext): XorT[Future, SearchRoutingError, Path] = XorT {
      Future[Xor[SearchRoutingError, Path]] {
        //      val travelInfo = publicTransportProvider.findTravelInfo(stopFrom.travelInfoId)
        val coordinates = publicTransportProvider.getPathBetweenStops(stopFrom, stopTo)
        val travelInfo = publicTransportProvider.findTravelInfo(stopFrom.travelInfoId)
        val transportDescription = s"${travelInfo.`type`} - Line ${travelInfo.name} - Branch ${travelInfo.branch} - ${travelInfo.sentido}"
        // FIXME add more info on PathDescription
        Xor.Right(Path(coordinates, PathDescription(BusPath, s"$transportDescription - From", s"$transportDescription - To")))
      } recover {
        case exc: Throwable ⇒
          logger.error(s"An error occur trying to build the path between stops. $stopFrom, $stopTo", exc)
          Xor.Left(NoPathBetweenStops)
      }
    }

    lazy val cost: Double = (stopTo.sequence - stopFrom.sequence) * costs.eachStop + costs.combination
  }

  case class WalkPathBuilder(from: Coordinate, to: Coordinate) extends PathBuilder with WalkRouteSearcherSupport {
    def build(implicit ec: ExecutionContext): XorT[Future, SearchRoutingError, Path] = {
      this.walkRouteSearcher.search(from, to)
    }

    lazy val cost: Double = from.distanceTo(to) * costs.distanceByKm
  }

  case class WalkPathCombinationBuilder(fromStopId: Long, toTravelInfoId: Long) extends PathBuilder with LazyLoggerSupport with PublicTransportProviderSupport {

    lazy val combination = publicTransportProvider.getCombinationByStopAndTravelInfo(fromStopId, toTravelInfoId)

    def build(implicit ec: ExecutionContext): XorT[Future, SearchRoutingError, Path] = XorT {
      Future[Xor[SearchRoutingError, Path]] {
        Xor.Right(JsonUtils.fromJson(combination.walkPath.get))
      } recover {
        case exc: Throwable ⇒
          logger.error(s"An error occur trying to build the path for combination. $fromStopId, $toTravelInfoId", exc)
          Xor.Left(NoPathBetweenStops)
      }
    }

    lazy val cost: Double = combination.cost

  }

}

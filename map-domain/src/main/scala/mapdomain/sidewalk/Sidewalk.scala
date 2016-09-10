package mapdomain.sidewalk

import base.{ FailureReporterSupport, LazyLoggerSupport }
import mapdomain.graph._
import mapdomain.math.Line
import scalikejdbc._

class PedestrianEdge(override val vertexStartId: Long, override val vertexEndId: Long, key: String, override val distance: Double = 1) extends GeoEdge(vertexStartId, vertexEndId, distance) {
  def from(implicit graphContainer: GraphContainer[SidewalkVertex]): Option[SidewalkVertex] = graphContainer.findVertex(vertexStartId)
  def to(implicit graphContainer: GraphContainer[SidewalkVertex]): Option[SidewalkVertex] = graphContainer.findVertex(vertexEndId)
}

trait Side
case object NorthSide extends Side
case object SouthSide extends Side

case class SidewalkEdge(override val vertexStartId: Long, override val vertexEndId: Long, keyValue: String,
  side: Side, id: Long, streetEdgeBelongToId: Long) extends PedestrianEdge(vertexStartId, vertexEndId, keyValue)

object SidewalkEdge extends FailureReporterSupport with LazyLoggerSupport with SQLSyntaxSupport[SidewalkEdge] {

  override val tableName = "sidewalk_edge"

  override val useSnakeCaseColumnName = false

  override val columns = Seq("id", "vertexStartId", "vertexEndId", "keyValue", "side", "streetEdgeBelongToId")

  def sideByEdges(streetLine: Line, sidewalkLine: Line): Side = withFailureLogging({
    if (Line.compareParallelsByAltitude(streetLine, sidewalkLine) == 1) SouthSide
    else NorthSide
  }, (exc: Throwable) ⇒ logger.error(s"Failed trying to calculate the side of a sidewalk from an edge.", exc))

  def generateKey[E <: GeoEdge](edgeBelongTo: E, side: Side): String = {
    val vertexStartId: Long = edgeBelongTo.vertexStartId
    val vertexEndId: Long = edgeBelongTo.vertexEndId
    val idPart = if (vertexStartId > vertexEndId) s"$vertexEndId-$vertexStartId" else s"$vertexStartId-$vertexEndId"
    s"$idPart-$side"
  }

}

case class StreetCrossingEdge(override val vertexStartId: Long, override val vertexEndId: Long, keyValue: String, val id: Option[Long] = None) extends PedestrianEdge(vertexStartId, vertexEndId, keyValue)

object StreetCrossingEdge extends SQLSyntaxSupport[StreetCrossingEdge] {

  override val tableName = "street_crossing_edge"

  override val useSnakeCaseColumnName = false

}

case class SidewalkVertex(override val id: Long, override val coordinate: Coordinate, sidewalkEdges: List[SidewalkEdge],
    streetCrossingEdges: List[StreetCrossingEdge], streetVertexBelongToId: Long) extends GeoVertex(id, sidewalkEdges ++ streetCrossingEdges, coordinate) {

  lazy val neighbourIds: List[Long] = edges.map(edge ⇒ if (edge.vertexStartId == id) edge.vertexEndId else edge.vertexStartId)

  override def getEdgesFor(vertexId: Long): Option[Edge] = edges.find(edge ⇒ edge.vertexEndId == vertexId || edge.vertexStartId == vertexId)

  override def neighbours[V <: Vertex](graph: GraphContainer[V]): List[V] = neighbourIds.flatMap(id ⇒ graph.findVertex(id) toList)
}

object SidewalkVertex extends SQLSyntaxSupport[SidewalkVertex] {

  override val tableName = "sidewalk_vertex"

  override val useSnakeCaseColumnName = false

}

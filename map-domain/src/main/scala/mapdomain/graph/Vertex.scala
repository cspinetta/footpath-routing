package mapdomain.graph

import scala.math._

trait Vertex {
  val id: Long
  val edges: List[Edge]

  def getEdgesFor(vertexId: Long): Option[Edge] = edges.find(_.vertexEndId == vertexId)
  def distanceToNeighbour(vertexId: Long): Double
}

case class GraphVertex(id: Long, edges: List[Edge]) extends Vertex {
  def distanceToNeighbour(vertexId: Long): Double = this.getEdgesFor(vertexId).map(_.distance).getOrElse(0)
}

object GraphVertex {
  def createWithEdges(id: Long, edges: List[Int]): GraphVertex = GraphVertex(id, edges.map(GraphEdge(id, _)))
  def removeEdge(source: GraphVertex, to: Long): GraphVertex = source.copy(edges = source.edges.filterNot(_.vertexEndId == to))
}

class GeoVertex(override val id: Long, override val edges: List[GeoEdge], val coordinate: Coordinate) extends Vertex {
  //  def removeEdgeAt(to: Long): GeoVertex = this.copy(edges = edges.filterNot(_.vertexEndId == to))
  def distanceToNeighbour(vertexId: Long): Double = this.getEdgesFor(vertexId).map(_.distance).getOrElse(0) // TODO report access by getOrElse
  def distanceTo(vertex: GeoVertex): Double = this.coordinate.distanceTo(vertex.coordinate)
  //  def removeEdge(source: GeoVertex, to: Long): GeoVertex = source.copy(edges = source.edges.filterNot(_.vertexEndId == to))

  override def toString: String = s"GeoVertex(id: $id, edges: $edges, coordinate: $coordinate)"
}
object GeoVertex {
  def createWithEdges(id: Long, edges: List[(Long, Coordinate)], coordinate: Coordinate): GeoVertex = {
    val edge: (Long, Double) ⇒ GeoEdge = GeoEdge(id) _

    new GeoVertex(id, edges.map {
      case (neighbourId, neighbourCoordinate) ⇒
        edge(neighbourId, neighbourCoordinate.distanceTo(coordinate))
    }, coordinate)
  }
}

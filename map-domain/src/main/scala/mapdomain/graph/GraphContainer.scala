package mapdomain.graph

import mapdomain.utils.GraphUtils
import Stream.cons

trait GraphContainer[V <: Vertex] {
  /**
   * Find vertex by ID
   * @param id: Long
   * @return
   */
  def findVertex(id: Long): Option[V]

  def neighbours(vertex: V): Seq[V]
}

trait LazyGraphContainer[V <: Vertex] extends GraphContainer[V]

class EagerGraphContainer[V <: Vertex, G <: GraphContainer[V]](val vertices: List[V], constructor: List[V] ⇒ G) extends GraphContainer[V] { self: G ⇒

  //  type Self <: GraphContainer[V]
  //  type Constructor = (List[V]) ⇒ G
  //  val constructor: Constructor

  /**
   * Find vertex by ID
   *
   * @param id : Long
   * @return an option of vertex
   */
  def findVertex(id: Long): Option[V] = vertices.find(_.id == id)

  def neighbours(vertex: V): Seq[V] = vertex.edges.flatMap(edge ⇒ findVertex(edge.vertexEndId) toSeq)

  /**
   * Create a new GraphContainer with maximal connected subgraph that this graph contains
   * @return The connected graph
   */
  def purge: G = GraphUtils.getConnectedComponent(this, constructor)

}

object EagerGraphContainer {

  def apply[V <: Vertex](vertices: List[V]): EagerGraphContainer[V]
}

trait GeoGraphContainer[V <: GeoVertex] extends GraphContainer[V] {
  def findNearest(coordinate: Coordinate): Option[V]
}

class EagerGeoGraphContainer[V <: GeoVertex](override val vertices: List[V]) extends EagerGraphContainer[V, EagerGeoGraphContainer[V]](vertices) with GeoGraphContainer[V] {
  val constructor: Constructor = (vertices: List[V]) ⇒ new EagerGeoGraphContainer(vertices)

  // FIXME reemplazar por GeoSearch
  override def findNearest(coordinate: Coordinate): Option[V] = vertices match {
    case Nil ⇒ None
    case list ⇒
      val (closestVertex, _) = list.tail.foldLeft((list.head, list.head.coordinate.distanceTo(coordinate))) {
        case (before @ (partialClosest: V, distanceToBefore: Double), next: V) ⇒
          val distanceToNext: Double = next.coordinate.distanceTo(coordinate)
          if (distanceToNext < distanceToBefore) (next, distanceToNext)
          else before
      }
      Some(closestVertex)
  }
}

trait LazyGeoGraphContainer[V <: GeoVertex] extends LazyGraphContainer[V] with GeoGraphContainer[V]

object EagerGeoGraphContainer {

  def joinGraphs[V <: GeoVertex](graphs: List[EagerGeoGraphContainer[V]]): EagerGeoGraphContainer[V] = {
    val vertices: List[V] = graphs.flatMap(graph ⇒ graph.vertices)
    new EagerGeoGraphContainer(vertices)
  }
}

object GraphContainer {

  def createGeoNodes(vertexData: Map[Long, (List[Long], Coordinate)]): EagerGeoGraphContainer[GeoVertex] = {
    val vertices: List[GeoVertex] = vertexData.toList map {
      case (nodeId, (edgeIds, nodeCoordinate)) ⇒
        new GeoVertex(nodeId,
          edgeIds.map(neighbourId ⇒ GeoEdge(nodeId, neighbourId, nodeCoordinate.distanceTo(vertexData(neighbourId)._2))),
          nodeCoordinate)
    }
    new EagerGeoGraphContainer(vertices)
  }
}

package pathgenerator.graph

import mapdomain.graph.{Edge, GeoEdge, GeoVertex, Vertex}

trait Heuristic[E <: Edge, V <: Vertex[E]] {
  def apply(node: V): Double
  def apply(from: V, to: V): Double
}

case class TrivialHeuristic[E <: Edge, V <: Vertex[E]]() extends Heuristic[E, V] {
  override def apply(node: V): Double = 0
  override def apply(from: V, to: V): Double = 0
}

case class GeoHeuristic[E <: GeoEdge, V <: GeoVertex[E]](referenceNode: V) extends Heuristic[E, V] {
  override def apply(node: V): Double = referenceNode.coordinate.distanceTo(node.coordinate)
  override def apply(from: V, to: V): Double = referenceNode.coordinate.distanceTo(to.coordinate)
}
